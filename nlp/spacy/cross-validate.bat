@echo off
rem Cross-validates the spaCy NLU model: splits the training data into 10 parts, then for each part trains a model
rem on the other 9 and tests it on that one, each in its own process. Results land in nlp\spacy\results\cv-<date>-<time>.
rem Options: --folds N (default 10), --resume results\cv-... (continue a stopped run).
setlocal
cd /d "%~dp0"

if not defined SPACY_VENV set "SPACY_VENV=%USERPROFILE%\.venvs\movebytext-spacy"
if not exist "%SPACY_VENV%\Scripts\activate.bat" (
    echo Can't find the spaCy environment at "%SPACY_VENV%". See README.md for the setup steps.
    exit /b 1
)
call "%SPACY_VENV%\Scripts\activate.bat"

rem Same data as train.bat. A resumed run keeps using the parts it split at the start.
python ..\rasa\generate_nlu.py || goto :fail
python ..\rasa\check_overlap.py --remove || goto :fail
python cross_validate.py %* || goto :fail
exit /b 0

:fail
echo.
echo Cross-validation failed or stopped, see the messages above.
exit /b 1
