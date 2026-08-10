# Renders how the adaptive icon looks once a launcher masks it, so cropping can
# be checked without installing. Composites background + foreground, then cuts
# the central 72dp with a circle and a squircle (MIUI/Pixel style shapes).

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $root "app/src/main/res/mipmap-xxxhdpi"
$out = Join-Path $root "_preview"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$bg = [System.Drawing.Image]::FromFile((Join-Path $dir "ic_launcher_background.png"))
$fg = [System.Drawing.Image]::FromFile((Join-Path $dir "ic_launcher_foreground.png"))

$layer = $bg.Width
$mask = [int][math]::Round($layer * 72 / 108)
$inset = [int][math]::Round(($layer - $mask) / 2)

function Render([string]$shape, [string]$file) {
    $bmp = New-Object System.Drawing.Bitmap $mask, $mask, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    if ($shape -eq "circle") {
        $path.AddEllipse(0, 0, $mask, $mask)
    } else {
        $r = [int][math]::Round($mask * 0.42)
        $path.AddArc(0, 0, $r * 2, $r * 2, 180, 90)
        $path.AddArc($mask - $r * 2, 0, $r * 2, $r * 2, 270, 90)
        $path.AddArc($mask - $r * 2, $mask - $r * 2, $r * 2, $r * 2, 0, 90)
        $path.AddArc(0, $mask - $r * 2, $r * 2, $r * 2, 90, 90)
        $path.CloseFigure()
    }
    $g.SetClip($path)
    $g.DrawImage($bg, -$inset, -$inset, $layer, $layer)
    $g.DrawImage($fg, -$inset, -$inset, $layer, $layer)
    $path.Dispose()
    $g.Dispose()
    $bmp.Save((Join-Path $out $file), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Output "$file ($mask x $mask)"
}

Render "circle" "icon_masked_circle.png"
Render "squircle" "icon_masked_squircle.png"

$bg.Dispose()
$fg.Dispose()
