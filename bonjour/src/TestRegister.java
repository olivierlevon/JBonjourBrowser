import java.net.*;
import java.io.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.apple.dnssd.*;

/**
 * Test utility for registering a sample Bonjour service.
 * Useful for testing service discovery with the BonjourBrowser application.
 *
 * <p>Usage: java TestRegister [options]</p>
 * <ul>
 *   <li>-n, --name &lt;name&gt;       Service name (default: "Test Service")</li>
 *   <li>-t, --type &lt;type&gt;       Service type (default: "_http._tcp.")</li>
 *   <li>-D, --domain &lt;domain&gt;   Registration domain (default: "local.")</li>
 *   <li>-p, --port &lt;port&gt;       Port number (default: auto-assigned)</li>
 *   <li>-d, --duration &lt;mins&gt;   Duration in minutes (default: 10, 0 = infinite)</li>
 *   <li>-h, --help              Show this help message</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe. The stop() method can be called
 * from any thread, including shutdown hooks.</p>
 */
public class TestRegister implements RegisterListener {

    private static final String DEFAULT_SERVICE_NAME = "Test Service";
    private static final String DEFAULT_SERVICE_TYPE = "_http._tcp.";
    private static final String DEFAULT_DOMAIN = "local.";
    private static final int DEFAULT_DURATION_MINUTES = 10;
    private static final String SEPARATOR_LINE = "==================================================";
    private static final int REGISTRATION_TIMEOUT_SECONDS = 10;

    private final String serviceName;
    private final String serviceType;
    private final String domain;
    private final int port;
    private final long durationMs;

    // Thread-safe state management
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final CountDownLatch registrationLatch = new CountDownLatch(1);
    private final Object lock = new Object();

    // Protected by 'lock'
    private DNSSDRegistration registration;
    private ServerSocket serverSocket;
    private volatile String registrationError = null;
    private volatile Thread mainThread;
    private volatile Thread shutdownHook;

    /**
     * Creates a new TestRegister instance.
     * @param serviceName the name of the service to register (must not be null)
     * @param serviceType the type of the service (e.g., "_http._tcp.", must not be null)
     * @param domain the registration domain (e.g., "local.", null for default)
     * @param port the port number (0 for auto-assign)
     * @param durationMinutes duration in minutes (0 for infinite)
     * @throws IllegalArgumentException if serviceName or serviceType is null or empty
     */
    public TestRegister(String serviceName, String serviceType, String domain, int port, int durationMinutes) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("Service name must not be null or empty");
        }
        if (serviceType == null || serviceType.trim().isEmpty()) {
            throw new IllegalArgumentException("Service type must not be null or empty");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        if (durationMinutes < 0) {
            throw new IllegalArgumentException("Duration must be >= 0");
        }

        this.serviceName = serviceName;
        this.serviceType = serviceType;
        this.domain = domain;
        this.port = port;
        this.durationMs = (durationMinutes <= 0) ? Long.MAX_VALUE : durationMinutes * 60000L;
    }

    /**
     * Called when a registration operation fails.
     * @param service DNSSDService instance
     * @param errorCode the error code
     */
    @Override
    public void operationFailed(DNSSDService service, int errorCode) {
        registrationError = "Registration failed with error code: " + errorCode;
        System.err.println(registrationError);
        registrationLatch.countDown();

        // Interrupt main thread to stop waiting
        Thread main = mainThread;
        if (main != null) {
            main.interrupt();
        }
    }

    /**
     * Called when a service is successfully registered.
     * @param registration DNSSDRegistration instance
     * @param flags flags for use
     * @param serviceName the registered service name
     * @param regType the service type
     * @param domain the registration domain
     */
    @Override
    public void serviceRegistered(DNSSDRegistration registration, int flags,
                                  String serviceName, String regType, String domain) {
        // Get port safely
        int localPort;
        synchronized (lock) {
            if (serverSocket != null) {
                localPort = serverSocket.getLocalPort();
            } else {
                localPort = -1;
            }
        }

        System.out.println("Service Registered Successfully!");
        System.out.println("  Name  : " + serviceName);
        System.out.println("  Type  : " + regType);
        System.out.println("  Domain: " + domain);
        System.out.println("  Port  : " + localPort);

        registrationLatch.countDown();
    }

    /**
     * Starts the service registration and keeps it running.
     * This method blocks until the duration expires or stop() is called.
     *
     * @throws DNSSDException if DNSSD registration call fails
     * @throws IOException if server socket creation fails
     * @throws IllegalStateException if start() has already been called
     * @throws RuntimeException if registration callback reports an error
     */
    public void start() throws DNSSDException, IOException {
        // Prevent multiple start() calls
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("start() has already been called");
        }

        mainThread = Thread.currentThread();

        // Create server socket
        ServerSocket socket = new ServerSocket(port);
        int actualPort;

        synchronized (lock) {
            serverSocket = socket;
            actualPort = socket.getLocalPort();
        }

        System.out.println(SEPARATOR_LINE);
        System.out.println("TestRegister - Bonjour Service Registration Utility");
        System.out.println(SEPARATOR_LINE);
        System.out.println();
        System.out.println("Registering service...");
        System.out.println("  Name    : " + serviceName);
        System.out.println("  Type    : " + serviceType);
        System.out.println("  Domain  : " + (domain != null ? domain : "(default)"));
        System.out.println("  Port    : " + actualPort);
        if (durationMs == Long.MAX_VALUE) {
            System.out.println("  Duration: infinite (press Ctrl+C to stop)");
        } else {
            System.out.println("  Duration: " + (durationMs / 60000) + " minutes");
        }
        System.out.println();

        // Create TXT record with service metadata
        TXTRecord txtRecord = new TXTRecord();
        txtRecord.set("txtvers", "1");
        txtRecord.set("status", "available");
        txtRecord.set("path", "/");

        System.out.println("TXT Record:");
        for (int i = 0; i < txtRecord.size(); i++) {
            String key = txtRecord.getKey(i);
            String value = txtRecord.getValueAsString(i);
            if (key != null && !key.isEmpty()) {
                System.out.println("  " + key + "=" + value);
            }
        }
        System.out.println();

        // Register the service - if this fails, clean up the socket
        try {
            synchronized (lock) {
                registration = DNSSD.register(0, DNSSD.ALL_INTERFACES,
                        serviceName, serviceType, domain,
                        null, actualPort,
                        txtRecord, this);
            }
        } catch (DNSSDException e) {
            // Clean up socket on registration failure
            closeSocketQuietly();
            throw e;
        }

        // Install shutdown hook for graceful cleanup
        shutdownHook = new Thread(() -> {
            stop();
        }, "TestRegister-ShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // Wait for registration callback with timeout
        try {
            if (!registrationLatch.await(REGISTRATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                System.err.println("Warning: Registration callback not received within " +
                        REGISTRATION_TIMEOUT_SECONDS + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            return;
        }

        // Check if registration failed
        if (registrationError != null) {
            stop();
            throw new RuntimeException(registrationError);
        }

        System.out.println();
        System.out.println("Service is now discoverable. Press Ctrl+C to stop.");
        System.out.println();

        // Wait for the specified duration or until interrupted
        long startTime = System.currentTimeMillis();
        long endTime = (durationMs == Long.MAX_VALUE) ? Long.MAX_VALUE : startTime + durationMs;
        int lastPrintedMinute = -1;

        while (!stopped.get() && System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(1000);

                // Print remaining time when minute changes
                if (durationMs != Long.MAX_VALUE) {
                    long remaining = endTime - System.currentTimeMillis();
                    if (remaining > 0) {
                        int currentMinute = (int) (remaining / 60000);
                        if (currentMinute != lastPrintedMinute && currentMinute > 0) {
                            System.out.println("Time remaining: " + currentMinute + " minute" +
                                    (currentMinute != 1 ? "s" : ""));
                            lastPrintedMinute = currentMinute;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        stop();
    }

    /**
     * Stops the service registration and releases resources.
     * This method is thread-safe and can be called multiple times.
     */
    public void stop() {
        // Atomic check-and-set to prevent double cleanup
        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        // Remove shutdown hook if we're not being called from it
        Thread hook = shutdownHook;
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // JVM is already shutting down, ignore
            }
            shutdownHook = null;
        }

        System.out.println();
        System.out.println("Stopping service registration...");

        // Stop registration
        synchronized (lock) {
            if (registration != null) {
                try {
                    registration.stop();
                    System.out.println("Service unregistered.");
                } catch (Exception e) {
                    System.err.println("Error stopping registration: " + e.getMessage());
                }
                registration = null;
            }
        }

        // Close socket
        closeSocketQuietly();

        System.out.println("Done.");
    }

    /**
     * Closes the server socket quietly, ignoring exceptions.
     */
    private void closeSocketQuietly() {
        synchronized (lock) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                    System.out.println("Server socket closed.");
                } catch (IOException e) {
                    System.err.println("Error closing socket: " + e.getMessage());
                }
            }
            serverSocket = null;
        }
    }

    /**
     * Prints usage information.
     */
    private static void printUsage() {
        System.out.println("TestRegister - Bonjour Service Registration Utility");
        System.out.println();
        System.out.println("Usage: java TestRegister [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -n, --name <name>       Service name (default: \"" + DEFAULT_SERVICE_NAME + "\")");
        System.out.println("  -t, --type <type>       Service type (default: \"" + DEFAULT_SERVICE_TYPE + "\")");
        System.out.println("  -D, --domain <domain>   Registration domain (default: \"" + DEFAULT_DOMAIN + "\")");
        System.out.println("  -p, --port <port>       Port number (default: auto-assigned)");
        System.out.println("  -d, --duration <mins>   Duration in minutes (default: " + DEFAULT_DURATION_MINUTES + ", 0 = infinite)");
        System.out.println("  -h, --help              Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java TestRegister");
        System.out.println("  java TestRegister -n \"My Web Server\" -t _http._tcp -p 8080");
        System.out.println("  java TestRegister --name \"SSH Server\" --type _ssh._tcp --duration 0");
        System.out.println("  java TestRegister -n \"Test\" -D local. -p 9000");
        System.out.println();
        System.out.println("Common service types:");
        System.out.println("  _http._tcp        Web server");
        System.out.println("  _https._tcp       Secure web server");
        System.out.println("  _ssh._tcp         SSH server");
        System.out.println("  _ftp._tcp         FTP server");
        System.out.println("  _smb._tcp         Windows file sharing");
        System.out.println("  _afpovertcp._tcp  Apple file sharing");
        System.out.println("  _printer._tcp     Network printer");
    }

    /**
     * Validates and normalizes a service type string.
     * @param type the service type to validate
     * @return the normalized service type (with trailing dot)
     * @throws IllegalArgumentException if the type is invalid
     */
    private static String validateServiceType(String type) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Service type must not be empty");
        }

        // Remove trailing dot for validation
        String normalized = type.endsWith(".") ? type.substring(0, type.length() - 1) : type;

        // Basic validation: should contain protocol
        if (!normalized.contains("._tcp") && !normalized.contains("._udp")) {
            System.err.println("Warning: Service type should contain ._tcp or ._udp (e.g., _http._tcp)");
        }

        // Should start with underscore
        if (!normalized.startsWith("_")) {
            System.err.println("Warning: Service type should start with underscore (e.g., _http._tcp)");
        }

        // Ensure trailing dot
        return normalized + ".";
    }

    /**
     * Validates and normalizes a domain string.
     * @param domain the domain to validate
     * @return the normalized domain (with trailing dot)
     * @throws IllegalArgumentException if the domain is invalid
     */
    private static String validateDomain(String domain) {
        if (domain == null || domain.trim().isEmpty()) {
            throw new IllegalArgumentException("Domain must not be empty");
        }

        // Ensure trailing dot
        String normalized = domain.trim();
        if (!normalized.endsWith(".")) {
            normalized = normalized + ".";
        }

        return normalized;
    }

    /**
     * Main entry point for the test registration utility.
     * @param args command line arguments
     */
    public static void main(String[] args) {
        String serviceName = DEFAULT_SERVICE_NAME;
        String serviceType = DEFAULT_SERVICE_TYPE;
        String domain = DEFAULT_DOMAIN;
        int port = 0;
        int durationMinutes = DEFAULT_DURATION_MINUTES;

        // Parse command line arguments
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-h":
                case "--help":
                    printUsage();
                    return;

                case "-n":
                case "--name":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    serviceName = args[++i];
                    if (serviceName.trim().isEmpty()) {
                        System.err.println("Error: Service name must not be empty");
                        System.exit(1);
                    }
                    break;

                case "-t":
                case "--type":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    try {
                        serviceType = validateServiceType(args[++i]);
                    } catch (IllegalArgumentException e) {
                        System.err.println("Error: " + e.getMessage());
                        System.exit(1);
                    }
                    break;

                case "-D":
                case "--domain":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    try {
                        domain = validateDomain(args[++i]);
                    } catch (IllegalArgumentException e) {
                        System.err.println("Error: " + e.getMessage());
                        System.exit(1);
                    }
                    break;

                case "-p":
                case "--port":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    try {
                        port = Integer.parseInt(args[++i]);
                        if (port < 0 || port > 65535) {
                            System.err.println("Error: Port must be between 0 and 65535");
                            System.exit(1);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Error: Invalid port number: " + args[i]);
                        System.exit(1);
                    }
                    break;

                case "-d":
                case "--duration":
                    if (i + 1 >= args.length) {
                        System.err.println("Error: Missing value for " + arg);
                        System.exit(1);
                    }
                    try {
                        durationMinutes = Integer.parseInt(args[++i]);
                        if (durationMinutes < 0) {
                            System.err.println("Error: Duration must be >= 0");
                            System.exit(1);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Error: Invalid duration: " + args[i]);
                        System.exit(1);
                    }
                    break;

                default:
                    System.err.println("Error: Unknown option: " + arg);
                    System.err.println("Use --help for usage information.");
                    System.exit(1);
            }
        }

        // Create and start the service registration
        try {
            TestRegister register = new TestRegister(serviceName, serviceType, domain, port, durationMinutes);
            register.start();
        } catch (IllegalArgumentException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            System.exit(1);
        } catch (DNSSDException e) {
            System.err.println("DNSSD Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (IOException e) {
            System.err.println("IO Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (IllegalStateException e) {
            System.err.println("State Error: " + e.getMessage());
            System.exit(1);
        } catch (RuntimeException e) {
            System.err.println("Registration Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
