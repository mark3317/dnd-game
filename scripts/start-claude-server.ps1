param(
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $projectRoot 'server/claude.local.json'
$examplePath = Join-Path $projectRoot 'server/claude.local.example.json'
$serverLauncher = Join-Path $projectRoot 'server/build/install/server/bin/server.bat'

# The distributed server runs directly on Java; the key never enters a Gradle command line.
$jdkCandidates = @($env:JAVA_HOME)
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($javaCommand) { $jdkCandidates += Split-Path -Parent (Split-Path -Parent $javaCommand.Source) }
foreach ($jdkRoot in @('.gradle/jdks', '.jdks')) {
    $jdkCandidates += Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE $jdkRoot) -Directory -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
}
$javaHome = $jdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin/java.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'release')) -and
    (Select-String -LiteralPath (Join-Path $_ 'release') -Pattern '^JAVA_VERSION="21[."]' -Quiet)
} | Select-Object -First 1
if (-not $javaHome) { throw 'JDK 21 not found. Set JAVA_HOME to your JDK 21 directory.' }

$names = @('JAVA_HOME', 'ANTHROPIC_API_KEY', 'DND_CLAUDE_BASE_URL', 'DND_CLAUDE_MODEL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME = $javaHome
    if ($Rebuild -or -not (Test-Path -LiteralPath $serverLauncher)) {
        # Do not pass an inherited key into Gradle or its configuration cache.
        [Environment]::SetEnvironmentVariable('ANTHROPIC_API_KEY', $null, 'Process')
        & (Join-Path $projectRoot 'gradlew.bat') :server:installDist --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Server build failed.' }
    }

    if (-not (Test-Path -LiteralPath $configPath)) {
        Copy-Item -LiteralPath $examplePath -Destination $configPath
    }
    $apiKey = $previous['ANTHROPIC_API_KEY']
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        try { $settings = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json }
        catch { throw 'Cannot read server/claude.local.json. Check its JSON syntax; do not paste the key into an error report.' }
        $apiKey = [string]$settings.apiKey
    } else {
        $settings = [pscustomobject]@{
            baseUrl = $previous['DND_CLAUDE_BASE_URL']
            model = $previous['DND_CLAUDE_MODEL']
        }
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        $secureKey = Read-Host 'Paste Claude API key (hidden input)' -AsSecureString
        $apiKey = [System.Net.NetworkCredential]::new('', $secureKey).Password
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) { throw 'Claude API key is empty.' }

    $env:ANTHROPIC_API_KEY = $apiKey.Trim()
    $env:DND_CLAUDE_BASE_URL = [string]$settings.baseUrl
    $env:DND_CLAUDE_MODEL = [string]$settings.model
    if ([string]::IsNullOrWhiteSpace($env:DND_CLAUDE_BASE_URL)) { $env:DND_CLAUDE_BASE_URL = 'https://ai.starimg.ru' }
    if ([string]::IsNullOrWhiteSpace($env:DND_CLAUDE_MODEL)) { $env:DND_CLAUDE_MODEL = 'claude-sonnet-5-5' }

    Write-Host 'Starting the local Claude server. Connect game clients to ws://localhost:8080.'
    Write-Host 'The key is not printed. Stop this server with Ctrl+C.'
    & $serverLauncher
    if ($LASTEXITCODE -ne 0) { throw 'Server stopped with an error. Check its diagnostic output.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    $apiKey = $null
    $secureKey = $null
    $settings = $null
    Pop-Location
}
