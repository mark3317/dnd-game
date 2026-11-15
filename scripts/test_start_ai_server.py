import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('start_ai_server', Path(__file__).with_name('start-ai-server.py'))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class ServerEnvironmentTest(unittest.TestCase):
    def test_existing_environment_does_not_read_local_secrets_or_change_parent(self):
        parent = {'ANTHROPIC_API_KEY': 'fictional-existing-key', 'DND_CLAUDE_MODEL': 'gpt-6-luna'}
        result = launcher.server_environment(parent, Path('/nonexistent/config'))
        self.assertEqual('fictional-existing-key', result['ANTHROPIC_API_KEY'])
        self.assertEqual('https://ai.starimg.ru', result['DND_CLAUDE_BASE_URL'])
        self.assertNotIn('DND_CLAUDE_BASE_URL', parent)

    def test_ignored_config_loads_into_child_environment_preserving_explicit_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'apiKey': 'fictional-local-key', 'model': 'gpt-6-luna',
                'baseUrl': 'https://ai.starimg.ru'}))
            parent = {'DND_CLAUDE_MODEL': 'explicit-model'}
            result = launcher.server_environment(parent, config)
            self.assertEqual('fictional-local-key', result['ANTHROPIC_API_KEY'])
            self.assertEqual('explicit-model', result['DND_CLAUDE_MODEL'])
            self.assertNotIn('ANTHROPIC_API_KEY', parent)

    def test_invalid_configuration_never_exposes_its_contents_in_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            for content in ['private-invalid-json', '[]', '{"apiKey":"private invalid key"}']:
                config.write_text(content)
                with self.assertRaises(ValueError) as failure:
                    launcher.server_environment({}, config)
                self.assertNotIn('private', str(failure.exception))


if __name__ == '__main__':
    unittest.main()
