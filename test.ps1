$ErrorActionPreference = 'Stop'
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = $OutputEncoding

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Android\Android Studio\jbr",
        "$env:ProgramFiles\Android\Android Studio\jbr"
    )
    $env:JAVA_HOME = $candidates | Where-Object { Test-Path "$_\bin\javac.exe" } | Select-Object -First 1
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a full JDK (17 or newer).' }

$out = Join-Path $PSScriptRoot 'build\core-tests'
New-Item -ItemType Directory -Force $out | Out-Null
$sources = @(
    (Join-Path $PSScriptRoot 'src\main\java\com\gathin\deviceinfo\DeviceReport.java'),
    (Join-Path $PSScriptRoot 'src\main\java\com\gathin\deviceinfo\SettingsNavigator.java'),
    (Join-Path $PSScriptRoot 'tests\com\gathin\deviceinfo\CoreTests.java')
)
& "$env:JAVA_HOME\bin\javac.exe" --release 8 -Xlint:-options -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw 'Core test compilation failed.' }
& "$env:JAVA_HOME\bin\java.exe" '-Dfile.encoding=UTF-8' -cp $out com.gathin.deviceinfo.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed.' }
