@echo off
setlocal enabledelayedexpansion
title Edge TTS - Standalone Android APK Builder
echo.
echo  =======================================================
echo   Edge TTS - Automated Standalone Android APK Builder
echo  =======================================================
echo.

set "PROJECT_DIR=%~dp0"
set "ANDROID_DIR=%PROJECT_DIR%android"
set "TOOLS_DIR=%PROJECT_DIR%build_tools"

if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"

:: ── Step 1: Portable JDK ──────────────────────────────────────────
echo [1/4] Checking for Java JDK...
set "JDK_DIR=%TOOLS_DIR%\jdk-17.0.2"
if not exist "%JDK_DIR%\bin\java.exe" (
    echo      Downloading Portable JDK 17 ^(This will take a moment^)...
    powershell -Command "$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri 'https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_windows-x64_bin.zip' -OutFile '%TOOLS_DIR%\jdk.zip'"
    echo      Extracting JDK...
    powershell -Command "Expand-Archive -Path '%TOOLS_DIR%\jdk.zip' -DestinationPath '%TOOLS_DIR%' -Force; Remove-Item '%TOOLS_DIR%\jdk.zip' -Force"
)
set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: ── Step 2: Portable Android SDK ──────────────────────────────────
echo [2/4] Checking Android SDK Tools...
set "SDK_ROOT=%TOOLS_DIR%\android_sdk"
set "CMDLINE_TOOLS=%SDK_ROOT%\cmdline-tools\latest"

if not exist "%CMDLINE_TOOLS%\bin\sdkmanager.bat" (
    echo      Downloading Android Command Line Tools...
    if not exist "%SDK_ROOT%" mkdir "%SDK_ROOT%"
    powershell -Command "$ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile '%TOOLS_DIR%\cmdline-tools.zip'"
    echo      Extracting Android Command Line Tools...
    powershell -Command "Expand-Archive -Path '%TOOLS_DIR%\cmdline-tools.zip' -DestinationPath '%SDK_ROOT%\cmdline-tools-temp' -Force"
    powershell -Command "New-Item -ItemType Directory -Path '%CMDLINE_TOOLS%' -Force | Out-Null; Copy-Item -Path '%SDK_ROOT%\cmdline-tools-temp\cmdline-tools\*' -Destination '%CMDLINE_TOOLS%' -Recurse -Force; Remove-Item '%SDK_ROOT%\cmdline-tools-temp' -Recurse -Force; Remove-Item '%TOOLS_DIR%\cmdline-tools.zip' -Force"
)

:: ── Step 3: Install Android Components & Accept Licenses ──────────
echo [3/4] Preparing Android Build Components...

:: Write licenses automatically to bypass interactive prompts
set "SDK_LICENSE_DIR=%SDK_ROOT%\licenses"
if not exist "%SDK_LICENSE_DIR%" mkdir "%SDK_LICENSE_DIR%"
(
echo 24333f8a63b6825ea9c5514f83c2829b004d1fee
echo 84831b9409646a918e30573bab4c9c91346d8abd
echo d56f5187479451eabf01fb78af6dfcb131a6481e
) > "%SDK_LICENSE_DIR%\android-sdk-license"
echo d975f751698a77e662f1cd747e1d8d0cddb43836 > "%SDK_LICENSE_DIR%\android-sdk-arm-dbt-license"

:: Install necessary platform and build tools using sdkmanager
echo      Verifying Platform 34 and Build-Tools...
echo y | call "%CMDLINE_TOOLS%\bin\sdkmanager.bat" --sdk_root="%SDK_ROOT%" "platforms;android-34" "build-tools;34.0.0" >nul

:: Setup local.properties so Gradle knows where the SDK is
set "SDK_ROOT_FORWARD_SLASH=%SDK_ROOT:\=/%"
echo sdk.dir=%SDK_ROOT_FORWARD_SLASH%> "%ANDROID_DIR%\local.properties"

:: ── Step 4: Build APK ─────────────────────────────────────────────
echo [4/4] Building Android APK via Gradle...
echo       ^(This may take a few minutes as Gradle downloads dependencies^)
echo.

cd /d "%ANDROID_DIR%"
call gradlew.bat assembleDebug --no-daemon

if errorlevel 1 (
    echo.
    echo  =======================================================
    echo   BUILD FAILED
    echo  =======================================================
    echo  Please check the error output above.
    pause
    exit /b 1
)

echo.
echo  =======================================================
echo   BUILD SUCCESSFUL
echo  =======================================================
echo.

:: Extract the built APK to the project root
set "APK_SRC=app\build\outputs\apk\debug\app-debug.apk"
set "APK_DST=%PROJECT_DIR%EdgeTTS.apk"
if exist "%APK_SRC%" (
    copy /y "%APK_SRC%" "%APK_DST%" >nul
    echo  ✅ Application built successfully!
    echo  You can find the ready-to-install file here:
    echo  %APK_DST%
    echo.
    echo  Instructions:
    echo  1. Copy EdgeTTS.apk to your Android device.
    echo  2. Install the APK ^(you may need to allow "Unknown Sources"^).
    echo  3. Open Android Settings -^> Accessibility -^> Text-to-speech output.
    echo  4. Set "Edge TTS" as your preferred engine.
) else (
    echo  ✅ Build finished, but couldn't copy the APK to the root folder.
    echo  Check inside: android\app\build\outputs\apk\debug\
)

echo.
pause
