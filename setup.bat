@echo off
title Edge TTS - Setup and Server
echo.
echo  =============================================
echo   Edge TTS - Premium Neural Voice Setup
echo  =============================================
echo.

if exist "venv\Scripts\python.exe" (
    venv\Scripts\python.exe --version >nul 2>&1
    if errorlevel 1 (
        echo [!] Broken virtual environment detected. Recreating...
        rmdir /s /q venv
    )
)

if not exist "venv\Scripts\python.exe" (
    echo [1/2] Creating Python environment...
    python -m venv venv
    if errorlevel 1 (
        echo ERROR: Python not found. Install Python 3.10+ from python.org
        pause
        exit /b 1
    )
    echo    Done!
) else (
    echo [1/2] Python environment ready.
)

echo [2/2] Installing dependencies...
venv\Scripts\pip.exe install --quiet edge-tts fastapi uvicorn pydantic
echo    Done!

echo.
echo  =============================================
echo   Setup Complete - No model downloads needed!
echo  =============================================
echo.
echo  Starting Edge TTS Server...
echo  Keep this window open while reading.
echo  Press Ctrl+C to stop.
echo.

powershell -Command "$p = (Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue).OwningProcess; if ($p) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }"
venv\Scripts\python.exe server.py
pause
