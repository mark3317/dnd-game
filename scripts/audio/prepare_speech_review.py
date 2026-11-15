"""Build a reversible A/B level/boundary candidate from existing Qwen recordings.

No synthesis, GPU, model import, network requests, or changes to shipped audio.
Whole-file RMS/sample peak are engineering metrics, not perceived loudness/LUFS.
"""
import argparse
import ast
import hashlib
import html
import json
import math
from pathlib import Path
import shutil
import wave

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'assets/audio/speech-review-v1'
SOURCE_PACK = ROOT / 'assets/audio/speech-qwen-v1'
SHIPPED = ROOT / 'shared/src/commonMain/composeResources/files/speech'
PARAMETERS = {
    'revision': 'rms-peak-edge-v1',
    'target_whole_file_rms_dbfs': -23.0,
    'maximum_absolute_gain_db': 6.0,
    'sample_peak_ceiling_dbfs': -3.0,
    'edge_fade_ms': 2.0,
    'edge_fade_shape': 'half-cosine from zero to unity / reverse',
    'near_silence_threshold_dbfs': -60.0,
    'quantization': 'round-to-nearest-even PCM16; no added dither',
    'trim_resample_compress_limit_denoise': False,
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def literal_assignment(path, variable):
    tree = ast.parse(path.read_text(encoding='utf-8'))
    matches = [node.value for node in ast.walk(tree) if isinstance(node, ast.Assign)
               and any(isinstance(target, ast.Name) and target.id == variable for target in node.targets)]
    if len(matches) != 1:
        raise ValueError('Expected one literal assignment: ' + variable)
    return ast.literal_eval(matches[0])  # Parse data only; do not import the GPU service.


def source_selection():
    manifest_path = SOURCE_PACK / 'manifest.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    phrases = manifest['phrases']
    narrator = next(item for item in phrases if item['voice'] == 'narrator')
    archivist = min((item for item in phrases if item['voice'] == 'archivist'), key=lambda item: item['duration_ms'])
    courier = next(item for item in phrases if item['voice'] == 'courier')
    heroes = literal_assignment(ROOT / 'scripts/tts/prepare_archive.py', 'samples')
    items = [{**item, 'source': SHIPPED / item['file'], 'selection': reason} for item, reason in [
        (narrator, 'First complete opening sentence, familiar scene reference'),
        (archivist, 'Shortest existing complete archivist sentence; no mid-sentence continuation'),
        (courier, 'Only existing courier recording'),
    ]]
    for voice in ('bran', 'rin', 'mira', 'lada'):
        item = next(item for item in heroes if item['voice'] == voice)
        items.append({**item, 'source': SOURCE_PACK / 'samples' / (voice + '.wav'), 'selection': 'Existing dedicated hero sample'})
    voices = literal_assignment(ROOT / 'scripts/tts/service.py', 'VOICES')
    if {item['voice'] for item in items} != set(voices) or len(items) != 7:
        raise ValueError('Review must include exactly the seven current voice IDs')
    # Validate recorded hashes where the historical manifest supplies them.
    recorded_hashes = {item['id'] + '.wav': item['sha256'] for item in manifest['measurements'] if 'sha256' in item}
    for phrase in phrases:
        path = SHIPPED / phrase['file']
        if sha(path.read_bytes()) != recorded_hashes[phrase['file']]:
            raise ValueError('Shipped audio differs from source manifest: ' + phrase['file'])
    protected = [SHIPPED / phrase['file'] for phrase in phrases]
    protected += [SOURCE_PACK / 'samples' / (voice + '.wav') for voice in ('bran', 'rin', 'mira', 'lada')]
    protected += [SHIPPED / 'catalog.json', manifest_path]
    return items, voices, manifest['model'], {str(path.relative_to(ROOT)): sha(path.read_bytes()) for path in protected}


def db(value):
    return round(20.0 * math.log10(value), 6) if value > 0 else None


def measure(samples, rate):
    samples = np.asarray(samples, dtype=np.float64)
    if samples.ndim != 1 or samples.size == 0 or not np.isfinite(samples).all():
        raise ValueError('Expected finite nonempty mono samples')
    peak = float(np.max(np.abs(samples)))
    rms = float(np.sqrt(np.mean(samples * samples)))
    active = np.flatnonzero(np.abs(samples) > 10 ** (PARAMETERS['near_silence_threshold_dbfs'] / 20))
    edge = min(samples.size, round(rate * .005))
    return {
        'sample_rate': int(rate), 'frames': int(samples.size), 'duration_ms': round(samples.size * 1000 / rate, 6),
        'whole_file_rms_dbfs': db(rms), 'sample_peak_dbfs': db(peak),
        'crest_db': round(20 * math.log10(peak / rms), 6) if rms and peak else None,
        'near_full_scale_samples': int(np.count_nonzero(np.abs(samples) >= 32760 / 32768)),
        'first_sample': float(samples[0]), 'last_sample': float(samples[-1]),
        'first_sample_jump_dbfs': db(abs(float(samples[0]))), 'last_sample_jump_dbfs': db(abs(float(samples[-1]))),
        'edge_5ms_peak_dbfs': db(float(np.max(np.abs(np.r_[samples[:edge], samples[-edge:]])))),
        'leading_near_silence_ms': round((int(active[0]) if active.size else samples.size) * 1000 / rate, 6),
        'trailing_near_silence_ms': round((samples.size - 1 - int(active[-1]) if active.size else samples.size) * 1000 / rate, 6),
        'dc_mean': float(np.mean(samples)),
    }


def candidate(samples, rate):
    original = np.asarray(samples, dtype=np.float64)
    if original.ndim != 1 or original.size == 0 or not np.isfinite(original).all():
        raise ValueError('Expected finite nonempty mono samples')
    rms = float(np.sqrt(np.mean(original * original)))
    peak = float(np.max(np.abs(original)))
    if not rms or not peak:
        raise ValueError('Do not amplify a silent file')
    requested = PARAMETERS['target_whole_file_rms_dbfs'] - 20 * math.log10(rms)
    bounded = float(np.clip(requested, -PARAMETERS['maximum_absolute_gain_db'], PARAMETERS['maximum_absolute_gain_db']))
    # Quantized ceiling ensures round-to-PCM16 cannot exceed the stated sample peak.
    ceiling = math.floor(32768 * 10 ** (PARAMETERS['sample_peak_ceiling_dbfs'] / 20)) / 32768
    peak_bound = 20 * math.log10(ceiling / peak)
    gain = min(bounded, peak_bound)
    if gain < -PARAMETERS['maximum_absolute_gain_db']:
        raise ValueError('Peak ceiling requires attenuation beyond the agreed gain cap')
    adjusted = original * 10 ** (gain / 20)
    fade_samples = round(rate * PARAMETERS['edge_fade_ms'] / 1000)
    if fade_samples < 2 or fade_samples * 2 > original.size:
        raise ValueError('Recording is too short for the agreed edge fade')
    fade = .5 * (1 - np.cos(np.linspace(0, np.pi, fade_samples)))
    adjusted[:fade_samples] *= fade
    adjusted[-fade_samples:] *= fade[::-1]
    pcm = np.rint(adjusted * 32768).astype('<i2')
    output = pcm.astype(np.float64) / 32768
    if np.max(np.abs(output)) > ceiling:
        raise ValueError('Candidate peak exceeds the agreed ceiling')
    return pcm, {
        'requested_rms_gain_db': round(requested, 6), 'gain_after_absolute_cap_db': round(bounded, 6),
        'peak_bound_gain_db': round(peak_bound, 6), 'applied_gain_db': round(gain, 6),
        'gain_cap_active': not math.isclose(requested, bounded), 'peak_cap_active': peak_bound < bounded,
        'edge_fade_samples_each': fade_samples,
        'gain_is_constant_except_edge_fades': True,
    }


def write_pcm(path, pcm, rate):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(rate)
        output.writeframes(pcm.tobytes())


def summary(records, key):
    levels = [item[key]['whole_file_rms_dbfs'] for item in records]
    return {'min_rms_dbfs': min(levels), 'max_rms_dbfs': max(levels),
            'rms_spread_db': round(max(levels) - min(levels), 6),
            'near_full_scale_samples': sum(item[key]['near_full_scale_samples'] for item in records)}


def preview(records):
    cards = []
    for item in records:
        voice = html.escape(item['voice'])
        original = item['original_metrics']; processed = item['candidate_metrics']
        cards.append(f'''<article data-voice="{voice}"><h2>{voice} · {html.escape(item['speaker'])}</h2>
<p>{html.escape(item['text'])}</p><p class="metrics">A RMS {original['whole_file_rms_dbfs']:.2f} / peak {original['sample_peak_dbfs']:.2f} dBFS · B RMS {processed['whole_file_rms_dbfs']:.2f} / peak {processed['sample_peak_dbfs']:.2f} dBFS · gain {item['processing']['applied_gain_db']:+.2f} dB</p>
<div class="pair"><section><h3>A · Original</h3><audio controls preload="none" src="original/{voice}.wav"></audio></section><section><h3>B · Technical candidate</h3><audio controls preload="none" src="candidate/{voice}.wav"></audio></section></div>
<div class="controls"><button data-action="switch">Переключить A/B с той же позиции</button><button data-action="start">Начало · 1 сек</button><button data-action="end">Конец · 1 сек</button></div>
<details><summary>Исходная инструкция голоса</summary><p>{html.escape(item['voice_instruction'])}</p></details>
<label>Выбор для этой записи <select><option>Не оценено</option><option>A · исходник</option><option>B · кандидат</option><option>Без разницы</option><option>Нужна другая обработка</option></select></label>
<label>Замечание об уровне / границах <textarea rows="2" placeholder="Тише, громче, щелчок, срезанный звук, не заметил разницы…"></textarea></label></article>''')
    template = '''<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Сравнение уровней голосов · A/B</title>
<style>body{max-width:1080px;margin:30px auto;padding:0 18px;background:#101a25;color:#e8edf0;font:17px/1.5 system-ui}h1,h2{color:#e6b66a}article{margin:24px 0;padding:20px;background:#1c2a38;border:1px solid #405064}h2{margin-top:0}.pair{display:grid;grid-template-columns:1fr 1fr;gap:16px}audio,textarea{width:100%}.controls{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0}button,select{padding:10px;background:#24384b;color:inherit;border:1px solid #b7935c;cursor:pointer}label{display:block;margin:12px 0}.metrics{color:#b8c7d4}textarea{box-sizing:border-box;background:#101a25;color:inherit;border:1px solid #677b8e;padding:8px}@media(max-width:700px){.pair{grid-template-columns:1fr}}</style>
<h1>Уровень и края записей · A/B</h1><p>A — неизменённый исходник. B — один технический кандидат: RMS −23 dBFS, gain не более ±6 dB, sample peak не выше −3 dBFS, края по 2 мс. Это не LUFS-нормализация, не новая генерация и не исправление произношения.</p>
<p>Начните с одной записи, затем сравните громкость разных персонажей при одной громкости устройства. Отдельно отметьте начало и конец. Оценку на этой странице делает слушатель; исходники игры не заменены.</p>
<label>Общая громкость предпросмотра <input id="volume" type="range" min="0" max="1" step="0.01" value="0.5"> <output id="level">50%</output></label>
<button id="export">Скачать мои заметки JSON</button><p id="status" role="status">Оценки хранятся только в открытой странице до скачивания; отправки в сеть нет.</p>
__CARDS__
<script>
const audio=[...document.querySelectorAll('audio')], stops=new WeakMap(); let last=null;
for(const p of audio){p.volume=.5;p.addEventListener('play',()=>{audio.forEach(other=>{if(other!==p)other.pause()});last=p});p.addEventListener('timeupdate',()=>{const stop=stops.get(p);if(stop!==undefined&&p.currentTime>=stop){p.pause();stops.delete(p)}})}
document.querySelector('#volume').oninput=e=>{audio.forEach(p=>p.volume=Number(e.target.value));document.querySelector('#level').textContent=Math.round(Number(e.target.value)*100)+'%'};
async function seekAndPlay(p,time,duration){if(p.readyState===0){p.load();await new Promise((resolve,reject)=>{p.addEventListener('loadedmetadata',resolve,{once:true});p.addEventListener('error',reject,{once:true})})}p.currentTime=Math.max(0,Math.min(time,p.duration||time));if(duration)stops.set(p,p.currentTime+duration);else stops.delete(p);await p.play()}
for(const card of document.querySelectorAll('article')){card.querySelectorAll('button').forEach(button=>button.onclick=async()=>{const pair=[...card.querySelectorAll('audio')];const current=pair.includes(last)?last:pair[0];try{if(button.dataset.action==='switch'){const next=pair.find(p=>p!==current);const position=current.currentTime;current.pause();await seekAndPlay(next,position)}else if(button.dataset.action==='start'){await seekAndPlay(current,0,1)}else{if(current.readyState===0){current.load();await new Promise((resolve,reject)=>{current.addEventListener('loadedmetadata',resolve,{once:true});current.addEventListener('error',reject,{once:true})})}await seekAndPlay(current,Math.max(0,current.duration-1),1)}document.querySelector('#status').textContent='Сравнение: '+card.dataset.voice}catch{document.querySelector('#status').textContent='Не удалось воспроизвести файл. Используйте встроенный аудиоплеер.'}})}
document.querySelector('#export').onclick=()=>{const notes={pack:'speech-review-v1',automatic_quality_claim:false,ratings:[...document.querySelectorAll('article')].map(card=>({voice:card.dataset.voice,choice:card.querySelector('select').value,notes:card.querySelector('textarea').value}))};const url=URL.createObjectURL(new Blob([JSON.stringify(notes,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='speech-review-notes.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)};
</script></html>'''
    return template.replace('__CARDS__', ''.join(cards))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Verify the existing pack; write no files')
    args = parser.parse_args()
    items, voices, model, protected = source_selection()
    if not args.check:
        (PACK / 'original').mkdir(parents=True, exist_ok=True)
        (PACK / 'candidate').mkdir(parents=True, exist_ok=True)
    records = []
    for item in items:
        source = item['source']; voice = item['voice']
        info = sf.info(source)
        if info.channels != 1 or info.subtype != 'PCM_16' or info.samplerate != 24_000:
            raise ValueError('Source must be original mono PCM16 / 24 kHz: ' + voice)
        samples, rate = sf.read(source, dtype='float64')
        pcm, processing = candidate(samples, rate)
        original = PACK / 'original' / (voice + '.wav')
        output = PACK / 'candidate' / (voice + '.wav')
        if args.check:
            with wave.open(str(output), 'rb') as recording:
                if recording.getframerate() != rate or recording.getnchannels() != 1 or recording.getsampwidth() != 2 or recording.readframes(recording.getnframes()) != pcm.tobytes():
                    raise ValueError('Candidate does not match processing: ' + voice)
        else:
            shutil.copyfile(source, original)
            write_pcm(output, pcm, rate)
        if original.read_bytes() != source.read_bytes():
            raise ValueError('Original copy is not byte-identical: ' + voice)
        written, written_rate = sf.read(output, dtype='float64')
        if len(written) != len(samples) or written_rate != rate:
            raise ValueError('Frame count/rate changed: ' + voice)
        speaker, instruction = voices[voice]
        records.append({
            'voice': voice, 'speaker': speaker, 'text': item['text'], 'selection_reason': item['selection'],
            'source': str(source.relative_to(ROOT)), 'source_sha256': sha(source.read_bytes()),
            'original_file': str(original.relative_to(PACK)), 'original_sha256': sha(original.read_bytes()),
            'candidate_file': str(output.relative_to(PACK)), 'candidate_sha256': sha(output.read_bytes()),
            'voice_instruction': instruction, 'processing': processing,
            'original_metrics': measure(samples, rate), 'candidate_metrics': measure(written, rate),
        })
    manifest = {
        'pack': 'speech-review-v1', 'status': 'technical_candidate_awaiting_human_listening',
        'source_pack': 'assets/audio/speech-qwen-v1', 'source_model': model,
        'voice_instruction_source': 'scripts/tts/service.py VOICES (read as AST data, not imported)',
        'voice_instruction_source_sha256': sha((ROOT / 'scripts/tts/service.py').read_bytes()),
        'historical_instruction_capture': 'Source manifest does not independently retain generation-time instructions; these are current reference instructions, unchanged by this script.',
        'processing_script_sha256': sha(Path(__file__).read_bytes()),
        'parameters': PARAMETERS, 'tools': {'numpy': np.__version__, 'soundfile': sf.__version__},
        'limitations': ['whole-file RMS is not LUFS or perceived loudness', 'sample peak is not oversampled true peak',
                        '2ms fades do not repair internal artifacts or truncated words', 'no listening, pronunciation or acting acceptance performed'],
        'original_summary': summary(records, 'original_metrics'), 'candidate_summary': summary(records, 'candidate_metrics'),
        'protected_source_hashes': protected, 'recordings': records,
    }
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if json.loads((PACK / 'manifest.json').read_text(encoding='utf-8')) != manifest:
            raise ValueError('Manifest differs from measured/current sources')
        if (PACK / 'preview.html').read_text(encoding='utf-8') != preview(records):
            raise ValueError('Preview differs from the deterministic manifest')
    else:
        (PACK / 'manifest.json').write_text(manifest_text, encoding='utf-8')
        (PACK / 'preview.html').write_text(preview(records), encoding='utf-8')
    for name, digest in protected.items():
        if sha((ROOT / name).read_bytes()) != digest:
            raise ValueError('Protected original changed during processing: ' + name)
    print(json.dumps({'recordings': len(records), 'original': manifest['original_summary'],
                      'candidate': manifest['candidate_summary'], 'protected_sources_unchanged': len(protected),
                      'mode': 'check' if args.check else 'build', 'api_requests': 0}))


if __name__ == '__main__':
    main()
