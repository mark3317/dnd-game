$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $tools = Join-Path $projectRoot '.local/tts/tools'
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $uv = Join-Path $tools 'uv.exe'
    if (-not (Test-Path -LiteralPath $uv)) {
        Invoke-WebRequest 'https://github.com/astral-sh/uv/releases/download/0.13.8/uv-x86_64-pc-windows-msvc.zip' -OutFile (Join-Path $tools 'uv.zip')
        Expand-Archive -LiteralPath (Join-Path $tools 'uv.zip') -DestinationPath $tools -Force
    }
    $env:UV_PYTHON_INSTALL_DIR = Join-Path $projectRoot '.local/tts/python'
    $env:UV_CACHE_DIR = Join-Path $projectRoot '.local/tts/uv-cache'
    & $uv venv --python 3.12 --allow-existing .local/tts/venv
    if ($LASTEXITCODE -ne 0) { throw 'Python setup failed.' }
    & $uv pip install --python .local/tts/venv/Scripts/python.exe torch==2.11.0 torchaudio==2.11.0 --index-url https://download.pytorch.org/whl/cu128
    if ($LASTEXITCODE -ne 0) { throw 'CUDA PyTorch setup failed.' }
    & $uv pip install --python .local/tts/venv/Scripts/python.exe -r scripts/tts/requirements.txt
    if ($LASTEXITCODE -ne 0) { throw 'Qwen setup failed.' }
} finally { Pop-Location }
