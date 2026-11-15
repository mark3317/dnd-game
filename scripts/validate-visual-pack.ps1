param([string]$PackDirectory = 'assets/visual')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = (Resolve-Path -LiteralPath $PackDirectory).Path
$prompts = Get-Content -LiteralPath (Join-Path $root 'generation-prompts.json') -Raw -Encoding utf8 | ConvertFrom-Json
$assets = @()
$ids = @{}
foreach ($spec in $prompts.assets) {
    if ($ids.ContainsKey($spec.id)) { throw "Duplicate ID: $($spec.id)" }
    $ids[$spec.id] = $true
    $path = Join-Path $root $spec.file
    $image = [System.Drawing.Bitmap]::new($path)
    try {
        $width = $image.Width; $height = $image.Height
        $transparentSamples = 0; $opaqueSamples = 0
        for ($y = 0; $y -lt $height; $y += 24) {
            for ($x = 0; $x -lt $width; $x += 24) {
                $alpha = $image.GetPixel($x, $y).A
                if ($alpha -eq 0) { $transparentSamples++ }
                if ($alpha -ge 240) { $opaqueSamples++ }
            }
        }
        if ($spec.kind -eq 'avatar') {
            if ($width -ne 1086 -or $height -ne 1448) { throw "Avatar size mismatch: $($spec.id)" }
            if ($transparentSamples -eq 0 -or $opaqueSamples -eq 0) { throw "Missing avatar transparency/content: $($spec.id)" }
        } elseif ($width -ne 1672 -or $height -ne 941) { throw "Background size mismatch: $($spec.id)" }
        $assets += [ordered]@{
            id = $spec.id; file = $spec.file; kind = $spec.kind; character = $spec.character
            emotion = $spec.emotion; locationId = $spec.scene; width = $width; height = $height
            hasTransparentSamples = ($transparentSamples -gt 0); mimeType = 'image/png'
            bytes = (Get-Item -LiteralPath $path).Length
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
            referenceAssetId = $spec.reference; generator = 'image_gen'; createdOn = '2026-11-15'
            status = 'generated-viewed-awaiting-user-art-review-and-game-integration'
        }
    } finally { $image.Dispose() }
}
if ($assets.Count -ne 27) { throw 'Expected 27 assets' }
foreach ($asset in $assets) {
    if ($asset.referenceAssetId -and -not $ids.ContainsKey($asset.referenceAssetId)) { throw 'Unknown reference asset' }
}
foreach ($hero in @('rin','mira','bran')) {
    foreach ($emotion in @('neutral','happy','angry','surprised','worried','sad')) {
        if (-not $ids.ContainsKey("$hero-$emotion")) { throw 'Missing expression' }
    }
}
$bindings = Get-Content -LiteralPath (Join-Path $root 'scene-bindings.json') -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($location in $bindings.locations.PSObject.Properties) {
    foreach ($rule in $location.Value.backgroundRules) {
        if (-not $ids.ContainsKey($rule.assetId)) { throw 'Unknown scene background' }
    }
}
foreach ($npc in $bindings.npcs.PSObject.Properties) {
    if (-not $ids.ContainsKey($npc.Value.assetId)) { throw 'Unknown NPC portrait' }
}
$manifest = [ordered]@{
    schemaVersion = 1; packId = 'flooded-archive-and-ready-heroes-v1'; scenarioId = 'flooded_archive'
    styleVersion = 'storybook-gouache-v1'; assets = $assets
}
[System.IO.File]::WriteAllText((Join-Path $root 'manifest.json'), ($manifest | ConvertTo-Json -Depth 10), [System.Text.UTF8Encoding]::new($false))
$totalBytes = 0
foreach ($asset in $assets) { $totalBytes += $asset.bytes }
[pscustomobject]@{Images=$assets.Count; Backgrounds=@($assets | Where-Object {$_.kind -eq 'background'}).Count; Avatars=@($assets | Where-Object {$_.kind -eq 'avatar'}).Count; MiB=[Math]::Round($totalBytes/1MB,2); Validation='passed'}
