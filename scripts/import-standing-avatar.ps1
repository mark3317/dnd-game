param(
    [Parameter(Mandatory=$true)][string]$Id,
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Prompt,
    [Parameter(Mandatory=$true)][string[]]$References,
    [double]$PortraitFraction = 0.40
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$pack = Join-Path $root 'assets/visual/standing-v2'
$resources = Join-Path $root 'shared/src/commonMain/composeResources/drawable'
New-Item -ItemType Directory -Force -Path "$pack/source", "$pack/stage", "$pack/portraits" | Out-Null
$image = [System.Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Source).Path)
try {
    # Alpha is checked before import: hidden RGB must never be mistaken for a background.
    foreach ($point in @(@(0,0), @(($image.Width-1),0), @(0,($image.Height-1)), @(($image.Width-1),($image.Height-1)))) {
        # Up to 2/255 alpha is encoder noise, excluded by the silhouette bounds below.
        if ($image.GetPixel($point[0],$point[1]).A -gt 2) { throw "Visible background at corner: $Id" }
    }
    $left=$image.Width; $top=$image.Height; $right=0; $bottom=0
    for ($y=0; $y -lt $image.Height; $y+=3) {
        for ($x=0; $x -lt $image.Width; $x+=3) {
            if ($image.GetPixel($x,$y).A -ge 32) {
                $left=[Math]::Min($left,$x); $right=[Math]::Max($right,$x)
                $top=[Math]::Min($top,$y); $bottom=[Math]::Max($bottom,$y)
            }
        }
    }
    if ($right -le $left -or $bottom -le $top) { throw "Empty sprite: $Id" }
    if ($top -lt 3 -or $bottom -ge $image.Height-3) { throw "Figure touches top/bottom edge: $Id" }
    $bounds=[System.Drawing.Rectangle]::new($left,$top,$right-$left+3,$bottom-$top+3)
    $stage=[System.Drawing.Bitmap]::new(640,1024)
    $g=[System.Drawing.Graphics]::FromImage($stage)
    try {
        $g.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $scale=[Math]::Min(608.0/$bounds.Width,992.0/$bounds.Height)
        $w=[int]($bounds.Width*$scale); $h=[int]($bounds.Height*$scale)
        $dest=[System.Drawing.Rectangle]::new([int]((640-$w)/2),1008-$h,$w,$h)
        $g.DrawImage($image,$dest,$bounds,[System.Drawing.GraphicsUnit]::Pixel)
        $stage.Save("$pack/stage/$Id.png",[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $g.Dispose(); $stage.Dispose() }
    $side=[int]($bounds.Height*$PortraitFraction)
    $crop=[System.Drawing.Rectangle]::new([int](($left+$right-$side)/2),[Math]::Max(0,$top-8),$side,$side)
    $portrait=[System.Drawing.Bitmap]::new(512,512)
    $g=[System.Drawing.Graphics]::FromImage($portrait)
    try {
        $g.InterpolationMode=[System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($image,[System.Drawing.Rectangle]::new(0,0,512,512),$crop,[System.Drawing.GraphicsUnit]::Pixel)
        $portrait.Save("$pack/portraits/$Id.png",[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $g.Dispose(); $portrait.Dispose() }
} finally { $image.Dispose() }
Copy-Item -LiteralPath $Source -Destination "$pack/source/$Id.png"
Copy-Item -LiteralPath "$pack/stage/$Id.png" -Destination "$resources/stage_$Id.png"
Copy-Item -LiteralPath "$pack/portraits/$Id.png" -Destination "$resources/$Id.png"
$entry=[ordered]@{
    id=$Id; generator='built-in image_gen'; prompt=$Prompt; references=$References
    source="source/$Id.png"; stage="stage/$Id.png"; portrait="portraits/$Id.png"
    stageResource="drawable/stage_$Id.png"; portraitResource="drawable/$Id.png"
    portraitFraction=$PortraitFraction
    sourceSha256=(Get-FileHash "$pack/source/$Id.png").Hash.ToLowerInvariant()
    stageSha256=(Get-FileHash "$pack/stage/$Id.png").Hash.ToLowerInvariant()
    portraitSha256=(Get-FileHash "$pack/portraits/$Id.png").Hash.ToLowerInvariant()
}
$entry | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 "$pack/$Id.json"
Write-Output "Imported $Id (640x1024 stage, 512x512 portrait)"
