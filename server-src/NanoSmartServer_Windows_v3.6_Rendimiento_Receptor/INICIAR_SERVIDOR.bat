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

node -e "require('qrcode')" >nul 2>nul
if errorlevel 1 (
  echo.
  echo Faltan dependencias del servidor. Intentando instalarlas automaticamente...
  where npm >nul 2>nul
  if errorlevel 1 (
    echo ERROR: npm no esta disponible en el PATH.
    echo Reinstale Node.js 24 LTS incluyendo npm.
    echo.
    pause
    exit /b 1
  )
  call npm ci --omit=dev
  if errorlevel 1 (
    echo.
    echo ERROR: No se pudieron instalar las dependencias.
    echo Verifique la conexion a Internet o use el paquete completo del servidor.
    echo.
    pause
    exit /b 1
  )
)

echo Iniciando NanoSmart Server...
start "" "http://localhost:18082"
node server.js

echo.
echo El servidor se detuvo.
pause
