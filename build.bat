@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
set "buildExit=%errorlevel%"
if "%DEVICE_INFO_BUILD_NO_PAUSE%"=="1" exit /b %buildExit%
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -ShowPausePrompt
pause >nul
exit /b %buildExit%
