import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('tts_service', Path(__file__).with_name('service.py'))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.previous_cache = service.CACHE
        service.CACHE = Path(self.temporary.name)
        service.records.clear()
        while not service.jobs.empty():
            service.jobs.get_nowait()

    def tearDown(self):
        service.CACHE = self.previous_cache
        self.temporary.cleanup()

    def test_four_players_share_one_generation(self):
        results = [service.prepare('Осторожно, вода!', 'narrator') for _ in range(4)]
        self.assertEqual(len({r['id'] for r in results}), 1)
        self.assertEqual(service.jobs.qsize(), 1)
        other_voice = service.prepare('Осторожно, вода!', 'bran')
        self.assertNotEqual(results[0]['id'], other_voice['id'])

    def test_unknown_voice_and_path_traversal_are_rejected(self):
        with self.assertRaises(service.HTTPException):
            service.prepare('Текст', '../voice')
        with self.assertRaises(service.HTTPException):
            service.audio('../secrets')

    def test_cached_recording_survives_worker_restart(self):
        import json
        key = service.identity('Дождь стих.', 'narrator')
        (service.CACHE / (key + '.wav')).write_bytes(b'RIFF')
        (service.CACHE / (key + '.json')).write_text(json.dumps({'id': key, 'status': 'ready', 'duration_ms': 1200, 'generation_ms': 100}))
        self.assertEqual(service.prepare('Дождь стих.', 'narrator')['status'], 'ready')
        self.assertTrue(service.jobs.empty())


if __name__ == '__main__':
    unittest.main()
