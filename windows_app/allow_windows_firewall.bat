@echo off
setlocal

net session >nul 2>&1
if not "%errorlevel%"=="0" (
  echo Requesting administrator permission...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

echo Adding Windows Firewall rules for LAN Clipboard Sync...
netsh advfirewall firewall delete rule name="LAN Clipboard Sync TCP 8787" >nul 2>&1
netsh advfirewall firewall delete rule name="LAN Clipboard Sync UDP 8788" >nul 2>&1
netsh advfirewall firewall add rule name="LAN Clipboard Sync TCP 8787" dir=in action=allow protocol=TCP localport=8787 profile=any
netsh advfirewall firewall add rule name="LAN Clipboard Sync UDP 8788" dir=in action=allow protocol=UDP localport=8788 profile=any

echo Removing any inbound blocking rules for Python...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-NetFirewallRule -Action Block -Direction Inbound -ErrorAction SilentlyContinue | Get-NetFirewallApplicationFilter | Where-Object Program -like '*\python.exe' | Get-NetFirewallRule | Remove-NetFirewallRule"

echo.
echo Done. Close this window, then restart the Windows app.
pause
