@echo off
setlocal
cd /d "%~dp0tools\memory-harness"

echo === Memory Harness WebUI ===
echo.

if not exist node_modules (
  echo [setup] node_modules missing, running npm install...
  call npm install
  if errorlevel 1 (echo [error] npm install failed & goto :end)
)

echo [setup] building core / agent / server (in dependency order)...
call npx tsc -p packages\core
if errorlevel 1 (echo [error] core build failed & goto :end)
call npx tsc -p packages\agent
if errorlevel 1 (echo [error] agent build failed & goto :end)
call npx tsc -p packages\server
if errorlevel 1 (echo [error] server build failed & goto :end)

if exist .env (
  echo [env] loading .env
  for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
) else (
  echo [env] no .env found - using LLM_TRANSPORT=mock for keyless smoke test
  set "LLM_TRANSPORT=mock"
)

echo [start] server ^(http://127.0.0.1:7421^) in new window...
start "harness-server" cmd /k "npm run dev:server"

timeout /t 2 /nobreak >nul
echo [start] web ^(vite^) in new window...
start "harness-web" cmd /k "npm run dev:web"

echo.
echo Server API: http://127.0.0.1:7421
echo Web UI:     see the vite window ^(default http://127.0.0.1:5173^)
echo.
echo To use a real LLM: copy .env.example to .env and set LLM_API_KEY / LLM_BASE_URL / LLM_MODEL
echo Close both popped-up windows to stop the harness.
echo.
:end
pause
endlocal
