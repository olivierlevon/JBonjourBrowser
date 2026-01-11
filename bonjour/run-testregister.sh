#!/bin/bash
# ============================================================================
# Run script for TestRegister utility
# ============================================================================
#
# Usage: ./run-testregister.sh [options]
#
# Options:
#   -n, --name <name>       Service name (default: "Test Service")
#   -t, --type <type>       Service type (default: "_http._tcp")
#   -p, --port <port>       Port number (default: auto-assigned)
#   -d, --duration <mins>   Duration in minutes (default: 10, 0 = infinite)
#   -h, --help              Show help message
#
# Examples:
#   ./run-testregister.sh
#   ./run-testregister.sh -n "My Web Server" -t _http._tcp -p 8080
#   ./run-testregister.sh --name "SSH Server" --type _ssh._tcp --duration 0
#
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Set library path for native DLLs
export LD_LIBRARY_PATH="$SCRIPT_DIR/lib:$LD_LIBRARY_PATH"
export DYLD_LIBRARY_PATH="$SCRIPT_DIR/lib:$DYLD_LIBRARY_PATH"
export PATH="$SCRIPT_DIR/lib:$PATH"

# Check if compiled
if [ ! -f "build/classes/TestRegister.class" ]; then
    echo "TestRegister not compiled. Building..."
    ./build-testregister.sh
    if [ $? -ne 0 ]; then
        exit 1
    fi
    echo
fi

# Run TestRegister with all arguments passed through
java -Djava.library.path=lib -cp "build/classes:jars/dns_sd.jar" TestRegister "$@"
