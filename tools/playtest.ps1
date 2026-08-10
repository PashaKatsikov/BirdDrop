# Drives the app from the launcher screen into level 1, fires a shot and
# collects the in-game frame log so runs can be compared before/after tuning.
param([int]$Level = 1, [switch]$NoLaunchShot)

adb shell am force-stop com.birddrop.birddropgame | Out-Null
adb logcat -c
adb shell am start -n com.birddrop.birddropgame/.LoadingActivity | Out-Null
Start-Sleep -Seconds 7

adb shell input tap 1089 782 | Out-Null   # PLAY
Start-Sleep -Seconds 2

$col = ($Level - 1) % 6
$row = [math]::Floor(($Level - 1) / 6)
$x = 234 + $col * 342
$y = 298 + $row * 250
adb shell input tap $x $y | Out-Null      # level tile
Start-Sleep -Seconds 3

adb shell input tap 900 500 | Out-Null    # drop a device in the zone
Start-Sleep -Seconds 1

if (-not $NoLaunchShot) {
    adb shell input tap 1968 891 | Out-Null   # LAUNCH
    Start-Sleep -Seconds 8
}

adb logcat -d -s BirdDropFps
