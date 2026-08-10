param([string]$Layer = "SurfaceView[com.birddrop.birddropgame/com.birddrop.birddropgame.GameActivity](BLAST)")

$name = (adb shell dumpsys SurfaceFlinger --list) | Select-String -SimpleMatch $Layer | Select-Object -First 1
if (-not $name) { Write-Output "layer not found"; exit 1 }
$layerName = $name.ToString().Trim()
if ($layerName -match '^RequestedLayerState\{(.+)\}$') { $layerName = $Matches[1] }

$raw = adb shell dumpsys SurfaceFlinger --latency "'$layerName'"
$lines = $raw | Where-Object { $_ -match '^\d+\s+\d+\s+\d+' }
$stamps = @()
foreach ($l in $lines) {
    $parts = $l -split '\s+'
    $v = [double]$parts[1]
    if ($v -gt 0 -and $v -lt 9.2e18) { $stamps += $v }
}
if ($stamps.Count -lt 5) { Write-Output "not enough frames ($($stamps.Count))"; exit 1 }

$deltas = for ($i = 1; $i -lt $stamps.Count; $i++) { ($stamps[$i] - $stamps[$i - 1]) / 1e6 }
$deltas = $deltas | Where-Object { $_ -gt 0 -and $_ -lt 500 }
$avg = ($deltas | Measure-Object -Average).Average
$sorted = $deltas | Sort-Object
$p95 = $sorted[[int]([math]::Floor($sorted.Count * 0.95))]
$worst = ($deltas | Measure-Object -Maximum).Maximum

"frames: {0}" -f $deltas.Count
"avg frame: {0:N2} ms  ({1:N1} fps)" -f $avg, (1000 / $avg)
"p95 frame: {0:N2} ms" -f $p95
"worst    : {0:N2} ms" -f $worst
"over 20ms: {0:P0}" -f (($deltas | Where-Object { $_ -gt 20 }).Count / $deltas.Count)
