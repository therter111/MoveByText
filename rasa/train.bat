@echo off
rem Trains the MoveByText Rasa NLU model: writes the training data, drops examples identical to a test message,
rem then runs "rasa train nlu". The model lands in rasa\models. Takes a few minutes on the CPU.
setlocal
cd /d "%~dp0"

if not defined RASA_VENV set "RASA_VENV=%USERPROFILE%\.venvs\movebytext-rasa"
if not exist "%RASA_VENV%\Scripts\activate.bat" (
    echo Can't find the Rasa environment at "%RASA_VENV%". See README.md for the setup steps.
    exit /b 1
)
call "%RASA_VENV%\Scripts\activate.bat"

python generate_nlu.py || goto :fail
python check_overlap.py --remove || goto :fail
rasa train nlu --config config.yml --nlu data --out models || goto :fail
rem Keep only the newest model
for /f "skip=1 delims=" %%f in ('dir /b /o-d models\*.tar.gz') do del "models\%%f"

echo.
echo Training done. Start the server with start-server.bat
exit /b 0

:fail
echo.
echo Training failed, see the messages above.
exit /b 1
