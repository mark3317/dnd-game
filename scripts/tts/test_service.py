import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('tts_service', Path(__file__).with_name('service.py'))
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)


class ServiceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.previous_cache = service.CACHE
        self.previous_engine_state = service.engine_state
        service.engine_state = 'loading'
        service.CACHE = Path(self.temporary.name)
        service.records.clear()
        while not service.jobs.empty():
            service.jobs.get_nowait()
            service.jobs.task_done()

    def tearDown(self):
        service.CACHE = self.previous_cache
        service.engine_state = self.previous_engine_state
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

    def test_startup_import_failure_finishes_waiting_jobs_and_rejects_new_work(self):
        first = service.prepare('Осторожно, вода!', 'narrator')
        second = service.prepare('Я прикрою отход.', 'bran')
        original_import = __import__

        def missing_torch(name, *args, **kwargs):
            if name == 'torch':
                raise ImportError('private dependency path must not be logged')
            return original_import(name, *args, **kwargs)

        with patch('builtins.__import__', side_effect=missing_torch), patch('builtins.print') as output:
            service.worker()
        self.assertEqual(service.health()['status'], 'failed')
        self.assertEqual(service.status(first['id'])['status'], 'failed')
        self.assertEqual(service.status(second['id'])['status'], 'failed')
        self.assertEqual(service.jobs.unfinished_tasks, 0)
        with self.assertRaises(service.HTTPException) as error:
            service.request_speech(service.Request(text='Новая реплика'))
        self.assertEqual(error.exception.status_code, 503)
        self.assertNotIn('private dependency', str(output.call_args_list))

    def test_cached_audio_remains_available_when_model_is_unavailable(self):
        import json
        key = service.identity('Дождь стих.', 'narrator')
        (service.CACHE / (key + '.wav')).write_bytes(b'RIFF')
        (service.CACHE / (key + '.json')).write_text(json.dumps({'id': key, 'status': 'ready', 'duration_ms': 1200, 'generation_ms': 100}))
        service.engine_state = 'failed'
        result = service.request_speech(service.Request(text='Дождь стих.'))
        self.assertEqual(result['status'], 'ready')
        self.assertTrue(service.jobs.empty())


if __name__ == '__main__':
    unittest.main()
