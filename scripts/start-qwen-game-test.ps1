param([int]$Port = 18082, [switch]$Rebuild, [switch]$WithGameMaster)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot '.local/tts/venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Run scripts/setup-qwen-tts.ps1 first.' }
$candidates = @($env:JAVA_HOME)
foreach ($root in @('.gradle/jdks', '.jdks')) {
    $candidates += Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE $root) -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName
}
$jdk = $candidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'release')) -and
    (Select-String -LiteralPath (Join-Path $_ 'release') -Pattern '^JAVA_VERSION="21[."]' -Quiet)
} | Select-Object -First 1
if (-not $jdk) { throw 'JDK 21 not found. Set JAVA_HOME.' }
$names = @('JAVA_HOME', 'DND_SERVER_PORT', 'ANTHROPIC_API_KEY')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $jdk
    $env:DND_SERVER_PORT = [string]$Port
    # The default test uses authored NPC replies; paid text generation is opt-in.
    $env:ANTHROPIC_API_KEY = $null
    $launcher = Join-Path $projectRoot 'server/build/install/server/bin/server.bat'
    if ($Rebuild -or -not (Test-Path -LiteralPath $launcher)) {
        & ./gradlew.bat :server:installDist --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Server build failed.' }
    }
    $health = try { Invoke-RestMethod 'http://127.0.0.1:8091/speech/health' -TimeoutSec 2 } catch { $null }
    if (-not $health) {
        $runtime = Join-Path $projectRoot '.local/tts'
        $worker = Start-Process -FilePath $python -ArgumentList @('-u', 'scripts/tts/service.py') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'service.log') -RedirectStandardError (Join-Path $runtime 'service.err.log')
        $worker.Id | Set-Content -LiteralPath (Join-Path $runtime 'service.pid')
        Write-Host 'Qwen is loading in the background; startup progress is in .local/tts/service.log.'
    }
    Write-Host "Connect clients to ws://localhost:$Port (on a phone use this PC's LAN address)."
    Write-Host 'Choose Archive Demo. Ready phrases are bundled; new player/NPC phrases use local Qwen.'
    if ($WithGameMaster) {
        $env:ANTHROPIC_API_KEY = $previous['ANTHROPIC_API_KEY']
        & (Join-Path $PSScriptRoot 'start-claude-server.ps1')
    }
    else { & $launcher }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
