@echo off
REM JBonjourBrowser Launcher
REM Sets up PATH for native DLLs and runs the application

set PATH=%~dp0lib;%PATH%
cd /d "%~dp0"

java -Djava.library.path=lib -cp "build/classes;jars/dns_sd.jar;jars/jbcl.jar" BonjourBrowser

pause
