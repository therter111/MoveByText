@echo off
rem Cross-validates the Rasa NLU model: splits the training data into 10 parts, then for each part trains a model on
rem the other 9 and tests it on that one, each in its own process. Results land in rasa\results\cv-<date>-<time>.
rem Takes about 3 hours on the CPU. Options: --folds N (default 10), --resume results\cv-... (continue a stopped run).
setlocal
cd /d "%~dp0"

if not defined RASA_VENV set "RASA_VENV=%USERPROFILE%\.venvs\movebytext-rasa"
if not exist "%RASA_VENV%\Scripts\activate.bat" (
    echo Can't find the Rasa environment at "%RASA_VENV%". See README.md for the setup steps.
    exit /b 1
)
call "%RASA_VENV%\Scripts\activate.bat"

rem Same data as train.bat. A resumed run keeps using the parts it split at the start.
python generate_nlu.py || goto :fail
python check_overlap.py --remove || goto :fail
python cross_validate.py %* || goto :fail
exit /b 0

:fail
echo.
echo Cross-validation failed or stopped, see the messages above.
exit /b 1
