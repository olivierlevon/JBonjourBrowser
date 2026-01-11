# Java Bonjour Browser

A cross-platform Java Swing application for browsing and displaying Bonjour/Zeroconf services on a local network.

## Overview

Java Bonjour Browser provides functionality similar to the native Mac OS X Bonjour Browser, but runs on multiple platforms (Windows, macOS, Linux, BSD, Solaris). It discovers and displays all Bonjour-enabled services on your network in a hierarchical tree structure.

### Features

- **Service Discovery**: Automatically discovers all Bonjour service types on the network
- **Tree Visualization**: Hierarchical display of domains, service types, and instances
- **Service Resolution**: Shows hostname, port, and TXT record details for each service
- **Real-time Updates**: Services appear/disappear as they come online/offline
- **Cross-platform**: Runs on any platform with Java 8+ and Bonjour support
- **Reload Button**: Manually refresh the service list

## Screenshots

```
Bonjour Service Browser
├── local.
│   ├── _http._tcp.
│   │   ├── My Web Server
│   │   │   ├── myserver.local.:80
│   │   │   └── path=/index.html
│   │   └── Another Server
│   ├── _ssh._tcp.
│   │   └── SSH Service
│   └── _printer._tcp.
│       └── Office Printer
```

## Prerequisites

### Java Development Kit (JDK)

Any JDK version 8 or later:

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
├── src/                              # Java source files
│   ├── BonjourBrowser.java           # Main GUI application (JFrame)
│   ├── BonjourBrowserImpl.java       # Core implementation (tree management)
│   ├── BonjourBrowserInterface.java  # Interface for browser operations
│   ├── BonjourBrowserElement.java    # Data transfer object for services
│   ├── BonjourBrowserMultiServiceListener.java   # Meta-query listener
│   ├── BonjourBrowserSingleServiceListener.java  # Service instance listener
│   └── TestRegister.java             # Test utility for registering services
│
├── jars/                             # Dependencies
│   └── dns_sd.jar                    # Apple DNSSD/Bonjour Java API
│
├── lib/                              # Native libraries
│   └── (platform-specific files)     # DNSSD native bindings
│
├── resources/                        # Icons and resources
├── javadoc/                          # Generated API documentation
│
├── .idea/                            # IntelliJ IDEA project files
├── JBonjourBrowser.iml               # IntelliJ module configuration
├── nbproject/                        # NetBeans project files
├── build.xml                         # Ant build script
└── manifest.mf                       # JAR manifest
```

## Building and Running

### Using IntelliJ IDEA (Recommended)

1. Open IntelliJ IDEA
2. **File** → **Open** → select `bonjour` folder
3. Configure JDK if prompted (**File** → **Project Structure** → **SDK**)
4. Build: **Ctrl+F9** (or **Build** → **Build Project**)
5. Run: **Shift+F10** (or select `BonjourBrowser` configuration and click **Run**)

### Using NetBeans

1. Open NetBeans
2. **File** → **Open Project** → select `bonjour` folder
3. Build: **F11** (or right-click → **Build**)
4. Run: **F6** (or right-click → **Run**)

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

# Create build directory
mkdir -p build/classes

# Compile (Windows)
javac -cp "jars/dns_sd.jar" -d build/classes src/*.java

# Compile (macOS/Linux)
javac -cp "jars/dns_sd.jar" -d build/classes src/*.java

# Run (Windows) - requires native library path
java -Djava.library.path=lib -cp "build/classes;jars/dns_sd.jar" BonjourBrowser

# Run (macOS/Linux)
java -Djava.library.path=lib -cp "build/classes:jars/dns_sd.jar" BonjourBrowser
```

## Run Configurations

| Configuration | Main Class | Description |
|---------------|------------|-------------|
| BonjourBrowser | `BonjourBrowser` | Main browser application |
| TestRegister | `TestRegister` | Register a test service for debugging |

### TestRegister Usage

```bash
# Register a test HTTP service for 5 minutes
java -cp "..." TestRegister "My Test Service" "_http._tcp." 5

# Register with specific port
java -cp "..." TestRegister "My Service" "_http._tcp." -p 8080 10

# Register in specific domain
java -cp "..." TestRegister "My Service" "_http._tcp." -D example.local. 5
```

## Architecture

The application follows an event-driven architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────┐
│                        BonjourBrowser                           │
│                     (Main JFrame / UI)                          │
├─────────────────────────────────────────────────────────────────┤
│                     BonjourBrowserImpl                          │
│          (Tree Management / Service Subscriptions)              │
│                implements BonjourBrowserInterface               │
├─────────────────────────────────────────────────────────────────┤
│    BonjourBrowserMultiServiceListener    │    BonjourBrowser    │
│         (Meta-query: discovers           │  SingleServiceListener│
│          service TYPES)                  │  (Discovers & resolves│
│                                          │   service INSTANCES)  │
├─────────────────────────────────────────────────────────────────┤
│                    BonjourBrowserElement                        │
│                  (Immutable Data Transfer Object)               │
├─────────────────────────────────────────────────────────────────┤
│                      Apple DNSSD API                            │
│                      (dns_sd.jar)                               │
└─────────────────────────────────────────────────────────────────┘
```

### Components

| Component | Responsibility |
|-----------|---------------|
| `BonjourBrowser` | Main JFrame, UI setup, window management |
| `BonjourBrowserImpl` | JTree management, EDT dispatching, service subscriptions |
| `BonjourBrowserInterface` | Contract for UI operations (add/remove/resolve nodes) |
| `BonjourBrowserElement` | Immutable DTO carrying service information |
| `BonjourBrowserMultiServiceListener` | Listens for service types via meta-query |
| `BonjourBrowserSingleServiceListener` | Listens for and resolves individual services |

### Service Discovery Flow

```
1. Application starts
   └── BonjourBrowserMultiServiceListener browses "_services._dns-sd._udp."

2. Meta-query returns service types (e.g., "_http._tcp", "_ssh._tcp")
   └── Each type is added to the tree under its domain

3. User expands a service type node
   └── BonjourBrowserImpl.subscribe() starts browsing that type
   └── BonjourBrowserSingleServiceListener receives callbacks

4. Individual services are discovered
   └── serviceFound() creates a resolver for each service
   └── serviceResolved() adds hostname:port and TXT records to tree

5. Services go offline
   └── serviceLost() removes the service from tree
```

### Thread Safety

The application is designed to be thread-safe:

- **DNSSD callbacks** arrive on background threads
- **Swing operations** are dispatched to EDT via `SwingUtilities.invokeLater()`
- **Shared state** uses `volatile` fields and `ConcurrentHashMap`
- **Resource cleanup** uses synchronized methods

## Dependencies

| Library | Purpose |
|---------|---------|
| `dns_sd.jar` | Apple DNSSD/Bonjour Java API for mDNS service discovery |

**Note**: The native DNSSD library must be available on the system (provided by Bonjour/Avahi).

## Troubleshooting

### "UnsatisfiedLinkError: no dns_sd in java.library.path"

The native DNSSD library is not found. Solutions:
- **Windows**: Ensure Bonjour is installed
- **Linux**: Install `libavahi-compat-libdnssd1`
- Add the native library path: `-Djava.library.path=/path/to/lib`

### "DNSSDException: NO_SUCH_NAME"

The Bonjour service is not running:
- **Windows**: Start "Bonjour Service" in Services
- **Linux**: Start `avahi-daemon`
- **macOS**: Should be running by default

### No services appearing

- Check that other Bonjour services exist on your network
- Use `TestRegister` to create a test service
- Verify firewall allows mDNS (UDP port 5353)

## License

GNU Lesser General Public License v2.1 or later (LGPL-2.1+)

See [copyright.txt](copyright.txt) for full license text.

## Authors

- Denis Abramov (dabramov@optonline.net)
- Myounghwan Lee (ml2483@columbia.edu)

Internet Real Time Lab, Columbia University (2006)

## Additional Resources

- [Project Report](report/project.htm) - Detailed project documentation
- [JavaDoc](bonjour/javadoc/) - Generated API documentation
- [Apple DNSSD Documentation](https://developer.apple.com/documentation/dnssd)
