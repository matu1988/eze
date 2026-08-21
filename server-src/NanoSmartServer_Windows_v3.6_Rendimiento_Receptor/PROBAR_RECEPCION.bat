@echo off
title Prueba UDP NanoSmart
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo ERROR: Node.js no esta instalado.
  pause
  exit /b 1
)

node tools\send-test.js 127.0.0.1 7050
echo.
echo Revise el panel en http://localhost:18082
pause
