@echo off
title Clave NanoSmart Produccion
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo ERROR: Node.js no esta instalado o no esta en el PATH.
  pause
  exit /b 1
)

node tools\production-key.js
echo.
pause
