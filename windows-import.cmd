@echo off
rem Plinko Mega demo: moves the data from another machine (the full copy unzipped into migration\), then starts.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\import.ps1"
pause
