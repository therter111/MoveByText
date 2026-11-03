@echo off
rem Starts the spaCy NLU server the mod talks to (provider "spacy" in data\config\llmdata.json).
rem Listens on 127.0.0.1:5007 only, so nothing outside this PC can reach it. Keep this window open while playing;
rem the server is ready when it prints "spaCy server is up and running".
setlocal
cd /d "%~dp0"

if not defined SPACY_VENV set "SPACY_VENV=%USERPROFILE%\.venvs\movebytext-spacy"
if not exist "%SPACY_VENV%\Scripts\activate.bat" (
    echo Can't find the spaCy environment at "%SPACY_VENV%". See README.md for the setup steps.
    exit /b 1
)
if not exist "models\nlu-*" (
    echo No trained model yet. Run train.bat first.
    exit /b 1
)
call "%SPACY_VENV%\Scripts\activate.bat"

python server.py --host 127.0.0.1 --port 5007
