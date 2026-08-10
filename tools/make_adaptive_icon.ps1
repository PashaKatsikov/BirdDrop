# Builds the adaptive launcher icon from art/icon_source.png.
#
# Adaptive layers are 108dp squares, but launchers only ever show the central
# 72dp after masking. Drawing the art at ART_DP (slightly wider than the mask)
# keeps it edge-to-edge inside every mask shape while losing only a few dp at
# the rim. The background layer repeats the same art full-bleed so parallax and
# oversized masks reveal more scenery instead of empty padding.

param(
    [string]$Source = "art/icon_source.png",
    [int]$ArtDp = 80
)

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$res = Join-Path $root "app/src/main/res"
$src = [System.Drawing.Image]::FromFile((Resolve-Path (Join-Path $root $Source)))

$densities = @(
    @{ Folder = "mipmap-mdpi"; Scale = 1 },
    @{ Folder = "mipmap-hdpi"; Scale = 1.5 },
    @{ Folder = "mipmap-xhdpi"; Scale = 2 },
    @{ Folder = "mipmap-xxhdpi"; Scale = 3 },
    @{ Folder = "mipmap-xxxhdpi"; Scale = 4 }
)

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    @{ Bitmap = $bmp; Graphics = $g }
}

function Save-Canvas($canvas, [string]$path) {
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Bitmap.Dispose()
}

foreach ($d in $densities) {
    $dir = Join-Path $res $d.Folder
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    $layer = [int][math]::Round(108 * $d.Scale)
    $art = [int][math]::Round($ArtDp * $d.Scale)
    $offset = [int][math]::Round(($layer - $art) / 2)

    $fg = New-Canvas $layer
    $fg.Graphics.DrawImage($src, $offset, $offset, $art, $art)
    Save-Canvas $fg (Join-Path $dir "ic_launcher_foreground.png")

    $bg = New-Canvas $layer
    $bg.Graphics.DrawImage($src, 0, 0, $layer, $layer)
    Save-Canvas $bg (Join-Path $dir "ic_launcher_background.png")

    # Legacy icons for launchers that ignore adaptive drawables.
    $legacy = [int][math]::Round(48 * $d.Scale)
    $square = New-Canvas $legacy
    $square.Graphics.DrawImage($src, 0, 0, $legacy, $legacy)
    Save-Canvas $square (Join-Path $dir "ic_launcher.png")

    $round = New-Canvas $legacy
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $legacy, $legacy)
    $round.Graphics.SetClip($path)
    $round.Graphics.DrawImage($src, 0, 0, $legacy, $legacy)
    $path.Dispose()
    Save-Canvas $round (Join-Path $dir "ic_launcher_round.png")

    Write-Output ("{0}: layer {1}px, art {2}px, legacy {3}px" -f $d.Folder, $layer, $art, $legacy)
}

$src.Dispose()
Write-Output "adaptive icon layers done"
