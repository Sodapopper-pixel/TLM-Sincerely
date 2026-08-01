@echo off
setlocal

cd /d "%~dp0"
call gradlew.bat runClient
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo runClient failed with exit code %EXIT_CODE%.
) else (
    echo runClient exited successfully.
)
pause

exit /b %EXIT_CODE%
