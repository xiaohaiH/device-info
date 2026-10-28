param([string]$Apk = (Join-Path $PSScriptRoot 'dist\device-info.apk'))
$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $OutputEncoding
$Apk = (Resolve-Path $Apk).Path
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$tools = Join-Path $sdk 'build-tools\36.0.0'
if (-not $env:JAVA_HOME) {
    $env:JAVA_HOME = @(
        "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android\Android Studio\jbr"
    ) | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$badging = & "$tools\aapt.exe" dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'Cannot parse APK manifest.' }
$text = $badging -join "`n"
foreach ($expected in @("package: name='com.gathin.deviceinfo'", "sdkVersion:'16'", "targetSdkVersion:'35'", "launchable-activity: name='com.gathin.deviceinfo.MainActivity'")) {
    if (-not $text.Contains($expected)) { throw "APK metadata mismatch: $expected" }
}
if ($text -match 'application-debuggable|native-code:') { throw 'APK must be non-debuggable and have no native ABI dependency.' }
$permissions = & "$tools\aapt.exe" dump permissions $Apk
if ($LASTEXITCODE -ne 0 -or ($permissions -join "`n") -match 'uses-permission:') { throw 'Unexpected permissions or malformed APK.' }
$manifest = & "$tools\aapt.exe" dump xmltree $Apk AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Cannot decode manifest.' }
if (($manifest -join "`n") -match 'android.intent.category.HOME|android.intent.category.DEFAULT') { throw 'Diagnostic tool must not register as a default home.' }
Write-Host 'PASS manifest: API 16+, target 35, no permissions/native code/HOME role, release build'

$signer = Join-Path $tools 'lib\apksigner.jar'
$signature = & $java -jar $signer verify --verbose $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature is invalid.' }
$signature | Write-Host
if (($signature -join "`n") -notmatch 'Verified using v1 .*: true' -or ($signature -join "`n") -notmatch 'Verified using v2 .*: true') {
    throw 'Both v1 and v2 signatures are required.'
}
foreach ($api in @(16,17,19,21,23,24,28,30,34,35,36)) {
    & $java -jar $signer verify --min-sdk-version $api --max-sdk-version $api $Apk
    if ($LASTEXITCODE -ne 0) { throw "Signature verification failed for API $api" }
    Write-Host "PASS signature verification for API $api"
}
& "$tools\zipalign.exe" -c 4 $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
Get-FileHash -Algorithm SHA256 $Apk | Format-List
