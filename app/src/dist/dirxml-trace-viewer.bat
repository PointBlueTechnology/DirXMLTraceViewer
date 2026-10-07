@echo off
rem DirXML Trace Viewer launcher for Windows.
rem
rem Environment:
rem   JAVA_HOME   Java 21 or later to use (otherwise "java" on the PATH)
rem   JAVA_OPTS   JVM options; default -Xmx2g. Raise it to load very large trace files.
rem
rem Arguments are passed to the viewer, e.g. --demo.
rem Double-click to start without a console window; run with --console to see output.

setlocal EnableDelayedExpansion
set "MIN_JAVA=21"
set "JAR=%~dp0dirxml-trace-viewer.jar"

if not exist "%JAR%" (
    echo Cannot find "%JAR%"
    pause
    exit /b 1
)

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVA (
    where java >nul 2>&1
    if errorlevel 1 (
        echo Java %MIN_JAVA% or later is required. Install it or set JAVA_HOME.
        pause
        exit /b 1
    )
    set "JAVA=java"
    set "JAVAW=javaw"
)

rem "java -version" prints e.g.: openjdk version "21.0.4" 2024-07-16  (or "1.8.0_402" for Java 8)
set "VERSION="
for /f "tokens=3" %%v in ('"!JAVA!" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined VERSION set "VERSION=%%~v"
)
for /f "tokens=1,2 delims=._-+" %%a in ("!VERSION!") do (
    set "MAJOR=%%a"
    if "%%a"=="1" set "MAJOR=%%b"
)
if not defined MAJOR set "MAJOR=0"
if !MAJOR! LSS %MIN_JAVA% (
    echo Java %MIN_JAVA% or later is required; found !VERSION! at !JAVA!.
    echo Install a newer Java or point JAVA_HOME at one.
    pause
    exit /b 1
)

if not defined JAVA_OPTS set "JAVA_OPTS=-Xmx2g"

set "CONSOLE="
set "ARGS="
for %%a in (%*) do (
    if /i "%%~a"=="--console" (set "CONSOLE=1") else (set ARGS=!ARGS! %%a)
)

if defined CONSOLE (
    "!JAVA!" %JAVA_OPTS% --enable-native-access=ALL-UNNAMED -jar "%JAR%" !ARGS!
) else (
    start "DirXML Trace Viewer" "!JAVAW!" %JAVA_OPTS% --enable-native-access=ALL-UNNAMED -jar "%JAR%" !ARGS!
)
endlocal
