"""Run the built server on macOS/Linux with its key confined to the child environment.

Build :server:installDist first. Arguments after -- are passed to the server,
including --evaluate-ai. This launcher never invokes Gradle with credentials.
"""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def server_environment(current, config_path):
    result = dict(current)
    if not result.get('ANTHROPIC_API_KEY', '').strip():
        try:
            settings = json.loads(config_path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            raise ValueError('Set ANTHROPIC_API_KEY or configure server/claude.local.json') from None
        if not isinstance(settings, dict):
            raise ValueError('Invalid local AI configuration')
        key = settings.get('apiKey')
        if not isinstance(key, str) or not key.strip() or any(c.isspace() for c in key.strip()):
            raise ValueError('Local AI configuration needs a valid apiKey')
        result['ANTHROPIC_API_KEY'] = key.strip()
        for field, name in [('baseUrl', 'DND_CLAUDE_BASE_URL'), ('model', 'DND_CLAUDE_MODEL')]:
            value = settings.get(field)
            if value is not None and not isinstance(value, str):
                raise ValueError('Invalid local AI configuration')
            if value and not result.get(name):
                result[name] = value
    result.setdefault('DND_CLAUDE_BASE_URL', 'https://ai.starimg.ru')
    result.setdefault('DND_CLAUDE_MODEL', 'gpt-6-luna')
    return result


def main():
    if os.name == 'nt':
        raise SystemExit('On Windows use scripts/start-claude-server.ps1')
    launcher = ROOT / 'server/build/install/server/bin/server'
    if not launcher.is_file():
        raise SystemExit('Build the server first: ./gradlew :server:installDist')
    try:
        environment = server_environment(os.environ, ROOT / 'server/claude.local.json')
    except ValueError as failure:
        raise SystemExit(str(failure)) from None
    arguments = sys.argv[1:]
    if arguments[:1] == ['--']:
        arguments = arguments[1:]
    try:
        return subprocess.run([str(launcher), *arguments], cwd=ROOT, env=environment, check=False).returncode
    except OSError:
        raise SystemExit('Could not start the server; check Java and launcher permissions') from None


if __name__ == '__main__':
    sys.exit(main())
