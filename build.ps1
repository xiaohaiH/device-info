param([switch]$Offline, [switch]$ShowPausePrompt)
if ($ShowPausePrompt) {
    Write-Host '按任意键关闭窗口…'
    return
}
$ErrorActionPreference = 'Stop'
$localConfig = Join-Path $PSScriptRoot 'config.local.jsonc'
$hasLocalConfig = Test-Path -LiteralPath $localConfig -PathType Leaf
if ($env:DEVICE_INFO_SIGNING_KEYSTORE -or $env:DEVICE_INFO_SIGNING_PASSWORD) {
    if (!$env:DEVICE_INFO_SIGNING_KEYSTORE -or !$env:DEVICE_INFO_SIGNING_PASSWORD) {
        throw '请同时设置两个签名环境变量，或清除两者后配置 config.local.jsonc。'
    }
} elseif (!$hasLocalConfig) {
    throw '请先将 config.jsonc 复制为 config.local.jsonc，填写 keystore 和 password 后再打包。'
}
if ($hasLocalConfig) {
    try {
        $json = [IO.File]::ReadAllText($localConfig)
        # Remove JSONC comments without changing quoted values, such as paths or passwords.
        $json = [regex]::Replace($json, '"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/', {
            param($token)
            if ($token.Value.StartsWith('"')) { return $token.Value }
            return ' '
        })
        # Windows PowerShell 5 requires strict JSON, even when the file uses JSONC.
        $json = [regex]::Replace($json, '"(?:\\.|[^"\\])*"|,(?=\s*[}\]])', {
            param($token)
            if ($token.Value.StartsWith('"')) { return $token.Value }
            return ''
        })
        $config = ConvertFrom-Json -InputObject $json -ErrorAction Stop
        if ($config -isnot [pscustomobject]) { throw 'Expected a JSON object.' }
    } catch {
        throw 'config.local.jsonc 格式有误，请参照 config.jsonc。'
    }

    foreach ($setting in @(@('jdkPath', 'JAVA_HOME'), @('androidSdkPath', 'ANDROID_HOME'))) {
        $field, $variable = $setting
        $path = $config.$field
        if ($null -ne $path -and $path -isnot [string]) {
            throw "config.local.jsonc 中的 $field 必须是路径字符串。"
        }
        if ([string]::IsNullOrWhiteSpace($path)) { continue }
        if (![IO.Path]::IsPathRooted($path)) { $path = Join-Path $PSScriptRoot $path }
        $path = [IO.Path]::GetFullPath($path)
        if ($field -eq 'jdkPath' -and (!(Test-Path -LiteralPath (Join-Path $path 'bin/javac.exe') -PathType Leaf) -or
                !(Test-Path -LiteralPath (Join-Path $path 'bin/java.exe') -PathType Leaf))) {
            throw 'config.local.jsonc 中的 jdkPath 无效，请指向包含 java.exe 和 javac.exe 的 JDK 根目录。'
        }
        if ($field -eq 'androidSdkPath') {
            if (!(Test-Path -LiteralPath (Join-Path $path 'platforms/android-36/android.jar') -PathType Leaf) -or
                    !(Test-Path -LiteralPath (Join-Path $path 'build-tools/36.0.0/lib/apksigner.jar') -PathType Leaf)) {
                throw 'config.local.jsonc 中的 androidSdkPath 无效，请指向已安装 Platform 36 和 Build Tools 36.0.0 的 SDK 根目录。'
            }
            if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $path }
        }
        Set-Item -LiteralPath "Env:$variable" -Value $path
    }
}

# Tests also locate a full JDK in the current process if JAVA_HOME is not configured.
& (Join-Path $PSScriptRoot 'test.ps1')
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:ANDROID_HOME\platforms\android-36\android.jar")) {
    throw '请安装 Android SDK Platform 36 和 Build Tools 36.0.0，并确认 ANDROID_HOME 指向 SDK 目录。'
}
$argsForGradle = @('--no-daemon', '--console=plain', 'assembleRelease', 'lintRelease')
if ($Offline) { $argsForGradle = @('--offline') + $argsForGradle }
Push-Location $PSScriptRoot
try {
    $signingError = $null
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat @argsForGradle 2>&1 | ForEach-Object {
            $line = $_.ToString()
            if ($line -match '\[DEVICE_INFO_SIGNING:([A-Z_]+)\]') { $signingError = $Matches[1] }
            Write-Host $line
        }
        $gradleExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($gradleExit -ne 0) {
        $reason = switch ($signingError) {
            'CONFIG_FORMAT' { 'config.local.jsonc 格式有误，请参照 config.jsonc。' }
            'CONFIG_FIELDS' { '请填写签名文件路径和密码。' }
            'KEYSTORE_MISSING' { '找不到签名文件，请检查 keystore 路径。' }
            'KEYSTORE_INVALID' { '无法读取签名文件，请确认它是有效的 PKCS12 文件。' }
            'STORE_PASSWORD' { '签名文件密码错误，请检查 password。' }
            'KEY_ALIAS' { '签名文件中没有别名为 device-info 的私钥。' }
            'KEY_PASSWORD' { '私钥密码错误，本项目要求私钥和签名文件使用同一密码。' }
            'CERTIFICATE_MISMATCH' { '签名证书不匹配，请使用原来的 device-info-signing.p12。' }
        }
        if ($reason) { throw "签名检查失败：$reason" }
        throw 'Android 构建或 Lint 检查失败，请查看上方日志。'
    }
    & .\verify-apk.ps1 -RequireDistributionSignature
    Write-Host "APK 已通过验证 $PSScriptRoot\dist\device-info.apk"
} finally {
    Pop-Location
}
# Intentionally no adb install, default-home changes, or device commands.
