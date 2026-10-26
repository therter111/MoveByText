@echo off
rem Starts the Rasa NLU server the mod talks to (provider "rasa" in data\config\llmdata.json).
rem Listens on 127.0.0.1:5005 only, so nothing outside this PC can reach it. Keep this window open while playing;
rem the server is ready when it prints "Rasa server is up and running".
setlocal
cd /d "%~dp0"

if not defined RASA_VENV set "RASA_VENV=%USERPROFILE%\.venvs\movebytext-rasa"
if not exist "%RASA_VENV%\Scripts\activate.bat" (
    echo Can't find the Rasa environment at "%RASA_VENV%". See README.md for the setup steps.
    exit /b 1
)
if not exist "models\*.tar.gz" (
    echo No trained model yet. Run train.bat first.
    exit /b 1
)
call "%RASA_VENV%\Scripts\activate.bat"

rasa run --enable-api --interface 127.0.0.1 --port 5005 --model models
