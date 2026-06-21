@echo off
setlocal
cd /d "%~dp0"
py -3 lan_clipboard_windows.py
if errorlevel 1 (
  python lan_clipboard_windows.py
)
