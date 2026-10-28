param([switch]$Offline)
$ErrorActionPreference = 'Stop'
# Tests also locate a full JDK in the current process if JAVA_HOME is not configured.
& (Join-Path $PSScriptRoot 'test.ps1')
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-36\android.jar")) {
    throw 'Install Android SDK platform 36 and build-tools 36.0.0, or set ANDROID_HOME.'
}
$argsForGradle = @('--no-daemon', '--console=plain', 'assembleRelease', 'lintRelease')
if ($Offline) { $argsForGradle = @('--offline') + $argsForGradle }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat @argsForGradle
    if ($LASTEXITCODE -ne 0) { throw 'Android build/lint failed.' }
    $apks = @(Get-ChildItem 'build\outputs\apk\release\*.apk' | Where-Object { $_.Name -notlike '*unsigned*' })
    if ($apks.Count -ne 1) { throw 'Expected exactly one signed release APK.' }
    & .\verify-apk.ps1 -Apk $apks[0].FullName
    New-Item -ItemType Directory -Force 'dist' | Out-Null
    Copy-Item $apks[0].FullName 'dist\device-info.apk' -Force
    Write-Host "Verified APK: $PSScriptRoot\dist\device-info.apk"
} finally {
    Pop-Location
}
# Intentionally no adb install, default-home changes, or device commands.
