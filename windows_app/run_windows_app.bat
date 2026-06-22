@echo off
setlocal
cd /d "%~dp0"

where pyw >nul 2>&1
if "%errorlevel%"=="0" (
  start "" pyw -3 "%~dp0lan_clipboard_windows.py"
  exit /b
)

where pythonw >nul 2>&1
if "%errorlevel%"=="0" (
  start "" pythonw "%~dp0lan_clipboard_windows.py"
  exit /b
)

py -3 "%~dp0lan_clipboard_windows.py"
if errorlevel 1 python "%~dp0lan_clipboard_windows.py"
