@echo off
setlocal

cd /d "%~dp0"
if exist "%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2" (
    set "JAVA_HOME=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2"
    set "PATH=%USERPROFILE%\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2\bin;%PATH%"
)
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
