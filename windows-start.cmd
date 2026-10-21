@echo off
rem Plinko Mega demo: builds and starts the demo, then checks the tunnel.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\start.ps1"
pause
