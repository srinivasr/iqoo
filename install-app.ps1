# Step 2: install DHRASHTA-X into the running emulator and open it
#   .\install-app.ps1                          -> install the APK that is already built
#   .\install-app.ps1 -Build                   -> build a fresh APK first, then install
#   .\install-app.ps1 -Serial emulator-5556    -> pick the device when more than one is connected
param([switch]$Build, [string]$Serial = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not $env:JAVA_HOME -and (Test-Path $jbr)) { $env:JAVA_HOME = $jbr }
$env:ANDROID_HOME = $sdk
$pkg = "com.dhrashta.x"
$apk = "app\build\outputs\apk\debug\app-debug.apk"

if ($Build) {
    Write-Host "Building APK..." -ForegroundColor Cyan
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}
if (-not (Test-Path $apk)) { throw "APK not found. Run: .\install-app.ps1 -Build" }

Write-Host "Connected devices:" -ForegroundColor Cyan
& $adb devices -l
# Only devices that are fully online ("device" state); offline/unauthorized ones are skipped.
$ready = @((& $adb devices) | ForEach-Object { if ($_ -match "^(\S+)\s+device$") { $Matches[1] } })
if (-not $Serial) {
    if ($ready.Count -eq 0) { throw "No emulator is online yet. Start one with .\start-emulator.ps1 and wait for 'Emulator ready'." }
    # Prefer an emulator; otherwise take the first device.
    $Serial = ($ready | Where-Object { $_ -like "emulator-*" } | Select-Object -Last 1)
    if (-not $Serial) { $Serial = $ready[0] }
}
Write-Host "Using device: $Serial" -ForegroundColor Cyan

Write-Host "Installing $apk ..." -ForegroundColor Cyan
& $adb -s $Serial install -r -g $apk
if ($LASTEXITCODE -ne 0) { throw "Install failed on $Serial" }
& $adb -s $Serial shell appops set $pkg GET_USAGE_STATS allow 2>$null | Out-Null

Write-Host "Opening DHRASHTA-X ..." -ForegroundColor Cyan
& $adb -s $Serial shell am start -n "$pkg/.ui.MainActivity" | Out-Null
Write-Host "Done. Logs:  & `"$adb`" -s $Serial logcat -v time DhrashtaMonitor:I DhrashtaOnnx:I DhrashtaNetModel:I DhrashtaVpn:I AndroidRuntime:E *:S" -ForegroundColor Green
