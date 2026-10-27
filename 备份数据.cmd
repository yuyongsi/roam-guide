@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -File "%~dp0scripts\backup-data.ps1"
echo.
pause
