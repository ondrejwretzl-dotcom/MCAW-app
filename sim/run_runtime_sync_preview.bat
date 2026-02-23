@echo off
setlocal EnableExtensions

REM ==================================================
REM Build in REPO sim folder, sync dist to RUNTIME, run preview from RUNTIME
REM ==================================================

REM --- Default paths (edit as needed) ---
set "REPO_SIM=C:\Users\Admin\Documents\GitHub\MCAW-app\sim"
set "RUNTIME_ROOT=C:\Users\Admin\Documents\MCAW\sim-runtime"

REM --- Optional override from env ---
if not "%REPO_SIM_OVERRIDE%"=="" set "REPO_SIM=%REPO_SIM_OVERRIDE%"
if not "%RUNTIME_ROOT_OVERRIDE%"=="" set "RUNTIME_ROOT=%RUNTIME_ROOT_OVERRIDE%"

echo ==================================================
echo  TS-web SIM - BUILD+SYNC+PREVIEW
echo ==================================================
echo REPO_SIM:    %REPO_SIM%
echo RUNTIME_ROOT:%RUNTIME_ROOT%
echo.

if not exist "%REPO_SIM%\package.json" (
  echo [ERROR] Repo sim folder invalid: %REPO_SIM%
  echo         Missing package.json
  pause
  exit /b 1
)

if not exist "%RUNTIME_ROOT%" (
  echo [ERROR] Runtime folder does not exist: %RUNTIME_ROOT%
  pause
  exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
  echo [ERROR] npm not found in PATH.
  pause
  exit /b 1
)

echo [INFO] Killing node/vite (if running)...
taskkill /IM node.exe /F >nul 2>&1
taskkill /IM vite.exe /F >nul 2>&1

REM ---------- BUILD IN REPO ----------
pushd "%REPO_SIM%" || (echo [ERROR] Cannot enter repo sim.& pause& exit /b 1)

echo [INFO] Installing repo dependencies (npm ci)...
call npm ci
if errorlevel 1 (
  echo [ERROR] npm ci failed in repo sim.
  popd
  pause
  exit /b 1
)

echo [INFO] Building repo sim (npm run build)...
call npm run build
if errorlevel 1 (
  echo [ERROR] npm run build failed in repo sim.
  popd
  pause
  exit /b 1
)

if not exist "%REPO_SIM%\dist" (
  echo [ERROR] Repo build finished but dist missing.
  popd
  pause
  exit /b 1
)

REM write simple build marker to help verify freshness in runtime
for /f "tokens=1-3 delims=/. " %%a in ('date /t') do set _d=%%a-%%b-%%c
for /f "tokens=1-2 delims=:" %%h in ('time /t') do set _t=%%h%%i
echo Built from repo: %REPO_SIM% > "%REPO_SIM%\dist\BUILD_INFO.txt"
echo Built at: %date% %time%>> "%REPO_SIM%\dist\BUILD_INFO.txt"

echo [OK] Repo build ready.
popd

REM ---------- SYNC TO RUNTIME ----------
if exist "%RUNTIME_ROOT%\dist" (
  echo [INFO] Removing old runtime dist...
  rmdir /S /Q "%RUNTIME_ROOT%\dist"
)

echo [INFO] Copying dist repo -> runtime...
robocopy "%REPO_SIM%\dist" "%RUNTIME_ROOT%\dist" /E >nul
if errorlevel 8 (
  echo [ERROR] robocopy failed while syncing dist.
  pause
  exit /b 1
)

echo [OK] Runtime dist synced.

REM Keep runtime package files in sync too (preview scripts/deps)
if exist "%REPO_SIM%\package.json" copy /Y "%REPO_SIM%\package.json" "%RUNTIME_ROOT%\" >nul
if exist "%REPO_SIM%\package-lock.json" copy /Y "%REPO_SIM%\package-lock.json" "%RUNTIME_ROOT%\" >nul

REM ---------- RUN PREVIEW IN RUNTIME ----------
pushd "%RUNTIME_ROOT%" || (echo [ERROR] Cannot enter runtime.& pause& exit /b 1)

if not exist "%RUNTIME_ROOT%\node_modules" (
  echo [INFO] Runtime node_modules missing -> npm ci
  call npm ci
  if errorlevel 1 (
    echo [ERROR] npm ci failed in runtime.
    popd
    pause
    exit /b 1
  )
)

echo [INFO] BUILD marker in runtime:
if exist "%RUNTIME_ROOT%\dist\BUILD_INFO.txt" type "%RUNTIME_ROOT%\dist\BUILD_INFO.txt"

echo.
echo [INFO] Starting preview from runtime...
call npm run preview

popd
exit /b 0
