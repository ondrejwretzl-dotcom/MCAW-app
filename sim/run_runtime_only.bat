@echo off
setlocal EnableExtensions

REM ==================================================
REM Runtime-only workflow (no npm install/build in repo)
REM - Sync source code from repo\sim -> runtime
REM - Install deps ONLY in runtime
REM - Build + Preview in runtime
REM ==================================================

set "REPO_SIM=C:\Users\Admin\Documents\GitHub\MCAW-app\sim"
set "RUNTIME_ROOT=C:\Users\Admin\Documents\MCAW\sim-runtime"

if not "%REPO_SIM_OVERRIDE%"=="" set "REPO_SIM=%REPO_SIM_OVERRIDE%"
if not "%RUNTIME_ROOT_OVERRIDE%"=="" set "RUNTIME_ROOT=%RUNTIME_ROOT_OVERRIDE%"

echo ==================================================
echo  TS-web SIM - RUNTIME ONLY
echo ==================================================
echo REPO_SIM:     %REPO_SIM%
echo RUNTIME_ROOT: %RUNTIME_ROOT%
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

echo [INFO] Killing node/vite if running...
taskkill /IM node.exe /F >nul 2>&1
taskkill /IM vite.exe /F >nul 2>&1

REM ---------- SAFETY: keep repo clean ----------
if exist "%REPO_SIM%\node_modules" (
  echo [WARN] Found repo node_modules. Removing to keep repo code-only...
  rmdir /S /Q "%REPO_SIM%\node_modules"
)
if exist "%REPO_SIM%\dist" (
  echo [INFO] Removing repo dist (runtime will own built output)...
  rmdir /S /Q "%REPO_SIM%\dist"
)

REM ---------- SYNC SOURCE REPO -> RUNTIME ----------
echo [INFO] Syncing source files to runtime...
robocopy "%REPO_SIM%" "%RUNTIME_ROOT%" /MIR /XD node_modules dist .git .github /XF *.log >nul
if errorlevel 8 (
  echo [ERROR] robocopy failed during source sync.
  pause
  exit /b 1
)

echo [OK] Source synced to runtime.

REM ---------- INSTALL / VERIFY DEPS IN RUNTIME ----------
pushd "%RUNTIME_ROOT%" || (echo [ERROR] Cannot enter runtime.& pause& exit /b 1)

if not exist "%RUNTIME_ROOT%\node_modules\.bin\vite.cmd" (
  echo [INFO] vite binary missing in runtime -> npm ci
  call npm ci
  if errorlevel 1 (
    echo [ERROR] npm ci failed in runtime.
    popd
    pause
    exit /b 1
  )
) else (
  echo [OK] Runtime dependencies already ready.
)

REM sanity check
if not exist "%RUNTIME_ROOT%\node_modules\.bin\vite.cmd" (
  echo [ERROR] vite.cmd still missing after npm ci.
  echo         Try deleting runtime node_modules manually and rerun.
  popd
  pause
  exit /b 1
)

REM ---------- BUILD + PREVIEW IN RUNTIME ----------
echo [INFO] Building in runtime...
call npm run build
if errorlevel 1 (
  echo [ERROR] npm run build failed in runtime.
  popd
  pause
  exit /b 1
)

echo Built from repo: %REPO_SIM% > "%RUNTIME_ROOT%\dist\BUILD_INFO.txt"
echo Built at: %date% %time%>> "%RUNTIME_ROOT%\dist\BUILD_INFO.txt"

echo [INFO] BUILD marker:
type "%RUNTIME_ROOT%\dist\BUILD_INFO.txt"

echo.
echo [INFO] Starting preview from runtime...
call npm run preview

popd
exit /b 0
