param([string]$Apk = (Join-Path $PSScriptRoot 'dist\device-info.apk'), [switch]$RequireDistributionSignature)
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
if (-not $env:JAVA_HOME) { throw '请将 JAVA_HOME 指向 JDK 目录。' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

$badging = & "$tools\aapt.exe" dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw '无法解析 APK 清单。' }
$text = $badging -join "`n"
foreach ($expected in @("package: name='com.gathin.deviceinfo'", "sdkVersion:'16'", "targetSdkVersion:'35'", "launchable-activity: name='com.gathin.deviceinfo.MainActivity'")) {
    if (-not $text.Contains($expected)) { throw "APK 元数据不符 $expected" }
}
if ($text -match 'application-debuggable|native-code:') { throw 'APK 必须为非调试版本，且无原生 ABI 依赖。' }
$permissions = & "$tools\aapt.exe" dump permissions $Apk
if ($LASTEXITCODE -ne 0) { throw '无法解析 APK 权限。' }
$declaredPermissions = @($permissions | Where-Object { $_ -match '^\s*uses-permission' })
$expectedPermissions = @('android.permission.REBOOT', 'android.permission.REQUEST_INSTALL_PACKAGES', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.SYSTEM_ALERT_WINDOW')
$permissionNames = @($declaredPermissions | ForEach-Object {
    if ($_ -match "name='([^']+)'") { $Matches[1] } else { throw 'APK 权限声明格式有误。' }
})
if ($permissionNames.Count -ne 4 -or (Compare-Object -CaseSensitive $expectedPermissions $permissionNames)) {
    throw 'APK 权限声明不符合预期。'
}
$manifest = & "$tools\aapt.exe" dump xmltree $Apk AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw '无法解码 APK 清单。' }
if (($manifest -join "`n") -match 'android.intent.category.HOME|android.intent.category.DEFAULT') { throw '诊断工具不可注册为默认桌面。' }
Write-Host 'PASS manifest: API 16+, target 35, exact reboot/install/legacy-read/legacy-overlay permissions, no native code/HOME role, release build'
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
        throw "组件缺失或未受权限保护 $($entry[1])"
    }
    if ($entry[0] -eq 'service') { $accessibilityService = $blocks[0].Groups['children'].Value }
}
$metadata = @([regex]::Matches($accessibilityService,
    '(?m)^[ \t]*E: meta-data[^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)') | Where-Object {
        $_.Groups['attrs'].Value -match 'android:name\([^)]+\)="android\.accessibilityservice"'
    })
if ($metadata.Count -ne 1) { throw '无障碍服务元数据缺失或存在多个匹配项。' }
$resourceId = [regex]::Match($metadata[0].Groups['attrs'].Value, 'android:resource\([^)]+\)=@(0x[0-9a-fA-F]+)')
if (-not $resourceId.Success) { throw '无障碍服务元数据必须引用 XML 资源。' }
# Follow the actual service metadata, including every resource variant and optimized file path.
$resourceTable = & "$tools\aapt.exe" dump --values resources $Apk
if ($LASTEXITCODE -ne 0) { throw '无法解码 APK 资源表。' }
$pathPattern = '(?m)^[ \t]+resource ' + [regex]::Escape($resourceId.Groups[1].Value) +
    ' [^\r\n]+:xml/[^\r\n]+:[^\r\n]*\r?\n[ \t]+\(string(?:8|16)?\) "([^"]+)"'
$resourcePaths = @([regex]::Matches(($resourceTable -join "`n"), $pathPattern) | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
if ($resourcePaths.Count -eq 0) { throw '缺少无障碍服务配置资源。' }
foreach ($resourcePath in $resourcePaths) {
    $accessibilityXml = & "$tools\aapt.exe" dump xmltree $Apk $resourcePath
    if ($LASTEXITCODE -ne 0) { throw '无法解码无障碍服务配置。' }
    $accessibilityText = $accessibilityXml -join "`n"
    if ($accessibilityText -notmatch 'android:canRetrieveWindowContent[^\r\n]*\(type 0x12\)0x0' -or
        $accessibilityText -match 'android:accessibilityEventTypes|android:canPerformGestures|android:accessibilityFlags') {
        throw '无障碍辅助不得读取窗口内容、采集事件、请求手势或设置额外标志。'
    }
}
Write-Host 'PASS reboot helpers: protected service/admin receiver, no accessibility content/events/gestures'

$permissionBlocks = @([regex]::Matches($manifestText,
    '(?m)^[ \t]*E: uses-permission [^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)'))
$storage = @($permissionBlocks | Where-Object { $_.Groups['attrs'].Value -match 'android:name\([^)]+\)="android\.permission\.READ_EXTERNAL_STORAGE"' })
if ($permissionBlocks.Count -ne 4 -or $storage.Count -ne 1 -or
    $storage[0].Groups['attrs'].Value -notmatch 'android:maxSdkVersion[^\r\n]*\(type 0x10\)0x1c(?:\s|$)') {
    throw '旧版存储读取权限的适用上限必须为 API 28。'
}
$overlayPermission = @($permissionBlocks | Where-Object { $_.Groups['attrs'].Value -match 'android:name\([^)]+\)="android\.permission\.SYSTEM_ALERT_WINDOW"' })
if ($overlayPermission.Count -ne 1 -or
    $overlayPermission[0].Groups['attrs'].Value -notmatch 'android:maxSdkVersion[^\r\n]*\(type 0x10\)0x15(?:\s|$)') {
    throw '悬浮窗口权限的适用上限必须为 API 21。'
}
foreach ($entry in @(@('activity', 'ApkInstallActivity'), @('provider', 'ApkFileProvider'))) {
    $pattern = '(?ms)^(?<indent>[ \t]*)E: ' + $entry[0] + '[^\r\n]*\r?\n(?<attrs>(?:[ \t]*A: [^\r\n]*\r?\n)*)(?<children>.*?)(?=^\k<indent>E:|\z)'
    $namePattern = 'android:name\([^)]+\)="' + [regex]::Escape('com.gathin.deviceinfo.' + $entry[1]) + '"'
    $blocks = @([regex]::Matches($manifestText, $pattern) | Where-Object { $_.Groups['attrs'].Value -match $namePattern })
    if ($blocks.Count -ne 1 -or $blocks[0].Groups['attrs'].Value -notmatch 'android:exported[^\r\n]*\(type 0x12\)0x0(?:\s|$)') {
        throw "APK 安装组件不可导出 $($entry[1])"
    }
    if ($entry[0] -eq 'provider') {
        $attrs = $blocks[0].Groups['attrs'].Value
        $children = $blocks[0].Groups['children'].Value
        if ($attrs -notmatch 'android:authorities\([^)]+\)="com\.gathin\.deviceinfo\.apkfiles"' -or
            $attrs -notmatch 'android:grantUriPermissions[^\r\n]*\(type 0x12\)0x0(?:\s|$)' -or
            [regex]::Matches($children, 'E: grant-uri-permission ').Count -ne 1 -or
            $children -notmatch 'android:pathPrefix\([^)]+\)="/apk/"' -or
            $children -match 'android:path\(|android:pathPattern\(|android:pathAdvancedPattern\(') {
            throw 'APK URI 授权必须限定在私有提供程序的 apk/ 路径。'
        }
    }
}
Write-Host 'PASS APK installation: private Activity/provider, scoped URI grants, storage capped at API28'

$signer = Join-Path $tools 'lib\apksigner.jar'
$signature = & $java -jar $signer verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK 签名无效。' }
$signature | Write-Host
if (($signature -join "`n") -notmatch 'Verified using v1 .*: true' -or ($signature -join "`n") -notmatch 'Verified using v2 .*: true') {
    throw 'APK 必须同时具有有效的 v1 和 v2 签名。'
}
if ($RequireDistributionSignature -and ($signature -join "`n") -notmatch
        'certificate SHA-256 digest: 5bba546b95ada03af88e26856b059ec131df5c3b6d13bfba34ae3762634a17f2(?:\s|$)') {
    throw 'APK 签名与 device-info 专用签名证书不一致。'
}
foreach ($api in @(16,17,19,21,23,24,28,30,34,35,36)) {
    & $java -jar $signer verify --min-sdk-version $api --max-sdk-version $api $Apk
    if ($LASTEXITCODE -ne 0) { throw "API $api 的签名验证失败。" }
    Write-Host "PASS signature verification for API $api"
}
& "$tools\zipalign.exe" -c 4 $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK 文件对齐检查失败。' }
$sha256 = [Security.Cryptography.SHA256]::Create()
$apkStream = [IO.File]::OpenRead($Apk)
try {
    [pscustomobject]@{
        Algorithm = 'SHA256'
        Hash = [BitConverter]::ToString($sha256.ComputeHash($apkStream)).Replace('-', '')
        Path = $Apk
    } | Format-List
} finally {
    $apkStream.Dispose()
    $sha256.Dispose()
}
