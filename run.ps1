# DHRASHTA-X: build, install and launch from VS Code (PowerShell terminal)
#   .\run.ps1                 -> build debug APK, start emulator if needed, install, launch, show logs
#   .\run.ps1 -NoBuild        -> install the existing APK and launch
#   .\run.ps1 -Avd Pixel_9a   -> use a specific emulator
param(
    [string]$Avd = "",
    [switch]$NoBuild,
    [switch]$NoLogs
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# --- Tools (Android Studio's bundled JDK + your Android SDK) -------------------
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not $env:JAVA_HOME -and (Test-Path $jbr)) { $env:JAVA_HOME = $jbr }
$env:ANDROID_HOME = $sdk
$adb = "$sdk\platform-tools\adb.exe"
$emulator = "$sdk\emulator\emulator.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb (install 'Android SDK Platform-Tools' from Android Studio > SDK Manager)" }

$pkg = "com.dhrashta.x"
$apk = "app\build\outputs\apk\debug\app-debug.apk"

# --- 1. Build -------------------------------------------------------------------
if (-not $NoBuild) {
    Write-Host "==> Building debug APK..." -ForegroundColor Cyan
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
}
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

# --- 2. Device / emulator ------------------------------------------------------
& $adb start-server | Out-Null
$devices = (& $adb devices) | Select-String "\tdevice$"
if (-not $devices) {
    if (-not $Avd) {
        $avds = & $emulator -list-avds | Where-Object { $_ -and $_ -notmatch "Pro" }
        if (-not $avds) { throw "No emulator found. Create one in Android Studio > Device Manager." }
        $Avd = ($avds | Select-Object -First 1)
    }
    Write-Host "==> Starting emulator $Avd ..." -ForegroundColor Cyan
    Start-Process -FilePath $emulator -ArgumentList "-avd", $Avd, "-netdelay", "none", "-netspeed", "full"
    & $adb wait-for-device
    Write-Host "    waiting for Android to finish booting..."
    do { Start-Sleep -Seconds 2; $boot = (& $adb shell getprop sys.boot_completed 2>$null) } while ($boot -notmatch "1")
}

# --- 3. Install + launch --------------------------------------------------------
Write-Host "==> Installing $apk ..." -ForegroundColor Cyan
& $adb install -r -g $apk
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

# Usage Access (bank-foreground sensor) can be granted over adb for testing.
& $adb shell appops set $pkg GET_USAGE_STATS allow 2>$null | Out-Null

Write-Host "==> Launching DHRASHTA-X ..." -ForegroundColor Cyan
& $adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null

# --- 4. Logs (Ctrl+C to stop) ----------------------------------------------------
if (-not $NoLogs) {
    Write-Host "==> Live logs (Ctrl+C to stop). Model self-test result appears as 'Network self-test'." -ForegroundColor Green
    & $adb logcat -c
    & $adb logcat -v time "DhrashtaMonitor:I" "DhrashtaOnnx:I" "DhrashtaNetModel:I" "DhrashtaVpn:I" "DhrashtaDns:I" "DhrashtaCanary:I" "AndroidRuntime:E" "*:S"
}
