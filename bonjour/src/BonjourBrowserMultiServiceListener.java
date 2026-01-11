import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import com.apple.dnssd.*;

/**
 * Class for implementation of a listener for general service advertisements.
 * Discovers service types available on the network using the meta-query.
 */
public class BonjourBrowserMultiServiceListener implements BrowseListener {

    // ========================================================================
    // Constants
    // ========================================================================

    // Special mDNS query that returns all registered service TYPES on the network
    // This is a "meta-query" - instead of returning service instances, it returns service types
    private static final String SERVICES_META_QUERY = "_services._dns-sd._udp.";

    // Timestamp format for log messages
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ========================================================================
    // Instance Fields
    // ========================================================================

    // The active DNSSD browser (volatile for thread-safe access from callbacks)
    private volatile DNSSDService browserForServices;

    // Reference to the UI for adding/removing service type nodes
    private final BonjourBrowserInterface guiBrowser;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs a new BonjourBrowserMultiServiceListener.
     * Implements a listener for general service advertisements.
     * @param browser BonjourBrowserInterface instance of the browser (must not be null)
     * @throws DNSSDException if browsing fails to start
     * @throws NullPointerException if browser is null
     */
    public BonjourBrowserMultiServiceListener(BonjourBrowserInterface browser) throws DNSSDException {
        // Assign guiBrowser BEFORE starting browse to avoid race condition
        // (callbacks can fire immediately on another thread)
        this.guiBrowser = Objects.requireNonNull(browser, "browser must not be null");
        logInfo("Starting meta-query browse for service types...");
        browserForServices = DNSSD.browse(0, DNSSD.ALL_INTERFACES, SERVICES_META_QUERY, "", this);
        logInfo("Meta-query browse started successfully");
    }

    // ========================================================================
    // Public Methods
    // ========================================================================

    /**
     * Checks if the browser is currently running.
     * @return true if browsing is active
     */
    public boolean isRunning() {
        return browserForServices != null;
    }

    // ========================================================================
    // BrowseListener Implementation
    // ========================================================================

    /**
     * Called when a browse operation fails.
     * @param service DNSSDService instance
     * @param errorCode the error code
     */
    @Override
    public void operationFailed(DNSSDService service, int errorCode) {
        logError("Meta-query browse FAILED: type= " + SERVICES_META_QUERY + ", errorCode= " + errorCode);
        if (service != null) {
            service.stop();
        }
        browserForServices = null;
    }

    /**
     * Called when a service type is discovered.
     * @param browser DNSSDService instance
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service type name (e.g., "_http")
     * @param regType the registration type (e.g., "_tcp.local.")
     * @param domain the domain
     */
    @Override
    public void serviceFound(DNSSDService browser, int flags, int ifIndex,
                             String serviceName, String regType, String domain) {

        logDebug("SERVICE_TYPE_FOUND: flags= " + flags + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + "), name= " + serviceName +
                ", type= " + regType + ", domain= " + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    browser, flags, ifIndex, serviceName, regType, domain);
            guiBrowser.addGeneralNode(element);
        } catch (Exception e) {
            logError("Failed to add service type: name= " + serviceName + ", type= " + regType +
                    ", domain= " + domain + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when a service type is no longer available.
     * @param browser DNSSDService instance
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service type name
     * @param regType the registration type
     * @param domain the domain
     */
    @Override
    public void serviceLost(DNSSDService browser, int flags, int ifIndex,
                            String serviceName, String regType, String domain) {
        logDebug("SERVICE_TYPE_LOST: flags= " + flags + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + "), name= " + serviceName +
                ", type= " + regType + ", domain= " + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    browser, flags, ifIndex, serviceName, regType, domain);
            guiBrowser.removeGeneralNode(element);
        } catch (Exception e) {
            logError("Failed to remove service type: name= " + serviceName + ", type= " + regType +
                    ", domain= " + domain + " - " + e.getMessage());
            e.printStackTrace();
        }
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

    /**
     * Creates a BonjourBrowserElement from meta-query callback parameters.
     * The meta-query returns service types in a special format that needs conversion.
     * @param browser DNSSDService instance
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service type name (e.g., "_http")
     * @param regType the registration type from meta-query (e.g., "_tcp.local.")
     * @param domain the domain
     * @return BonjourBrowserElement with actual service type and domain
     */
    private BonjourBrowserElement createElementFromMetaQuery(DNSSDService browser, int flags,
                                                              int ifIndex, String serviceName,
                                                              String regType, String domain) {
        // Meta-query returns data in a special format that needs transformation:
        // - serviceName = service type without protocol (e.g., "_http")
        // - regType = protocol + domain (e.g., "_tcp.local.")
        // We need to convert this to standard format:
        // - actualRegType = full service type (e.g., "_http._tcp.")
        // - actualDomain = just the domain (e.g., "local.")

        String actualDomain;
        String actualRegType;

        // Extract domain from regType by removing the protocol prefix
        // "_tcp.local." -> find first dot -> "local."
        int dotIndex = regType.indexOf(".");
        if (dotIndex >= 0 && dotIndex < regType.length() - 1) {
            actualDomain = regType.substring(dotIndex + 1);
        } else {
            // Fallback to provided domain if parsing fails
            actualDomain = domain;
        }

        // Build the full service type by combining serviceName with protocol
        // serviceName="_http", regType starts with "_udp." -> "_http._udp."
        // serviceName="_http", regType starts with "_tcp." -> "_http._tcp."
        if (regType.startsWith("_udp.")) {
            actualRegType = serviceName + "._udp.";
        } else {
            // Default to TCP (most common protocol)
            actualRegType = serviceName + "._tcp.";
        }

        // Create element with empty fullName (not needed for service types)
        return new BonjourBrowserElement(
                browser, flags, ifIndex, "", serviceName, actualRegType, actualDomain);
    }

    // ========================================================================
    // Logging Helpers
    // ========================================================================

    private static String ts() {
        return "[" + LocalTime.now().format(TIME_FMT) + "]";
    }

    private static void logInfo(String msg) {
        System.out.println(ts() + " INFO  [MultiServiceListener] " + msg);
    }

    private static void logDebug(String msg) {
        System.out.println(ts() + " DEBUG [MultiServiceListener] " + msg);
    }

    private static void logError(String msg) {
        System.err.println(ts() + " ERROR [MultiServiceListener] " + msg);
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Stops browsing for services and releases resources.
     */
    public void stop() {
        DNSSDService service = browserForServices;
        if (service != null) {
            logInfo("Stopping meta-query browse...");
            browserForServices = null;
            service.stop();
            logInfo("Meta-query browse stopped");
        }
    }
}
