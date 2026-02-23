@echo off
setlocal EnableExtensions

REM =========================
REM CONFIG
REM =========================
set "REPO_DIR=C:\Users\Admin\Documents\GitHub\TS-web\sim"
set "RUNTIME_DIR=C:\Users\Admin\Documents\MCAW\sim-runtime"
set "PORT=5173"

set "REPO_NM=%REPO_DIR%\node_modules"
set "RUNTIME_NM=%RUNTIME_DIR%\node_modules"
set "OUT_DIST=%RUNTIME_DIR%\dist"
set "LOG_DIR=%RUNTIME_DIR%\logs"

REM =========================
REM Create runtime/log dirs first
REM =========================
if not exist "%RUNTIME_DIR%" mkdir "%RUNTIME_DIR%" >nul 2>&1
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>&1

REM =========================
REM Timestamp (PowerShell; fallback if missing)
REM =========================
set "TS="
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss" 2^>nul') do set "TS=%%i"
if "%TS%"=="" set "TS=no-ts"
set "LOG_FILE=%LOG_DIR%\runtime-%TS%.log"

call :log ==========================================================
call :log MCAW SIM runtime clean/install/build/run
call :log Repo:    %REPO_DIR%
call :log Runtime: %RUNTIME_DIR%
call :log Log:     %LOG_FILE%
call :log ==========================================================
call :log.

REM =========================
REM Safety checks
REM =========================
if not exist "%REPO_DIR%\package.json" (
  call :log [ERROR] package.json not found in %REPO_DIR%
  goto :fail
)
if not exist "%REPO_DIR%\src" (
  call :log [ERROR] src folder not found in %REPO_DIR% (wrong repo root?)
  goto :fail
)

REM =========================
REM Clean repo dist only
REM =========================
call :log [INFO] Removing repo dist (if exists)...
if exist "%REPO_DIR%\dist" (
  rmdir /s /q "%REPO_DIR%\dist" >> "%LOG_FILE%" 2>&1
)

REM =========================
REM Ensure runtime node_modules exists
REM =========================
if not exist "%RUNTIME_NM%" mkdir "%RUNTIME_NM%" >nul 2>&1

REM =========================
REM Ensure repo node_modules is junction to runtime
REM Strategy:
REM  1) If repo\node_modules exists -> try delete it (it may be locked)
REM  2) Create junction
REM If delete fails -> stop with clear instructions
REM =========================
call :log [INFO] Ensuring repo\node_modules is a junction to runtime...

if exist "%REPO_NM%" (
  call :log [INFO] Removing repo\node_modules (required for clean repo)...
  rmdir /s /q "%REPO_NM%" >> "%LOG_FILE%" 2>&1
)

if exist "%REPO_NM%" (
  call :log [ERROR] Cannot remove %REPO_NM% (Access denied / locked).
  call :log         Close VS Code/WebStorm, stop vite/dev servers, close Explorer windows.
  call :log         Then run again. (Or run as Admin.)
  goto :fail
)

call :log [INFO] Creating junction (Admin usually required): repo\node_modules -> runtime\node_modules
mklink /J "%REPO_NM%" "%RUNTIME_NM%" >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  call :log [ERROR] mklink failed. Run CMD as Administrator.
  goto :fail
)

REM =========================
REM Install deps + build to runtime
REM =========================
call :log.
call :log [INFO] npm install (deps go into runtime via junction)...
pushd "%REPO_DIR%" >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  call :log [ERROR] Cannot enter repo dir.
  goto :fail
)

call npm install >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  popd
  call :log [ERROR] npm install failed. See log.
  goto :fail
)

call :log [INFO] Build to %OUT_DIST% ...
if exist "%OUT_DIST%" rmdir /s /q "%OUT_DIST%" >> "%LOG_FILE%" 2>&1

call npm run build -- --outDir "%OUT_DIST%" >> "%LOG_FILE%" 2>&1
if errorlevel 1 (
  popd
  call :log [ERROR] npm run build failed. See log.
  goto :fail
)

popd

REM =========================
REM Serve runtime dist
REM =========================
call :log.
call :log [INFO] Serving runtime dist on http://localhost:%PORT% ...

pushd "%RUNTIME_DIR%" >> "%LOG_FILE%" 2>&1
python --version >nul 2>&1
if not errorlevel 1 (
  call :log [INFO] Using python http.server
  python -m http.server %PORT% --directory dist
  popd
  goto :ok
)

call :log [INFO] Python not found -> using npx serve
npx serve -l %PORT% dist >> "%LOG_FILE%" 2>&1
popd

:ok
call :log.
call :log [OK] Done. Log: %LOG_FILE%
echo.
echo [OK] Log:
echo   %LOG_FILE%
echo.
pause
exit /b 0

:fail
call :log.
call :log [FAIL] Script stopped. Log: %LOG_FILE%
echo.
echo [FAIL] Mrkni do logu:
echo   %LOG_FILE%
echo.
pause
exit /b 1

:log
echo %*
>>"%LOG_FILE%" echo %*
exit /b 0
