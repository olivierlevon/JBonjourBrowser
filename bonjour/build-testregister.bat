@echo off
REM ============================================================================
REM Build script for TestRegister utility
REM ============================================================================

setlocal

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

echo ==================================================
echo Building TestRegister
echo ==================================================
echo.

REM Create build directory if it doesn't exist
if not exist "build\classes" mkdir build\classes

REM Compile TestRegister.java only
echo Compiling TestRegister.java...
javac -cp "jars\dns_sd.jar" -d build\classes src\TestRegister.java

if %ERRORLEVEL% neq 0 (
    echo.
    echo BUILD FAILED!
    exit /b 1
)

echo.
echo BUILD SUCCESSFUL!
echo.
echo To run: run-testregister.bat [options]
echo For help: run-testregister.bat --help

endlocal
