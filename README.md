# Java Bonjour Browser

A cross-platform Java Swing application for browsing and displaying Bonjour/Zeroconf services on a local network.

## Overview

Java Bonjour Browser provides functionality similar to the native Mac OS X Bonjour Browser, but runs on multiple platforms (Windows, macOS, Linux, BSD, Solaris). It discovers and displays all Bonjour-enabled services on your network in a hierarchical tree structure.

### Features

- Tree-based visualization of network services
- Real-time service discovery and removal
- Service resolution with detailed information (hostname, port, TXT records)
- Cross-platform compatibility
- "Reload Services" button to refresh the service list

## Prerequisites

### Java Development Kit (JDK)

Any JDK version 8 or later. Recommended options:

| JDK | Download |
|-----|----------|
| Eclipse Temurin | https://adoptium.net/ |
| Oracle JDK | https://www.oracle.com/java/technologies/downloads/ |
| Amazon Corretto | https://aws.amazon.com/corretto/ |

Verify installation:
```bash
java -version
javac -version
```

### Bonjour Service

| Platform | Installation |
|----------|-------------|
| Windows | Install [Bonjour Print Services](https://support.apple.com/kb/DL999) or iTunes |
| macOS | Built-in (no installation needed) |
| Linux | Install `avahi-daemon` with Bonjour compatibility layer |

## Project Structure

```
bonjour/
├── src/                          # Java source files
│   ├── BonjourBrowser.java       # Main GUI (JFrame)
│   ├── BonjourBrowserImpl.java   # Core logic, tree management
│   ├── BonjourBrowserInterface.java
│   ├── BonjourBrowserElement.java
│   ├── BonjourBrowserMultiServiceListener.java
│   ├── BonjourBrowserSingleServiceListener.java
│   └── TestRegister.java         # Test utility
├── jars/                         # Dependencies
│   └── dns_sd.jar                # Apple Bonjour/DNSSD API
├── resources/                    # Icons and resources
├── javadoc/                      # Generated API documentation
│
├── # IDE Project Files
├── .idea/                        # IntelliJ IDEA
├── BonjourBrowser.iml            # IntelliJ module
├── nbproject/                    # NetBeans
├── build.xml                     # Ant build file
└── manifest.mf                   # JAR manifest
```

## Building and Running

### Using IntelliJ IDEA (Recommended)

1. Open IntelliJ IDEA
2. File → Open → select `bonjour` folder
3. Configure JDK if prompted (File → Project Structure → SDK)
4. Build: **Ctrl+F9** (or Build → Build Project)
5. Run: **Shift+F10** (or select "BonjourBrowser" and click Run)

### Using NetBeans

1. Open NetBeans
2. File → Open Project → select `bonjour` folder
3. Build: **F11** (or right-click → Build)
4. Run: **F6** (or right-click → Run)

### Using Apache Ant (Command Line)

```bash
cd bonjour
ant clean      # Clean build files
ant compile    # Compile sources
ant jar        # Build JAR in dist/
ant run        # Run the application
ant javadoc    # Generate documentation
```

### Using javac/java Directly

```bash
cd bonjour

# Compile (Windows)
javac -cp "jars/dns_sd.jar" -d build/classes src/*.java

# Compile (macOS/Linux)
javac -cp "jars/dns_sd.jar" -d build/classes src/*.java

# Run (Windows)
java -cp "build/classes;jars/dns_sd.jar" BonjourBrowser

# Run (macOS/Linux)
java -cp "build/classes:jars/dns_sd.jar" BonjourBrowser
```

## Run Configurations

| Configuration | Main Class | Description |
|--------------|------------|-------------|
| BonjourBrowser | `BonjourBrowser` | Main application |
| TestRegister | `TestRegister` | Register a test service for debugging |

## Architecture

The application follows an MVC-inspired pattern:

- **UI Layer**: `BonjourBrowser` - JFrame with JTree for service display
- **Logic Layer**: `BonjourBrowserImpl` - Tree management and service subscription
- **Listener Layer**: `BonjourBrowserMultiServiceListener`, `BonjourBrowserSingleServiceListener`
- **Data Model**: `BonjourBrowserElement` - Service metadata container

### Service Discovery Flow

1. Application starts and browses `_services._dns-sd._udp.`
2. Bonjour returns service types (e.g., `_http._tcp`, `_ssh._tcp`)
3. Service types are added to the tree with placeholder children
4. When user expands a node, the specific service type is browsed
5. Individual services are discovered and resolved for details

## Dependencies

| Library | Purpose |
|---------|---------|
| `dns_sd.jar` | Apple DNSSD/Bonjour API for service discovery |

## License

GNU Lesser General Public License v2.1 or later (LGPL-2.1+)

See [copyright.txt](copyright.txt) for full license text.

## Authors

- Denis Abramov (dabramov@optonline.net)
- Myounghwan Lee (ml2483@columbia.edu)

Internet Real Time Lab, Columbia University (2006)

## Additional Resources

- [Project Report](report/project.htm) - Detailed project documentation
- [JavaDoc](bonjour/javadoc/) - API documentation
