# Rasterises a monochrome notification vector so the glyph can be eyeballed
# before it ships. Only handles the M/Q/Z subset the notification icons use.
param(
    [string]$Vector = "$PSScriptRoot\..\app\src\main\res\drawable\ic_notif_ember.xml",
    [string]$Out    = "$PSScriptRoot\..\build\notif_preview.png"
)

Add-Type -AssemblyName System.Drawing

$pathData = ([regex]::Match((Get-Content $Vector -Raw), 'pathData="([^"]+)"')).Groups[1].Value
if (-not $pathData) { throw "no pathData in $Vector" }

$tokens = [regex]::Matches($pathData, '[MQZmqz]|-?\d+(?:\.\d+)?') | ForEach-Object { $_.Value }

$gp = New-Object System.Drawing.Drawing2D.GraphicsPath
$i = 0; $cur = $null; $start = $null
while ($i -lt $tokens.Count) {
    switch ($tokens[$i]) {
        'M' { $cur = [System.Drawing.PointF]::new([single]$tokens[$i+1], [single]$tokens[$i+2])
              $start = $cur; $i += 3 }
        'Q' { $cx = [single]$tokens[$i+1]; $cy = [single]$tokens[$i+2]
              $ex = [single]$tokens[$i+3]; $ey = [single]$tokens[$i+4]
              # quadratic -> cubic control points
              $c1 = [System.Drawing.PointF]::new($cur.X + 2/3*($cx-$cur.X), $cur.Y + 2/3*($cy-$cur.Y))
              $c2 = [System.Drawing.PointF]::new($ex   + 2/3*($cx-$ex),    $ey   + 2/3*($cy-$ey))
              $end = [System.Drawing.PointF]::new($ex, $ey)
              $gp.AddBezier($cur, $c1, $c2, $end); $cur = $end; $i += 5 }
        'Z' { $gp.CloseFigure(); $cur = $start; $i += 1 }
        default { throw "unsupported path command '$($tokens[$i])'" }
    }
}

# Three sizes: 192 to judge the shape, 48 and 24 to judge legibility in the bar.
$sizes = 192, 48, 24
$pad = 8
$w = [int](($sizes | Measure-Object -Sum).Sum + $pad * ($sizes.Count + 1))
$h = [int](192 + $pad * 2)

$bmp = New-Object System.Drawing.Bitmap($w, $h)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = 'AntiAlias'
$g.Clear([System.Drawing.Color]::FromArgb(255, 32, 34, 38))

$x = $pad
foreach ($s in $sizes) {
    $st = $g.Save()
    $g.TranslateTransform($x, $pad + (192 - $s) / 2)
    $g.ScaleTransform($s / 24.0, $s / 24.0)
    $g.FillPath([System.Drawing.Brushes]::White, $gp)
    $g.Restore($st)
    $x += $s + $pad
}

New-Item -ItemType Directory -Force -Path (Split-Path $Out) | Out-Null
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()
Write-Host "wrote $Out"
