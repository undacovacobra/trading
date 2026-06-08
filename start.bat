@echo off
cd /d "%~dp0"

echo Starting trading bot...

:: Pull latest code
git pull

:: Start the bot in a named window
start "Trading Bot" cmd /k "cd /d %~dp0 && C:\Users\TheTr\AppData\Local\Programs\Python\Python311\python.exe -m uvicorn webhook_server:app --host 0.0.0.0 --port 8000"

:: Wait for bot to start
timeout /t 5 /nobreak > nul

:: Start ngrok in a named window
start "ngrok Tunnel" cmd /k "ngrok http 8000"

echo.
echo Both windows are starting up.
echo - "Trading Bot" window = the bot server
echo - "ngrok Tunnel" window = your public webhook URL
echo.
echo Once ngrok shows a URL, that is your TradingView webhook address.
echo Add /webhook to the end of it.
echo.
pause
