@echo off
setlocal
set "ROOT=%~dp0"
if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME if exist "%USERPROFILE%\AppData\Local\Android\Sdk" set "ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined ANDROID_HOME (
  echo Android SDK nebyl nalezen. Otevri projekt v Android Studiu a nainstaluj SDK Platform 35 + Build Tools.
  pause
  exit /b 1
)
where gradle >nul 2>nul
if errorlevel 1 (
  echo Globalni Gradle nebyl nalezen. Otevri projekt v Android Studiu a pouzij Gradle wrapper, nebo nainstaluj Gradle.
  pause
  exit /b 1
)
gradle assembleDebug
if errorlevel 1 exit /b 1
copy /Y app\build\outputs\apk\debug\app-debug.apk TronLocal-debug.apk >nul
echo Hotovo: %ROOT%TronLocal-debug.apk
endlocal
