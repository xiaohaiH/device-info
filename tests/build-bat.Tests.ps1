$ErrorActionPreference = 'Stop'

function Assert-True($condition, $message) {
    if (-not $condition) { throw $message }
}

$batchSource = Join-Path (Split-Path $PSScriptRoot -Parent) 'build.bat'
Assert-True (Test-Path -LiteralPath $batchSource -PathType Leaf) 'build.bat should exist.'

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('device info & build test ' + [guid]::NewGuid().ToString('N'))
$previousMode = $env:DEVICE_INFO_BUILD_TEST_MODE
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

    Write-Host 'build.bat tests passed.'
} finally {
    $env:DEVICE_INFO_BUILD_TEST_MODE = $previousMode
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
