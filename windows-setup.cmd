@echo off
rem Plinko Mega demo: makes the settings, the secrets and saves the tunnel token.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\setup.ps1"
pause
