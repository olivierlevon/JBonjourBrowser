@echo off
REM ============================================================================
REM Run script for TestRegister utility
REM ============================================================================
REM
REM Usage: run-testregister.bat [options]
REM
REM Options:
REM   -n, --name <name>       Service name (default: "Test Service")
REM   -t, --type <type>       Service type (default: "_http._tcp")
REM   -p, --port <port>       Port number (default: auto-assigned)
REM   -d, --duration <mins>   Duration in minutes (default: 10, 0 = infinite)
REM   -h, --help              Show help message
REM
REM Examples:
REM   run-testregister.bat
REM   run-testregister.bat -n "My Web Server" -t _http._tcp -p 8080
REM   run-testregister.bat --name "SSH Server" --type _ssh._tcp --duration 0
REM
REM ============================================================================

setlocal

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

REM Add lib folder to PATH for native DLLs
set PATH=%SCRIPT_DIR%lib;%PATH%

REM Check if compiled
if not exist "build\classes\TestRegister.class" (
    echo TestRegister not compiled. Building...
    call build-testregister.bat
    if %ERRORLEVEL% neq 0 exit /b 1
    echo.
)

REM Run TestRegister with all arguments passed through
java -Djava.library.path=lib -cp "build\classes;jars\dns_sd.jar" TestRegister %*

endlocal
