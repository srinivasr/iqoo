# Step 1: start an Android emulator in its own window, tuned to stay responsive
#   .\start-emulator.ps1                  -> starts Pixel_9a with performance flags
#   .\start-emulator.ps1 -Avd Pixel_8     -> another emulator
#   .\start-emulator.ps1 -Cold            -> cold boot (use if it hangs on the old snapshot)
#   .\start-emulator.ps1 -Gpu swiftshader_indirect   -> try if the screen freezes/flickers with -gpu host
param(
    [string]$Avd = "Pixel_9a",
    [switch]$Cold,
    [string]$Gpu = "host",
    [int]$MemoryMB = 3072,
    [int]$Cores = 4
)
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$emulator = "$sdk\emulator\emulator.exe"
$adb = "$sdk\platform-tools\adb.exe"

# --- Things that make the emulator crawl on Windows -------------------------------
$heavy = @("VirtualBoxVM", "VBoxHeadless", "Docker Desktop", "com.docker.backend", "vmmem")
$running = Get-Process -ErrorAction SilentlyContinue | Where-Object { $heavy -contains $_.ProcessName } |
    Select-Object -ExpandProperty ProcessName -Unique
if ($running) {
    Write-Host "WARNING: these are running and fight the emulator for CPU/RAM/virtualization:" -ForegroundColor Yellow
    $running | ForEach-Object { Write-Host "   $_" -ForegroundColor Yellow }
    Write-Host "   Close the Kali VirtualBox VM and Docker Desktop for a smooth emulator." -ForegroundColor Yellow
}
$freeGB = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 1)
Write-Host "Free RAM: $freeGB GB" -ForegroundColor Cyan
if ($freeGB -lt 4) { Write-Host "   Low RAM: close Chrome tabs / Android Studio, or use -MemoryMB 2048" -ForegroundColor Yellow }

Write-Host "Hardware acceleration:" -ForegroundColor Cyan
& $emulator -accel-check | Select-Object -Last 2 | ForEach-Object { Write-Host "   $_" }

# --- Start ----------------------------------------------------------------------------
$avds = & $emulator -list-avds | Where-Object { $_ }
Write-Host "Available emulators: $($avds -join ', ')" -ForegroundColor Cyan
if ($Avd -notin $avds) { $Avd = ($avds | Where-Object { $_ -match "9a" } | Select-Object -First 1) }
if (-not $Avd) { throw "No emulator found. Create one in Android Studio > Device Manager." }

# Kill emulators that are already running so only one competes for resources.
(& $adb devices) | ForEach-Object { if ($_ -match "^(emulator-\d+)\s") { & $adb -s $Matches[1] emu kill | Out-Null } }
Start-Sleep -Seconds 2

$emuArgs = @("-avd", $Avd, "-gpu", $Gpu, "-memory", $MemoryMB, "-cores", $Cores,
          "-no-boot-anim", "-no-audio", "-netdelay", "none", "-netspeed", "full")
if ($Cold) { $emuArgs += "-no-snapshot-load" }
Write-Host "Starting $Avd  ($($emuArgs -join ' '))" -ForegroundColor Cyan
Start-Process -FilePath $emulator -ArgumentList $emuArgs

Write-Host "Waiting for Android to finish booting (first/cold boot can take 1-3 minutes)..."
$serial = $null
do {
    Start-Sleep -Seconds 3
    $serial = (& $adb devices) | ForEach-Object { if ($_ -match "^(emulator-\d+)\s+device$") { $Matches[1] } } | Select-Object -Last 1
    $boot = if ($serial) { & $adb -s $serial shell getprop sys.boot_completed 2>$null } else { "" }
} while ($boot -notmatch "1")

# Turn off UI animations: the emulator feels much faster.
& $adb -s $serial shell settings put global window_animation_scale 0
& $adb -s $serial shell settings put global transition_animation_scale 0
& $adb -s $serial shell settings put global animator_duration_scale 0

Write-Host "Emulator ready ($serial). Keep this window open. Now run: .\install-app.ps1" -ForegroundColor Green
