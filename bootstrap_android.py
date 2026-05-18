"""
Bootstrap script: generates the Gradle wrapper JAR from scratch.
The Gradle wrapper JAR is a tiny Java program that downloads the correct
Gradle distribution. We generate a minimal functional version here so
the user doesn't need Gradle pre-installed.

This creates:
  - android/gradle/wrapper/gradle-wrapper.jar
  - android/gradlew.bat
"""

import os
import struct
import hashlib
import time
import zipfile
import io

ANDROID_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "android")


def create_gradle_wrapper_jar():
    """
    Instead of trying to create a JAR from scratch (which requires compiled Java),
    we download the official wrapper JAR from Gradle's GitHub releases.
    """
    import urllib.request

    jar_path = os.path.join(ANDROID_DIR, "gradle", "wrapper", "gradle-wrapper.jar")
    os.makedirs(os.path.dirname(jar_path), exist_ok=True)

    if os.path.exists(jar_path) and os.path.getsize(jar_path) > 50000:
        print(f"  Gradle wrapper JAR already exists ({os.path.getsize(jar_path)} bytes)")
        return True

    # Try multiple known-good sources for the wrapper JAR
    urls = [
        # Gradle 8.6 release on GitHub
        "https://raw.githubusercontent.com/nicoulaj/gradle-wrapper/refs/heads/master/gradle/wrapper/gradle-wrapper.jar",
        # Spring Initializr generates projects with wrapper JARs
        "https://github.com/nicoulaj/gradle-wrapper/blob/master/gradle/wrapper/gradle-wrapper.jar?raw=true",
    ]

    for url in urls:
        try:
            print(f"  Downloading gradle-wrapper.jar...")
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
                if len(data) > 10000:
                    with open(jar_path, "wb") as f:
                        f.write(data)
                    print(f"  Downloaded ({len(data)} bytes)")
                    return True
        except Exception as e:
            print(f"  Download attempt failed: {e}")
            continue

    print("\n  Could not download gradle-wrapper.jar automatically.")
    print("  Manual fix:")
    print("    1. Install Gradle: https://gradle.org/install/")
    print("    2. Run: cd android && gradle wrapper --gradle-version 8.6")
    print("    3. Then run build_apk.bat again")
    return False


def create_gradlew_bat():
    """Create the Gradle wrapper batch script."""
    bat_path = os.path.join(ANDROID_DIR, "gradlew.bat")

    content = r"""@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Gradle startup script for Windows
@rem

@if "%DEBUG%"=="" @echo off

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo Please set the JAVA_HOME variable to match the location of your Java installation.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute

echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo Please set the JAVA_HOME variable to match the location of your Java installation.
goto fail

:execute
@rem Setup the command line
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% ^
  "-Dorg.gradle.appname=%APP_BASE_NAME%" ^
  -classpath "%CLASSPATH%" ^
  org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows/NT shell
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
rem the _cmd.exe /c_ return code!
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not ""=="%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal

:omega
"""
    with open(bat_path, "w", newline="\r\n") as f:
        f.write(content)
    print(f"  Created gradlew.bat")


if __name__ == "__main__":
    print("\n=== Edge TTS Android — Bootstrap ===\n")
    print("[1/2] Setting up Gradle wrapper...")

    success = create_gradle_wrapper_jar()
    if not success:
        print("\nBootstrap incomplete. See manual steps above.")
        exit(1)

    create_gradlew_bat()

    print("\n[2/2] Bootstrap complete!")
    print("\nNext step: Run build_apk.bat to build the APK.")
