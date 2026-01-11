#!/bin/bash
# ============================================================================
# Build script for TestRegister utility
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=================================================="
echo "Building TestRegister"
echo "=================================================="
echo

# Create build directory if it doesn't exist
mkdir -p build/classes

# Compile TestRegister.java only
echo "Compiling TestRegister.java..."
javac -cp "jars/dns_sd.jar" -d build/classes src/TestRegister.java

if [ $? -ne 0 ]; then
    echo
    echo "BUILD FAILED!"
    exit 1
fi

echo
echo "BUILD SUCCESSFUL!"
echo
echo "To run: ./run-testregister.sh [options]"
echo "For help: ./run-testregister.sh --help"
