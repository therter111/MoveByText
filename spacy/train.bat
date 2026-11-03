@echo off
rem Trains the MoveByText spaCy NLU model: writes the training data with the Rasa templates, drops examples
rem identical to a test message, then runs train.py. The model lands in spacy\models. Takes a few minutes on the CPU.
setlocal
cd /d "%~dp0"

if not defined SPACY_VENV set "SPACY_VENV=%USERPROFILE%\.venvs\movebytext-spacy"
if not exist "%SPACY_VENV%\Scripts\activate.bat" (
    echo Can't find the spaCy environment at "%SPACY_VENV%". See README.md for the setup steps.
    exit /b 1
)
call "%SPACY_VENV%\Scripts\activate.bat"

rem The same examples as the Rasa model: both scripts only need plain Python
python ..\rasa\generate_nlu.py || goto :fail
python ..\rasa\check_overlap.py --remove || goto :fail
python train.py || goto :fail

echo.
echo Training done. Start the server with start-server.bat
exit /b 0

:fail
echo.
echo Training failed, see the messages above.
exit /b 1
