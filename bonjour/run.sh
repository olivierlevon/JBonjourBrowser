#!/bin/bash
# JBonjourBrowser Launcher
# Sets up library path and runs the application

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export LD_LIBRARY_PATH="$SCRIPT_DIR/lib:$LD_LIBRARY_PATH"
export PATH="$SCRIPT_DIR/lib:$PATH"

cd "$SCRIPT_DIR"
java -Djava.library.path=lib -cp "build/classes:jars/dns_sd.jar" BonjourBrowser
