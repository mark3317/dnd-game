"""Exercise four concurrent consumers through the game's actual HTTP proxy."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
import urllib.request
import wave
import io


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default='http://127.0.0.1:18082')
    args = parser.parse_args()

    def api(path, payload=None):
        data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode('utf-8')
        request = urllib.request.Request(args.base + '/speech/' + path, data=data,
            headers={'Content-Type': 'application/json', 'Origin': 'http://localhost:8081'})
        with urllib.request.urlopen(request, timeout=15) as response:
            assert response.headers['Access-Control-Allow-Origin'] == 'http://localhost:8081'
            return json.load(response)

    results = []
    for request in [
        {'voice': 'bran', 'text': 'Я держу дверь. Рин, проверь, не поднимается ли вода у лестницы!'},
        {'voice': 'archivist', 'text': 'За этой дверью грамоты всего посёлка. Только осторожнее: нижняя ступень раскололась.'},
    ]:
        started = time.monotonic()
        with ThreadPoolExecutor(max_workers=4) as pool:
            replies = list(pool.map(lambda _: api('prepare', request), range(4)))
        assert len({reply['id'] for reply in replies}) == 1, 'Duplicate generations'
        result = replies[0]
        deadline = time.monotonic() + 90
        while result['status'] in ('queued', 'generating'):
            assert time.monotonic() < deadline, 'Live generation timeout'
            time.sleep(.2)
            result = api('status/' + result['id'])
        assert result['status'] == 'ready', result
        ready_ms = round((time.monotonic() - started) * 1000)
        with urllib.request.urlopen(args.base + '/speech/audio/' + result['id'] + '.wav', timeout=15) as response:
            audio = response.read()
        with wave.open(io.BytesIO(audio)) as recording:
            assert recording.getframerate() == 24000
            assert recording.getnchannels() == 1
            assert recording.getnframes() > 2400
        cache_started = time.monotonic()
        assert api('prepare', request)['status'] == 'ready'
        evidence = {'voice': request['voice'], 'clients': 4, 'ready_ms': ready_ms,
            'cached_ms': round((time.monotonic() - cache_started) * 1000), 'bytes': len(audio), **result}
        results.append(evidence)
        print(json.dumps(evidence), flush=True)
    root = Path(__file__).resolve().parents[2]
    (root / 'assets/audio/speech-qwen-v1/live-probe.json').write_text(json.dumps(results, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
