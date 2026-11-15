"""Measure bundled PCM16 speech without loading a model or sending any requests.

RMS dBFS describes whole-file signal level, not perceived loudness (LUFS).
This report cannot assess pronunciation, acting, or whether speech was cut off.
"""
import argparse
from array import array
import hashlib
import json
import math
from pathlib import Path
import sys
import wave

ROOT = Path(__file__).resolve().parents[2]


def measure(path):
    with wave.open(str(path), 'rb') as recording:
        if recording.getsampwidth() != 2 or recording.getnchannels() != 1:
            raise ValueError('Expected mono PCM16: ' + path.name)
        rate = recording.getframerate()
        frames = recording.getnframes()
        samples = array('h', recording.readframes(frames))
    if sys.byteorder != 'little':
        samples.byteswap()
    if not samples or len(samples) != frames:
        raise ValueError('Empty or truncated recording: ' + path.name)
    peak = max(abs(sample) for sample in samples) / 32768
    rms = math.sqrt(sum(sample * sample for sample in samples) / frames) / 32768
    threshold = 16  # Near-silence at about -66 dBFS; not a speech detector.
    first = next((i for i, value in enumerate(samples) if abs(value) > threshold), frames)
    last = next((i for i, value in enumerate(reversed(samples)) if abs(value) > threshold), frames)
    return {
        'file': path.name,
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'sample_rate': rate,
        'duration_ms': round(frames * 1000 / rate),
        'rms_dbfs': round(20 * math.log10(rms), 2) if rms else None,
        'peak_dbfs': round(20 * math.log10(peak), 2) if peak else None,
        'near_full_scale_samples': sum(abs(value) >= 32760 for value in samples),
        'leading_near_silence_ms': round(first * 1000 / rate),
        'trailing_near_silence_ms': round(last * 1000 / rate),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=ROOT / 'shared/src/commonMain/composeResources/files/speech')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    recordings = [measure(path) for path in sorted(args.directory.glob('*.wav'))]
    if not recordings:
        parser.error('No WAV recordings found')
    levels = [entry['rms_dbfs'] for entry in recordings if entry['rms_dbfs'] is not None]
    report = {
        'method': 'mono PCM16 whole-file RMS/peak dBFS; not LUFS or listening acceptance',
        'recordings': recordings,
        'summary': {
            'files': len(recordings),
            'duration_ms': sum(entry['duration_ms'] for entry in recordings),
            'min_rms_dbfs': min(levels) if levels else None,
            'max_rms_dbfs': max(levels) if levels else None,
            'near_full_scale_samples': sum(entry['near_full_scale_samples'] for entry in recordings),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report['summary']))


if __name__ == '__main__':
    main()
