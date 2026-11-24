# MolidVPN — build + install
#   .\build.ps1                 build, install, launch
#   .\build.ps1 -Shot home.png   also grab a screenshot into shots/
#   .\build.ps1 -Reset           restore the default home_layout.json first
param(
    [string]$Shot = "",
    [switch]$Reset
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$assets = Join-Path $root "app\src\main\assets"
$gradle = "C:\Users\MOLLAZEHI\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
$env:GRADLE_USER_HOME = "D:\gradle-home"

if ($Reset) {
    Copy-Item (Join-Path $assets "home_layout.default.json") (Join-Path $assets "home_layout.json") -Force
    Write-Host "home_layout.json reset to defaults" -ForegroundColor Cyan
}

& $gradle -p $root :app:assembleDebug --console=plain --offline
if ($LASTEXITCODE -ne 0) { Write-Host "BUILD FAILED" -ForegroundColor Red; exit 1 }

& $adb install -r (Join-Path $root "app\build\outputs\apk\debug\app-debug.apk")
if ($LASTEXITCODE -ne 0) { Write-Host "INSTALL FAILED" -ForegroundColor Red; exit 1 }

& $adb shell am force-stop com.molidvpn
& $adb shell am start -n com.molidvpn/.MainActivity | Out-Null

if ($Shot -ne "") {
    # wait until the sheet is actually on screen, not the splash icon
    for ($i = 0; $i -lt 25; $i++) {
        Start-Sleep -Milliseconds 900
        $focus = & $adb shell dumpsys window 2>$null | Select-String "mCurrentFocus"
        if ("$focus" -match "com.molidvpn") { break }
    }
    Start-Sleep -Seconds 2
    & $adb shell screencap -p /sdcard/shot.png
    & $adb pull /sdcard/shot.png (Join-Path $root "shots\$Shot") | Out-Null
    Write-Host "screenshot -> shots\$Shot" -ForegroundColor Cyan
}
Write-Host "OK" -ForegroundColor Green
