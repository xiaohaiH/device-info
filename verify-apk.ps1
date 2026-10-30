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
if ($LASTEXITCODE -ne 0) { throw 'Cannot parse APK permissions.' }
$declaredPermissions = @($permissions | Where-Object { $_ -match '^\s*uses-permission' })
$expectedPermissions = @('android.permission.REBOOT', 'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.READ_EXTERNAL_STORAGE')
$permissionNames = @($declaredPermissions | ForEach-Object {
    if ($_ -match "name='([^']+)'") { $Matches[1] } else { throw 'Malformed APK permission declaration.' }
})
if ($permissionNames.Count -ne 3 -or (Compare-Object -CaseSensitive $expectedPermissions $permissionNames)) {
    throw 'APK must request exactly REBOOT, REQUEST_INSTALL_PACKAGES and capped READ_EXTERNAL_STORAGE.'
}
$manifest = & "$tools\aapt.exe" dump xmltree $Apk AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Cannot decode manifest.' }
if (($manifest -join "`n") -match 'android.intent.category.HOME|android.intent.category.DEFAULT') { throw 'Diagnostic tool must not register as a default home.' }
Write-Host 'PASS manifest: API 16+, target 35, exact reboot/install/legacy-read permissions, no native code/HOME role, release build'
$manifestText = $manifest -join "`n"
foreach ($entry in @(
    @('service', 'RebootAccessibilityService', 'android.permission.BIND_ACCESSIBILITY_SERVICE'),
    @('receiver', 'RebootAdminReceiver', 'android.permission.BIND_DEVICE_ADMIN')
)) {
    $pattern = '(?ms)^(?<indent>[ \t]*)E: ' + $entry[0] + '[^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)(?<children>.*?)(?=^\k<indent>E:|\z)'
    $namePattern = 'android:name\([^)]+\)="' + [regex]::Escape('com.gathin.deviceinfo.' + $entry[1]) + '"'
    $permissionPattern = 'android:permission\([^)]+\)="' + [regex]::Escape($entry[2]) + '"'
    $blocks = @([regex]::Matches($manifestText, $pattern) | Where-Object { $_.Groups['attrs'].Value -match $namePattern })
    if ($blocks.Count -ne 1 -or $blocks[0].Groups['attrs'].Value -notmatch $permissionPattern) {
        throw "Missing or unprotected component: $($entry[1])"
    }
    if ($entry[0] -eq 'service') { $accessibilityService = $blocks[0].Groups['children'].Value }
}
$metadata = @([regex]::Matches($accessibilityService,
    '(?m)^[ \t]*E: meta-data[^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)') | Where-Object {
        $_.Groups['attrs'].Value -match 'android:name\([^)]+\)="android\.accessibilityservice"'
    })
if ($metadata.Count -ne 1) { throw 'Missing or ambiguous accessibility metadata.' }
$resourceId = [regex]::Match($metadata[0].Groups['attrs'].Value, 'android:resource\([^)]+\)=@(0x[0-9a-fA-F]+)')
if (-not $resourceId.Success) { throw 'Accessibility metadata must reference an XML resource.' }
# Follow the actual service metadata, including every resource variant and optimized file path.
$resourceTable = & "$tools\aapt.exe" dump --values resources $Apk
if ($LASTEXITCODE -ne 0) { throw 'Cannot decode resource table.' }
$pathPattern = '(?m)^[ \t]+resource ' + [regex]::Escape($resourceId.Groups[1].Value) +
    ' [^\r\n]+:xml/[^\r\n]+:[^\r\n]*\r?\n[ \t]+\(string(?:8|16)?\) "([^"]+)"'
$resourcePaths = @([regex]::Matches(($resourceTable -join "`n"), $pathPattern) | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
if ($resourcePaths.Count -eq 0) { throw 'Accessibility configuration resource missing.' }
foreach ($resourcePath in $resourcePaths) {
    $accessibilityXml = & "$tools\aapt.exe" dump xmltree $Apk $resourcePath
    if ($LASTEXITCODE -ne 0) { throw 'Cannot decode accessibility configuration.' }
    $accessibilityText = $accessibilityXml -join "`n"
    if ($accessibilityText -notmatch 'android:canRetrieveWindowContent[^\r\n]*\(type 0x12\)0x0' -or
        $accessibilityText -match 'android:accessibilityEventTypes|android:canPerformGestures|android:accessibilityFlags') {
        throw 'Accessibility helper must not retrieve window content, collect events or request gestures/flags.'
    }
}
Write-Host 'PASS reboot helpers: protected service/admin receiver, no accessibility content/events/gestures'

$permissionBlocks = @([regex]::Matches($manifestText,
    '(?m)^[ \t]*E: uses-permission [^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)'))
$storage = @($permissionBlocks | Where-Object { $_.Groups['attrs'].Value -match 'android:name\([^)]+\)="android\.permission\.READ_EXTERNAL_STORAGE"' })
if ($permissionBlocks.Count -ne 3 -or $storage.Count -ne 1 -or
    $storage[0].Groups['attrs'].Value -notmatch 'android:maxSdkVersion[^\r\n]*\(type 0x10\)0x1c(?:\s|$)') {
    throw 'Legacy storage read permission must be capped at API28.'
}
foreach ($entry in @(@('activity', 'ApkInstallActivity'), @('provider', 'ApkFileProvider'))) {
    $pattern = '(?ms)^(?<indent>[ \t]*)E: ' + $entry[0] + '[^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)(?<children>.*?)(?=^\k<indent>E:|\z)'
    $namePattern = 'android:name\([^)]+\)="' + [regex]::Escape('com.gathin.deviceinfo.' + $entry[1]) + '"'
    $blocks = @([regex]::Matches($manifestText, $pattern) | Where-Object { $_.Groups['attrs'].Value -match $namePattern })
    if ($blocks.Count -ne 1 -or $blocks[0].Groups['attrs'].Value -notmatch 'android:exported[^\r\n]*\(type 0x12\)0x0(?:\s|$)') {
        throw "APK installation component must not be exported: $($entry[1])"
    }
    if ($entry[0] -eq 'provider') {
        $attrs = $blocks[0].Groups['attrs'].Value
        $children = $blocks[0].Groups['children'].Value
        if ($attrs -notmatch 'android:authorities\([^)]+\)="com\.gathin\.deviceinfo\.apkfiles"' -or
            $attrs -notmatch 'android:grantUriPermissions[^\r\n]*\(type 0x12\)0x0(?:\s|$)' -or
            [regex]::Matches($children, 'E: grant-uri-permission ').Count -ne 1 -or
            $children -notmatch 'android:pathPrefix\([^)]+\)="/apk/"' -or
            $children -match 'android:path\(|android:pathPattern\(|android:pathAdvancedPattern\(') {
            throw 'APK URI grants must be restricted to the private provider apk/ path.'
        }
    }
}
Write-Host 'PASS APK installation: private Activity/provider, scoped URI grants, storage capped at API28'

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
