$ErrorActionPreference = 'Stop'

function Assert-True($condition, $message) {
    if (-not $condition) { throw $message }
}

$batchSource = Join-Path (Split-Path $PSScriptRoot -Parent) 'build.bat'
Assert-True (Test-Path -LiteralPath $batchSource -PathType Leaf) 'build.bat should exist.'

$batchText = [System.IO.File]::ReadAllText($batchSource)
Assert-True ($batchText -notmatch '(?im)^\s*(cls|chcp)\b') 'The pause must preserve the build log and console code page.'
Assert-True ($batchText -match '(?m)-ShowPausePrompt\s*\r?\npause >nul') 'The batch should append a single prompt before waiting.'

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('device info & build test ' + [guid]::NewGuid().ToString('N'))
$previousMode = $env:DEVICE_INFO_BUILD_TEST_MODE
$previousPause = $env:DEVICE_INFO_BUILD_NO_PAUSE
try {
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $stub = @'
param([switch]$Offline)
if ($env:DEVICE_INFO_BUILD_TEST_MODE -eq 'fail') { exit 23 }
[System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'called.txt'), $Offline.ToString())
'@
    Copy-Item -LiteralPath $batchSource -Destination (Join-Path $tempRoot 'build.bat')
    [System.IO.File]::WriteAllText((Join-Path $tempRoot 'build.ps1'), $stub)
    $rootApk = Join-Path $tempRoot 'device-info.apk'
    $distApk = Join-Path $tempRoot 'dist\device-info.apk'
    New-Item -ItemType Directory -Path (Join-Path $tempRoot 'dist') | Out-Null
    [System.IO.File]::WriteAllText($rootApk, 'root output')
    [System.IO.File]::WriteAllText($distApk, 'dist output')

    function Invoke-Batch([switch]$Offline) {
        Push-Location (Split-Path $tempRoot -Parent)
        try {
            $oldPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            if ($Offline) {
                & (Join-Path $tempRoot 'build.bat') -Offline 2>&1 | Out-Null
            } else {
                & (Join-Path $tempRoot 'build.bat') 2>&1 | Out-Null
            }
            return $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
            Pop-Location
        }
    }

    $env:DEVICE_INFO_BUILD_NO_PAUSE = '1'
    $env:DEVICE_INFO_BUILD_TEST_MODE = 'success'
    $result = Invoke-Batch -Offline
    Assert-True ($result -eq 0) 'Successful build should exit with code 0.'
    Assert-True ([System.IO.File]::ReadAllText((Join-Path $tempRoot 'called.txt')) -eq 'True') 'build.bat should forward -Offline to build.ps1.'
    Assert-True ([System.IO.File]::ReadAllText($rootApk) -eq 'root output') 'build.bat must not copy or modify build outputs.'
    Assert-True ([System.IO.File]::ReadAllText($distApk) -eq 'dist output') 'build.bat must not modify the dist APK.'

    $env:DEVICE_INFO_BUILD_TEST_MODE = 'fail'
    $result = Invoke-Batch
    Assert-True ($result -eq 23) 'build.bat should propagate the build failure exit code.'
    Assert-True ([System.IO.File]::ReadAllText($rootApk) -eq 'root output') 'A failed build must not change the root APK.'

    $project = Split-Path $PSScriptRoot -Parent
    foreach ($file in @('build.ps1', 'config.jsonc')) {
        Copy-Item -LiteralPath (Join-Path $project $file) -Destination $tempRoot -Force
    }
    $originalKey = $env:DEVICE_INFO_SIGNING_KEYSTORE
    $originalPassword = $env:DEVICE_INFO_SIGNING_PASSWORD
    $originalAndroidHome = $env:ANDROID_HOME
    $originalJavaHome = $env:JAVA_HOME
    $originalSdkRoot = $env:ANDROID_SDK_ROOT
    try {
        $env:DEVICE_INFO_SIGNING_KEYSTORE = $null
        $env:DEVICE_INFO_SIGNING_PASSWORD = $null
        $missingConfig = $null
        try { & (Join-Path $tempRoot 'build.ps1') } catch { $missingConfig = $_.Exception.Message }
        Assert-True ($missingConfig -like '*请先将 config.jsonc 复制为 config.local.jsonc*') 'The template alone should prompt for local configuration.'
        Assert-True ([System.IO.File]::ReadAllText($distApk) -eq 'dist output') 'Missing configuration must preserve the existing dist APK.'

        $env:DEVICE_INFO_SIGNING_KEYSTORE = 'unused-test-keystore.p12'
        $env:DEVICE_INFO_SIGNING_PASSWORD = 'test-only-password'
        $env:ANDROID_HOME = Join-Path $tempRoot 'sdk'
        $platform = Join-Path $env:ANDROID_HOME 'platforms/android-36'
        New-Item -ItemType Directory -Path $platform -Force | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $platform 'android.jar'), '')
        [System.IO.File]::WriteAllText((Join-Path $tempRoot 'test.ps1'), '')
        foreach ($diagnostic in @(
            @{ code = 'STORE_PASSWORD'; message = '签名文件密码错误' },
            @{ code = 'CERTIFICATE_MISMATCH'; message = '签名证书不匹配' }
        )) {
            $gradleStub = "@echo off`r`necho [DEVICE_INFO_SIGNING:$($diagnostic.code)] 1>&2`r`nexit /b 1`r`n"
            [System.IO.File]::WriteAllText((Join-Path $tempRoot 'gradlew.bat'), $gradleStub)
            $summary = $null
            try { & (Join-Path $tempRoot 'build.ps1') 6>$null } catch { $summary = $_.Exception.Message }
            Assert-True ($summary -like "签名检查失败：$($diagnostic.message)*") 'The final error should describe the signing failure.'
        }
        $jdk = Join-Path $tempRoot 'local jdk'
        $sdk = Join-Path $tempRoot 'local sdk'
        foreach ($file in @('bin/java.exe','bin/javac.exe')) {
            $exe = Join-Path $jdk $file
            New-Item -ItemType Directory -Path (Split-Path $exe) -Force | Out-Null
            [System.IO.File]::WriteAllText($exe, '')
        }
        foreach ($file in @('platforms/android-36/android.jar','build-tools/36.0.0/lib/apksigner.jar')) {
            $jar = Join-Path $sdk $file
            New-Item -ItemType Directory -Path (Split-Path $jar) -Force | Out-Null
            [System.IO.File]::WriteAllText($jar, '')
        }
        [System.IO.File]::WriteAllText((Join-Path $tempRoot 'config.local.jsonc'),
            "{ // Relative tool paths`n`"jdkPath`": `"local jdk`", `"androidSdkPath`": `"local sdk`", // JSONC permits a trailing comma`n}")
        $env:JAVA_HOME = 'environment-jdk'
        $env:ANDROID_HOME = 'environment-sdk'
        $env:ANDROID_SDK_ROOT = 'environment-sdk'
        $summary = $null
        try { & (Join-Path $tempRoot 'build.ps1') 6>$null } catch { $summary = $_.Exception.Message }
        Assert-True ($summary -like '签名检查失败：签名证书不匹配*') 'Local tool paths should reach Gradle.'
        Assert-True ($env:JAVA_HOME -eq $jdk -and $env:ANDROID_HOME -eq $sdk -and $env:ANDROID_SDK_ROOT -eq $sdk) 'Local paths must override environment paths.'
    } finally {
        $env:DEVICE_INFO_SIGNING_KEYSTORE = $originalKey
        $env:DEVICE_INFO_SIGNING_PASSWORD = $originalPassword
        $env:ANDROID_HOME = $originalAndroidHome
        $env:JAVA_HOME = $originalJavaHome
        $env:ANDROID_SDK_ROOT = $originalSdkRoot
    }

    Write-Host 'build.bat tests passed.'
} finally {
    $env:DEVICE_INFO_BUILD_TEST_MODE = $previousMode
    $env:DEVICE_INFO_BUILD_NO_PAUSE = $previousPause
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
