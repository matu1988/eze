@echo off
title Firewall NanoSmart - Produccion TCP 18083

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo Debe ejecutar este archivo con clic derecho - Ejecutar como administrador.
  echo.
  pause
  exit /b 1
)

netsh advfirewall firewall delete rule name="NanoSmart Produccion TCP 18083" >nul 2>&1
netsh advfirewall firewall add rule name="NanoSmart Produccion TCP 18083" dir=in action=allow protocol=TCP localport=18083 profile=any

echo.
echo Regla de Windows creada para TCP 18083.
echo.
echo IMPORTANTE:
echo En el Security Group de AWS agregue TCP 18083 con origen limitado
echo exclusivamente a la IP publica de la fabrica, en formato IP/32.
echo No utilice 0.0.0.0/0 para este puerto.
echo.
pause
