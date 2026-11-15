"""Local, single-worker Qwen3-TTS service. No prompts or player text in logs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import threading
import time

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / '.local' / 'tts'
CACHE = DATA / 'audio'
MODEL = os.environ.get('DND_TTS_MODEL', 'Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice')
os.environ.setdefault('HF_HOME', str(DATA / 'huggingface'))
os.environ.setdefault('HF_HUB_DISABLE_XET', '1')
os.environ.setdefault('HF_HUB_DISABLE_TELEMETRY', '1')
VOICES = {
    'narrator': ('Ryan', 'A warm, measured storyteller. Clear native Russian pronunciation. Expressive but restrained.'),
    'bran': ('Aiden', 'A brave, grounded warrior. Low, calm, firm voice. Short decisive phrases. Speak natural Russian.'),
    'rin': ('Dylan', 'A young agile rogue. Light, quick, dry wit, restrained irony. Speak natural Russian.'),
    'mira': ('Serena', 'A thoughtful confident scholar. Clear, deliberate, curious. Speak natural Russian.'),
    'lada': ('Vivian', 'A cheerful bard. Warm lively voice, playful and engaging. Speak natural Russian.'),
    'archivist': ('Serena', 'An older archivist, composed but worried about her village. Warm, slightly stern, clear Russian.'),
    'courier': ('Eric', 'A practical young courier. Brisk, conversational, a little impatient. Speak natural Russian.'),
}
VERSION = 'qwen-fast-custom-v1'
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
app.add_middleware(CORSMiddleware,
    allow_origin_regex=r'https?://(localhost|127\.0\.0\.1)(:[0-9]+)?',
    allow_methods=['GET', 'POST'], allow_headers=['Content-Type'])
jobs = queue.Queue(maxsize=48)
lock = threading.Lock()
records = {}
engine = None
engine_state = 'loading'


class Request(BaseModel):
    text: str = Field(min_length=1, max_length=600)
    voice: str = 'narrator'


def identity(text, voice):
    value = json.dumps([VERSION, MODEL, voice, text.strip()], ensure_ascii=False, separators=(',', ':'))
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def prepare(text, voice):
    if voice not in VOICES:
        raise HTTPException(400, 'unknown_voice')
    text = text.strip()
    if not text:
        raise HTTPException(400, 'empty_text')
    key = identity(text, voice)
    with lock:
        if key in records:
            return dict(records[key])
        metadata = CACHE / f'{key}.json'
        if metadata.is_file() and (CACHE / f'{key}.wav').is_file():
            result = json.loads(metadata.read_text(encoding='utf-8'))
            records[key] = result
            return dict(result)
        result = {'id': key, 'status': 'queued', 'duration_ms': 0, 'generation_ms': 0}
        try:
            jobs.put_nowait((key, text, voice))
        except queue.Full:
            raise HTTPException(429, 'queue_full')
        records[key] = result
        return dict(result)


def worker():
    global engine, engine_state
    import torch
    import soundfile as sf
    from faster_qwen3_tts import FasterQwen3TTS
    CACHE.mkdir(parents=True, exist_ok=True)
    try:
        device = 'cuda:0'
        torch.set_num_threads(2)
        engine = FasterQwen3TTS.from_pretrained(MODEL, device=device,
            dtype=torch.bfloat16, attn_implementation='sdpa', max_seq_len=2048)
        engine.warmup(prefill_len=160)
        engine_state = 'ready'
        print(json.dumps({'event': 'tts_ready', 'device': device, 'model': MODEL}), flush=True)
    except Exception as error:
        engine_state = 'failed'
        print(json.dumps({'event': 'tts_load_failed', 'type': type(error).__name__}), flush=True)
        return
    while True:
        key, text, voice = jobs.get()
        started = time.monotonic()
        with lock:
            records[key]['status'] = 'generating'
        try:
            speaker, instruction = VOICES[voice]
            torch.manual_seed(int(key[:8], 16))
            with torch.inference_mode():
                wavs, sample_rate = engine.generate_custom_voice(text=text, language='Russian',
                    speaker=speaker, instruct=instruction, non_streaming_mode=True,
                    max_new_tokens=1024, do_sample=True)
            temporary = CACHE / f'{key}.tmp.wav'
            sf.write(str(temporary), wavs[0], sample_rate, subtype='PCM_16')
            temporary.replace(CACHE / f'{key}.wav')
            result = {'id': key, 'status': 'ready',
                'duration_ms': round(len(wavs[0]) * 1000 / sample_rate),
                'generation_ms': round((time.monotonic() - started) * 1000)}
            (CACHE / f'{key}.json').write_text(json.dumps(result), encoding='utf-8')
            with lock:
                records[key] = result
            print(json.dumps({'event': 'tts_generated', 'voice': voice, **result}), flush=True)
        except Exception as error:
            with lock:
                records[key]['status'] = 'failed'
            print(json.dumps({'event': 'tts_failed', 'id': key, 'type': type(error).__name__}), flush=True)
        finally:
            jobs.task_done()


@app.get('/speech/health')
def health():
    return {'status': engine_state, 'model': MODEL, 'queued': jobs.qsize()}


@app.post('/speech/prepare')
def request_speech(request: Request):
    if engine_state == 'failed':
        raise HTTPException(503, 'model_unavailable')
    return prepare(request.text, request.voice)


@app.get('/speech/status/{key}')
def status(key: str):
    with lock:
        result = records.get(key)
        if result is None:
            raise HTTPException(404, 'unknown_job')
        return dict(result)


@app.get('/speech/audio/{key}.wav')
def audio(key: str):
    if len(key) != 64 or any(c not in '0123456789abcdef' for c in key):
        raise HTTPException(404, 'unknown_audio')
    path = CACHE / f'{key}.wav'
    if not path.is_file():
        raise HTTPException(404, 'unknown_audio')
    return FileResponse(path, media_type='audio/wav', headers={'Cache-Control': 'private, max-age=86400'})


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8091)
    args = parser.parse_args()
    threading.Thread(target=worker, daemon=True).start()
    import uvicorn
    uvicorn.run(app, host='127.0.0.1', port=args.port, access_log=False, log_level='warning')
