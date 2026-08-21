@echo off
title Firewall AWS - NanoSmart Server

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo Debe ejecutar este archivo con clic derecho - Ejecutar como administrador.
  echo.
  pause
  exit /b 1
)

netsh advfirewall firewall delete rule name="NanoSmart AWS UDP 7050" >nul 2>&1
netsh advfirewall firewall delete rule name="NanoSmart AWS TCP 18082" >nul 2>&1
netsh advfirewall firewall delete rule name="NanoSmart Produccion TCP 18083" >nul 2>&1

netsh advfirewall firewall add rule name="NanoSmart AWS UDP 7050" dir=in action=allow protocol=UDP localport=7050 profile=any
netsh advfirewall firewall add rule name="NanoSmart AWS TCP 18082" dir=in action=allow protocol=TCP localport=18082 profile=any
netsh advfirewall firewall add rule name="NanoSmart Produccion TCP 18083" dir=in action=allow protocol=TCP localport=18083 profile=any

echo.
echo Reglas de Windows creadas:
echo - UDP 7050: reportes de equipos y ACK
echo - TCP 18082: API de las aplicaciones moviles
echo - TCP 18083: API separada de la estacion de produccion
echo.
echo IMPORTANTE: tambien debe habilitar los puertos en el Security Group de AWS.
echo En AWS, TCP 18083 debe aceptar SOLAMENTE la IP publica de la fabrica.
echo El panel administrativo queda bloqueado para conexiones publicas.
echo.
pause
