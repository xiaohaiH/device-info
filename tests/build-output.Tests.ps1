param([switch]$Offline)
$ErrorActionPreference = 'Stop'

function Assert-True($condition, $message) {
    if (-not $condition) { throw $message }
}

$source = Split-Path $PSScriptRoot -Parent
$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('device info gradle test ' + [guid]::NewGuid().ToString('N'))
$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
        $env:JAVA_HOME = @(
            "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
            "$env:ProgramFiles\Android\Android Studio\jbr"
        ) | Where-Object { Test-Path "$_\bin\javac.exe" } | Select-Object -First 1
    }
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a full JDK (17 or newer).' }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }

    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    foreach ($name in @('src', 'gradle', 'build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew.bat')) {
        Copy-Item -LiteralPath (Join-Path $source $name) -Destination $tempRoot -Recurse
    }
    $gradleArgs = @('--no-daemon', '--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    $log = Join-Path $tempRoot 'gradle-test.log'

    function Invoke-Gradle([string[]]$taskArgs) {
        Push-Location $tempRoot
        try {
            $oldPreference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            & (Join-Path $tempRoot 'gradlew.bat') @gradleArgs @taskArgs 2>&1 | Out-File -LiteralPath $log -Encoding UTF8
            return $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
            Pop-Location
        }
    }

    $rootApk = Join-Path $tempRoot 'device-info.apk'
    $distApk = Join-Path $tempRoot 'dist\device-info.apk'
    [System.IO.File]::WriteAllText($rootApk, 'old root apk')
    $result = Invoke-Gradle @('assembleRelease')
    if ($result -ne 0) { Get-Content -LiteralPath $log -Tail 80 | Out-Host; throw 'Direct Gradle release build failed.' }
    $apks = @(Get-ChildItem (Join-Path $tempRoot 'build\outputs\apk\release\*.apk') | Where-Object { $_.Name -notlike '*unsigned*' })
    Assert-True ($apks.Count -eq 1) 'Release build should produce exactly one signed APK.'
    $builtHash = (Get-FileHash -LiteralPath $apks[0].FullName -Algorithm SHA256).Hash
    Assert-True ((Get-FileHash -LiteralPath $rootApk -Algorithm SHA256).Hash -eq $builtHash) 'Direct Gradle release build should replace the root APK.'
    Assert-True ((Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash -eq $builtHash) 'Direct Gradle release build should replace the dist APK.'

    [System.IO.File]::WriteAllText($rootApk, 'stale root apk')
    [System.IO.File]::WriteAllText($distApk, 'stale dist apk')
    $result = Invoke-Gradle @('assembleRelease')
    if ($result -ne 0) { Get-Content -LiteralPath $log -Tail 80 | Out-Host; throw 'Repeated Gradle release build failed.' }
    Assert-True ((Get-FileHash -LiteralPath $rootApk -Algorithm SHA256).Hash -eq $builtHash) 'Up-to-date release build should restore the root APK.'
    Assert-True ((Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash -eq $builtHash) 'Up-to-date release build should restore the dist APK.'

    $altered = [System.IO.File]::ReadAllBytes($apks[0].FullName)
    $altered[0] = $altered[0] -bxor 1
    foreach ($copy in @($rootApk, $distApk)) {
        [System.IO.File]::WriteAllBytes($copy, $altered)
        [System.IO.File]::SetLastWriteTimeUtc($copy, [System.IO.File]::GetLastWriteTimeUtc($apks[0].FullName))
    }
    $result = Invoke-Gradle @('assembleRelease')
    if ($result -ne 0) { Get-Content -LiteralPath $log -Tail 80 | Out-Host; throw 'Release build with altered outputs failed.' }
    Assert-True ((Get-FileHash -LiteralPath $rootApk -Algorithm SHA256).Hash -eq $builtHash) 'Release build should replace an altered root APK even when size and timestamp match.'
    Assert-True ((Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash -eq $builtHash) 'Release build should replace an altered dist APK even when size and timestamp match.'

    Remove-Item -LiteralPath $rootApk, $distApk
    $result = Invoke-Gradle @('assembleRelease')
    if ($result -ne 0) { Get-Content -LiteralPath $log -Tail 80 | Out-Host; throw 'Release build with missing outputs failed.' }
    Assert-True ((Get-FileHash -LiteralPath $rootApk -Algorithm SHA256).Hash -eq $builtHash) 'Up-to-date release build should recreate a missing root APK.'
    Assert-True ((Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash -eq $builtHash) 'Up-to-date release build should recreate a missing dist APK.'

    [System.IO.File]::WriteAllText($rootApk, 'old root apk')
    [System.IO.File]::WriteAllText($distApk, 'old dist apk')
    $result = Invoke-Gradle @('build')
    if ($result -ne 0) { Get-Content -LiteralPath $log -Tail 80 | Out-Host; throw 'Gradle build task failed.' }
    Assert-True ((Get-FileHash -LiteralPath $rootApk -Algorithm SHA256).Hash -eq $builtHash) 'Gradle build task should replace the root APK.'
    Assert-True ((Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash -eq $builtHash) 'Gradle build task should replace the dist APK.'

    $init = Join-Path $tempRoot 'fail-release.gradle'
    $failureScript = @'
gradle.projectsEvaluated {
    def app = gradle.rootProject
    app.tasks.register('intentionalReleaseFailure') {
        doLast { throw new GradleException('intentional release test failure') }
    }
    app.tasks.named('assembleRelease') { dependsOn('intentionalReleaseFailure') }
}
'@
    [System.IO.File]::WriteAllText($init, $failureScript)
    [System.IO.File]::WriteAllText($rootApk, 'old root apk')
    [System.IO.File]::WriteAllText($distApk, 'old dist apk')
    $result = Invoke-Gradle @('--init-script', $init, 'assembleRelease')
    Assert-True ($result -ne 0) 'Failed release build should return a nonzero exit code.'
    Assert-True ((Get-Content -LiteralPath $log -Raw) -match 'intentional release test failure') 'The release build should fail for the intended test reason.'
    Assert-True ([System.IO.File]::ReadAllText($rootApk) -eq 'old root apk') 'Failed release build must not replace the root APK.'
    Assert-True ([System.IO.File]::ReadAllText($distApk) -eq 'old dist apk') 'Failed release build must not replace the dist APK.'

    Write-Host 'Gradle release output tests passed.'
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
