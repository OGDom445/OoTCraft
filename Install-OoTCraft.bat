@echo off
rem OoTCraft installer: double-click to set up OoTCraft. See LEGAL.md - you must own both games.
title OoTCraft installer
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install.ps1" %*
echo.
pause
