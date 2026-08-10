$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

# Mirrors GameView/GameWorld layout math so the composition can be checked
# without installing the app.
$res = "C:\Dev\flutter_projects\BirdDrop\app\src\main\res\drawable"
$outDir = "C:\Dev\flutter_projects\BirdDrop\_preview"
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$W = 2340.0
$H = 1080.0
$groundY = 0.86 * $H
$cannonX = 0.10 * $W
$cannonY = $groundY - 0.07 * $H
$gravity = 2.2 * $W

function Load($name) { return [System.Drawing.Bitmap]::FromFile("$res\$name.png") }

function Draw-Scene($levelName, $background, $horizon, $launchSpeed, $angleDeg, $buildings, $foes) {
    $canvas = New-Object System.Drawing.Bitmap -ArgumentList ([int]$W), ([int]$H)
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic

    $bg = Load $background
    $scale = [Math]::Max($W / $bg.Width, ($groundY / $horizon) / $bg.Height)
    $drawnW = $bg.Width * $scale
    $drawnH = $bg.Height * $scale
    $left = -($drawnW - $W) * 0.25
    $top = $groundY - $drawnH * $horizon
    $g.DrawImage($bg, [float]$left, [float]$top, [float]$drawnW, [float]$drawnH)
    $bg.Dispose()

    foreach ($b in $buildings) {
        $sprite = Load $b.Sprite
        $bh = $b.Height * $H
        $bw = $bh * $sprite.Width / $sprite.Height
        $bottom = $groundY - $b.Base * $H
        $g.DrawImage($sprite, [float]($b.X * $W - $bw / 2), [float]($bottom - $bh), [float]$bw, [float]$bh)
        $sprite.Dispose()
    }

    foreach ($f in $foes) {
        $sprite = Load $f.Sprite
        $size = $f.Size * $H
        $halfW = $size / 2 * $sprite.Width / $sprite.Height
        $cy = $groundY - $f.Base * $H - $size / 2
        $g.DrawImage($sprite, [float]($f.X * $W - $halfW), [float]($cy - $size / 2), [float]($halfW * 2), [float]$size)
        $sprite.Dispose()
    }

    # Trajectory of a bare launch, drawn as the in-game dotted preview.
    $angle = $angleDeg * [Math]::PI / 180.0
    $speed = $launchSpeed * $W
    $x = $cannonX + 0.06 * $H
    $y = $cannonY - 0.04 * $H
    $vx = [Math]::Cos($angle) * $speed
    $vy = -[Math]::Sin($angle) * $speed
    $dot = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(210, 255, 255, 255))
    $dt = 1.0 / 120.0
    $step = 0
    while ($y -lt $groundY -and $x -lt $W) {
        $vy += $gravity * $dt
        $x += $vx * $dt
        $y += $vy * $dt
        if ($step % 6 -eq 0) {
            $g.FillEllipse($dot, [float]($x - 5), [float]($y - 5), 10, 10)
        }
        $step++
    }
    $dot.Dispose()

    $cannon = Load "cannon"
    $size = 0.30 * $H
    $halfW = $size / 2 * $cannon.Width / $cannon.Height
    $state = $g.Save()
    $g.TranslateTransform([float]$cannonX, [float]$cannonY)
    $g.RotateTransform([float](-$angleDeg + 26))
    $g.TranslateTransform([float](-$cannonX), [float](-$cannonY))
    $g.DrawImage($cannon, [float]($cannonX - $halfW), [float]($cannonY - $size / 2), [float]($halfW * 2), [float]$size)
    $g.Restore($state)
    $cannon.Dispose()

    $bird = Load "bird_red"
    $bs = 0.11 * $H
    $bw = $bs * $bird.Width / $bird.Height
    $g.DrawImage($bird, [float]($cannonX - $bw / 2), [float]($cannonY - 0.15 * $H), [float]$bw, [float]$bs)
    $bird.Dispose()

    # Ground line marker for verification only.
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(160, 255, 0, 0)), 3
    $g.DrawLine($pen, 0, [float]$groundY, [float]$W, [float]$groundY)
    $pen.Dispose()

    $g.Dispose()
    $canvas.Save("$outDir\$levelName.png", [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Dispose()
    Write-Host "saved $levelName.png"
}

Draw-Scene "level01" "bg_green_valley" 0.80 1.05 48 @(
    @{ X = 0.70; Height = 0.20; Base = 0.0; Sprite = "wood_2" }
) @(
    @{ X = 0.78; Base = 0.0; Size = 0.11; Sprite = "foe_grunt" }
)

Draw-Scene "level05" "bg_foundry" 0.80 0.97 48 @(
    @{ X = 0.66; Height = 0.30; Base = 0.0; Sprite = "stone_1" },
    @{ X = 0.80; Height = 0.22; Base = 0.0; Sprite = "stone_3" },
    @{ X = 0.91; Height = 0.24; Base = 0.0; Sprite = "wood_1" }
) @(
    @{ X = 0.66; Base = 0.30; Size = 0.12; Sprite = "foe_armored" },
    @{ X = 0.80; Base = 0.22; Size = 0.11; Sprite = "foe_grunt" },
    @{ X = 0.91; Base = 0.24; Size = 0.11; Sprite = "foe_builder" }
)

Draw-Scene "level08" "bg_sky_fortress" 0.76 0.98 48 @(
    @{ X = 0.62; Height = 0.30; Base = 0.0; Sprite = "metal_2" },
    @{ X = 0.78; Height = 0.24; Base = 0.0; Sprite = "stone_4" },
    @{ X = 0.90; Height = 0.26; Base = 0.0; Sprite = "metal_3" }
) @(
    @{ X = 0.62; Base = 0.30; Size = 0.12; Sprite = "foe_armored" },
    @{ X = 0.78; Base = 0.24; Size = 0.12; Sprite = "foe_engineer" },
    @{ X = 0.90; Base = 0.26; Size = 0.12; Sprite = "foe_armored" }
)

Draw-Scene "level12" "bg_volcano" 0.76 1.02 48 @(
    @{ X = 0.56; Height = 0.26; Base = 0.0; Sprite = "metal_1" },
    @{ X = 0.68; Height = 0.34; Base = 0.0; Sprite = "metal_2" },
    @{ X = 0.80; Height = 0.28; Base = 0.0; Sprite = "metal_4" },
    @{ X = 0.92; Height = 0.30; Base = 0.0; Sprite = "stone_4" }
) @(
    @{ X = 0.56; Base = 0.26; Size = 0.12; Sprite = "foe_armored" },
    @{ X = 0.68; Base = 0.34; Size = 0.12; Sprite = "foe_engineer" },
    @{ X = 0.80; Base = 0.28; Size = 0.12; Sprite = "foe_armored" },
    @{ X = 0.92; Base = 0.30; Size = 0.12; Sprite = "foe_engineer" }
)
