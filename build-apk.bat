@echo off
REM Build the debug APK on Windows. Requires JDK 17.
REM   winget install Microsoft.OpenJDK.17
REM Then just run:  build-apk.bat
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found. Install with: winget install Microsoft.OpenJDK.17
  exit /b 1
)

set SDKDIR=%LOCALAPPDATA%\Android\Sdk
if not exist "%SDKDIR%\platform-tools" (
  echo [*] Downloading Android command-line tools...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$zip='%TEMP%\cmdtools.zip';" ^
    "Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip;" ^
    "Expand-Archive -Force $zip '%TEMP%\cmdtools';" ^
    "New-Item -ItemType Directory -Force '%SDKDIR%\cmdline-tools\latest' | Out-Null;" ^
    "Copy-Item '%TEMP%\cmdtools\cmdline-tools\*' '%SDKDIR%\cmdline-tools\latest' -Recurse -Force;"
  echo y | "%SDKDIR%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses >nul
  "%SDKDIR%\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
)

set ANDROID_HOME=%SDKDIR%
set ANDROID_SDK_ROOT=%SDKDIR%
cd /d "%~dp0ClassPulse"
call gradle assembleDebug --no-daemon
echo.
echo APK: %CD%\app\build\outputs\apk\debug\app-debug.apk
