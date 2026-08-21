@echo off
title Configurar Firebase - NanoSmart Server
cd /d "%~dp0"

echo.
echo Este asistente copia la clave privada de Firebase al servidor NanoSmart.
echo La clave quedara solamente en esta PC y no se incluye en la APK.
echo.
set /p "ORIGEN=Arrastre aqui el JSON privado descargado de Firebase y presione Enter: "
set "ORIGEN=%ORIGEN:"=%"

if not exist "%ORIGEN%" (
  echo.
  echo ERROR: No se encontro el archivo indicado.
  echo.
  pause
  exit /b 1
)

if not exist "%~dp0secrets" mkdir "%~dp0secrets"
copy /Y "%ORIGEN%" "%~dp0secrets\firebase-service-account.json" >nul
if errorlevel 1 (
  echo.
  echo ERROR: No se pudo copiar la clave.
  echo.
  pause
  exit /b 1
)

echo.
echo Firebase quedo configurado correctamente.
echo Reinicie NanoSmart Server para activar las notificaciones push.
echo.
pause
