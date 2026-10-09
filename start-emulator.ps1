# Step 1: start an Android emulator in its own window (outside Android Studio)
#   .\start-emulator.ps1               -> starts Pixel_9a (the one that works)
#   .\start-emulator.ps1 -Avd Pixel_8  -> starts another emulator
param([string]$Avd = "Pixel_9a")
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$emulator = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"

Write-Host "Available emulators:" -ForegroundColor Cyan
$avds = & $emulator -list-avds | Where-Object { $_ }
$avds | ForEach-Object { Write-Host "  $_" }
if ($Avd -notin $avds) { $Avd = ($avds | Where-Object { $_ -match "9a" } | Select-Object -First 1) }
if (-not $Avd) { throw "No emulator found. Create one in Android Studio > Device Manager." }

Write-Host "Starting $Avd ..." -ForegroundColor Cyan
Start-Process -FilePath $emulator -ArgumentList "-avd", $Avd, "-netdelay", "none", "-netspeed", "full"

Write-Host "Waiting for Android to finish booting (first boot can take 1-3 minutes)..."
$serial = $null
do {
    Start-Sleep -Seconds 3
    $serial = (& $adb devices) | ForEach-Object { if ($_ -match "^(emulator-\d+)\s+device$") { $Matches[1] } } | Select-Object -Last 1
    $boot = if ($serial) { & $adb -s $serial shell getprop sys.boot_completed 2>$null } else { "" }
} while ($boot -notmatch "1")
Write-Host "Emulator ready ($serial). Keep this window open. Now run: .\install-app.ps1" -ForegroundColor Green
