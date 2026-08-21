@echo off
title NanoSmart Server
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo ERROR: Node.js no esta instalado o no esta en el PATH.
  echo Instale Node.js 24 LTS desde https://nodejs.org/
  echo Luego cierre y vuelva a abrir esta ventana.
  echo.
  pause
  exit /b 1
)

echo Iniciando NanoSmart Server...
start "" "http://localhost:18082"
node server.js

echo.
echo El servidor se detuvo.
pause
