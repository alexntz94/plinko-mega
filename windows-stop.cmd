@echo off
rem Plinko Mega demo: stops the demo (its data is kept).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\stop.ps1"
pause
