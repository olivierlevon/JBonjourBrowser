import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.apple.dnssd.*;

/**
 * Class for implementation of a listener for specific service advertisements.
 * Discovers individual service instances and resolves their details.
 */
public class BonjourBrowserSingleServiceListener implements BrowseListener, ResolveListener {

    // ========================================================================
    // Constants
    // ========================================================================

    // Timestamp format for log messages
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Debug logging enable flag
    private static volatile boolean debugEnabled = false;

    // ========================================================================
    // Instance Fields
    // ========================================================================

    // Reference to the UI for adding/removing/resolving service nodes
    private final BonjourBrowserInterface guiBrowser;

    // Tracks active resolver operations: fullName -> resolver service
    // Used to cancel resolvers when services disappear or on cleanup
    // ConcurrentHashMap for thread-safe access from multiple DNSSD callback threads
    private final Map<String, DNSSDService> activeResolvers = new ConcurrentHashMap<>();

    // Tracks seen services to filter duplicates (same service on different interfaces)
    private final java.util.Set<String> seenServices = ConcurrentHashMap.newKeySet();

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs a new BonjourBrowserSingleServiceListener.
     * Implements a listener for specific service advertisements.
     * @param guiIntf BonjourBrowserInterface instance of the browser (must not be null)
     * @throws NullPointerException if guiIntf is null
     */
    public BonjourBrowserSingleServiceListener(BonjourBrowserInterface guiIntf) {
        guiBrowser = Objects.requireNonNull(guiIntf, "guiIntf must not be null");
    }

    // ========================================================================
    // BrowseListener / ResolveListener Implementation
    // ========================================================================

    /**
     * Called when a browse or resolve operation fails.
     * @param service DNSSDService instance that failed
     * @param errorCode the error code
     */
    @Override
    public void operationFailed(DNSSDService service, int errorCode) {
        // Try to find the fullName associated with this service for better error reporting
        String failedServiceName = null;
        if (service != null) {
            for (Map.Entry<String, DNSSDService> entry : activeResolvers.entrySet()) {
                if (entry.getValue() == service) {
                    failedServiceName = entry.getKey();
                    break;
                }
            }
        }

        if (failedServiceName != null) {
            logError("Browse/Resolve FAILED: fullName= " + failedServiceName + ", errorCode= " + errorCode);
        } else {
            logError("Browse/Resolve FAILED: errorCode= " + errorCode);
        }

        // Remove failed resolver from tracking map
        if (service != null) {
            activeResolvers.entrySet().removeIf(entry -> entry.getValue() == service);
            service.stop();
        }
    }

    /**
     * Called when a service is discovered.
     * @param browser DNSSDService instance
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service provider name
     * @param regType the service type
     * @param domain the service provider domain
     */
    @Override
    public void serviceFound(DNSSDService browser, int flags, int ifIndex,
                             String serviceName, String regType, String domain) {

        String fullName = null;
        try {
            // Construct the unique identifier for this service instance
            // Format: "ServiceName._type._protocol.domain." (e.g., "MyPrinter._ipp._tcp.local.")
            fullName = DNSSD.constructFullName(serviceName, regType, domain);

            // Filter duplicates - same service can be announced on multiple interfaces
            if (!seenServices.add(fullName)) {
                logDebug("SERVICE_FOUND (duplicate ignored): " + serviceName + " on ifIndex= " + ifIndex);
                return;
            }

            // Log at INFO level since service discovery is an important event
            logInfo("SERVICE_FOUND: name= " + serviceName + ", type= " + regType + ", domain= " + domain);
            // Debug: show encoding details
            logEncodingDebug("serviceName", serviceName);
            logDebug("SERVICE_FOUND details: flags= " + flags + ", ifIndex= " + ifIndex +
                    " (" + getInterfaceName(ifIndex) + ")");

            // Immediately start resolving to get hostname, port, and TXT record
            // Resolution is async - results arrive in serviceResolved() callback
            DNSSDService resolver = DNSSD.resolve(0, DNSSD.ALL_INTERFACES,
                    serviceName, regType, domain, this);

            // Track the resolver so we can cancel it if the service disappears
            // or when switching to a different service type
            activeResolvers.put(fullName, resolver);

            // Add the service node to the tree (before resolution completes)
            // Resolution info will be added as child nodes later
            guiBrowser.addNode(new BonjourBrowserElement(
                    browser, flags, ifIndex, fullName, serviceName, regType, domain));

        } catch (DNSSDException e) {
            logError("Failed to resolve service: name= " + serviceName + ", type= " + regType +
                    ", domain= " + domain + " - " + e.getMessage());
            e.printStackTrace();

            // Clean up partial state if resolve failed after constructing fullName
            if (fullName != null) {
                activeResolvers.remove(fullName);
            }
        }
    }

    /**
     * Called when a service is no longer available.
     * @param browser DNSSDService instance
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service provider name
     * @param regType the service type
     * @param domain the service provider domain
     */
    @Override
    public void serviceLost(DNSSDService browser, int flags, int ifIndex,
                            String serviceName, String regType, String domain) {
        // Log at INFO level since service removal is an important event
        logInfo("SERVICE_LOST: name= " + serviceName + ", type= " + regType + ", domain= " + domain);
        logDebug("SERVICE_LOST details: flags= " + flags + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + ")");

        try {
            String fullName = DNSSD.constructFullName(serviceName, regType, domain);

            // Remove from seen services tracking
            seenServices.remove(fullName);

            // Stop and remove the resolver for this service
            DNSSDService resolver = activeResolvers.remove(fullName);
            if (resolver != null) {
                resolver.stop();
            }

            guiBrowser.removeNode(new BonjourBrowserElement(
                    browser, flags, ifIndex, fullName, serviceName, regType, domain));
        } catch (DNSSDException e) {
            logError("Failed to remove service: name= " + serviceName + ", type= " + regType +
                    ", domain= " + domain + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when a service is resolved.
     * @param resolver DNSSDService instance (the resolver that completed)
     * @param flags flags for use
     * @param ifIndex interface index
     * @param fullName the service provider full name
     * @param hostName the service provider hostname
     * @param port the service provider port
     * @param txtRecord the service provider TXT record
     */
    @Override
    public void serviceResolved(DNSSDService resolver, int flags, int ifIndex, String fullName,
                                String hostName, int port, TXTRecord txtRecord) {
        logDebug("SERVICE_RESOLVED: flags= " + flags + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + "), fullName= " + fullName +
                ", hostname= " + hostName + ", port= " + port +
                ", txtRecord= " + (txtRecord != null ? txtRecord.size() + " entries" : "null"));

        // Update the tree node with resolved information (hostname:port and TXT records)
        // This adds child nodes under the service name node
        guiBrowser.resolveNode(new BonjourBrowserElement(
                resolver, flags, ifIndex, fullName, hostName, port, txtRecord));

        // Clean up: remove from tracking map
        // Note: may already be removed if serviceLost was called before resolution completed
        activeResolvers.remove(fullName);

        // Stop the resolver - we only need one-shot resolution
        // Keeping it running would cause repeated callbacks as the service re-announces
        resolver.stop();
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Gets the name and description of a network interface from its index.
     * @param ifIndex the interface index (0 means any/all interfaces)
     * @return the interface name and description, or "any" for index 0
     */
    private static String getInterfaceName(int ifIndex) {
        if (ifIndex == 0) {
            return "any";
        }
        try {
            NetworkInterface netIf = NetworkInterface.getByIndex(ifIndex);
            if (netIf != null) {
                String name = netIf.getName();
                String desc = netIf.getDisplayName();
                if (desc != null && !desc.equals(name)) {
                    return name + " - " + desc;
                }
                return name;
            }
        } catch (SocketException e) {
            // Ignore
        }
        return "if" + ifIndex;
    }

    // ========================================================================
    // Public Methods
    // ========================================================================

    /**
     * Checks if there are any active resolvers.
     * @return true if there are active resolvers
     */
    public boolean hasActiveResolvers() {
        return !activeResolvers.isEmpty();
    }

    /**
     * Gets the count of active resolvers.
     * @return the number of active resolvers
     */
    public int getActiveResolverCount() {
        return activeResolvers.size();
    }

    // ========================================================================
    // Logging Helpers
    // ========================================================================

    private static String ts() {
        return "[" + LocalTime.now().format(TIME_FMT) + "]";
    }

    private static void logInfo(String msg) {
        System.out.println(ts() + " INFO  [SingleServiceListener] " + msg);
    }

    private static void logDebug(String msg) {
        if (debugEnabled) {
            System.out.println(ts() + " DEBUG [SingleServiceListener] " + msg);
        }
    }

    /**
     * Enable or disable debug logging.
     * @param enabled true to enable debug logging
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logError(String msg) {
        System.err.println(ts() + " ERROR [SingleServiceListener] " + msg);
    }

    /**
     * Logs encoding debug info for a string - shows bytes, codepoints, and char details.
     * Only logs if debug is enabled.
     * @param label the label for this debug output
     * @param value the string value to analyze
     */
    private static void logEncodingDebug(String label, String value) {
        if (!debugEnabled) {
            return;
        }

        if (value == null) {
            System.out.println(ts() + " ENCODING [" + label + "] null");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(ts()).append(" ENCODING [").append(label).append("] ");
        sb.append("length=").append(value.length()).append(", ");

        // Show codepoints
        sb.append("codepoints=[");
        value.codePoints().forEach(cp -> sb.append(String.format("U+%04X ", cp)));
        sb.append("], ");

        // Show UTF-8 bytes
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        sb.append("UTF-8 bytes=[");
        for (byte b : utf8) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        sb.append("]");

        System.out.println(sb.toString());
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Stops all active resolvers and releases resources.
     * Also clears the seen services set to allow fresh discovery on next subscription.
     */
    public void stopAllResolvers() {
        int count = activeResolvers.size();
        if (count > 0) {
            logInfo("Stopping " + count + " active resolver(s)...");
            for (DNSSDService resolver : activeResolvers.values()) {
                resolver.stop();
            }
            activeResolvers.clear();
            logInfo("All resolvers stopped");
        }
        // Clear seen services to allow fresh discovery on next subscription
        seenServices.clear();
    }
}
