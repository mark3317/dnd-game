param([switch]$PrepareArchive)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot '.local/tts/venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Run scripts/setup-qwen-tts.ps1 first.' }
Push-Location -LiteralPath $projectRoot
try {
    if ($PrepareArchive) {
        & $python scripts/tts/prepare_archive.py
    } else {
        $existing = try { Invoke-RestMethod 'http://127.0.0.1:8091/speech/health' -TimeoutSec 2 } catch { $null }
        if ($existing -and $existing.model -like 'Qwen/*') {
            Write-Host "Qwen TTS is already running: $($existing.status)"
            return
        }
        & $python -u scripts/tts/service.py
    }
    if ($LASTEXITCODE -ne 0) { throw 'Qwen TTS stopped with an error.' }
} finally { Pop-Location }
