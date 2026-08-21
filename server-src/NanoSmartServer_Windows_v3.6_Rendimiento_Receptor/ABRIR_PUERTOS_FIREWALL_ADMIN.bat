@echo off
title Firewall NanoSmart Server

net session >nul 2>&1
if errorlevel 1 (
  echo.
  echo Debe ejecutar este archivo con clic derecho - Ejecutar como administrador.
  echo.
  pause
  exit /b 1
)

netsh advfirewall firewall delete rule name="NanoSmart Server UDP 7050" >nul 2>&1
netsh advfirewall firewall delete rule name="NanoSmart Panel TCP 8081" >nul 2>&1
netsh advfirewall firewall delete rule name="NanoSmart API TCP 8082" >nul 2>&1
netsh advfirewall firewall delete rule name="NanoSmart API TCP 18082" >nul 2>&1

netsh advfirewall firewall add rule name="NanoSmart Server UDP 7050" dir=in action=allow protocol=UDP localport=7050 profile=private
netsh advfirewall firewall add rule name="NanoSmart API TCP 18082" dir=in action=allow protocol=TCP localport=18082 profile=private

echo.
echo Reglas creadas para redes privadas:
echo - UDP 7050: recepcion de equipos
echo - TCP 18082: API y panel web dentro de la red local
echo.
pause
