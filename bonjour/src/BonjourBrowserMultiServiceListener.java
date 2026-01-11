import java.net.NetworkInterface;
import java.net.SocketException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.apple.dnssd.*;

/**
 * Class for implementation of a listener for general service advertisements.
 * Discovers service types available on the network using the meta-query.
 * Also enumerates browse domains to support unicast DNS-SD.
 */
public class BonjourBrowserMultiServiceListener implements BrowseListener, DomainListener {

    // ========================================================================
    // Constants
    // ========================================================================

    // Special mDNS query that returns all registered service TYPES on the network
    // This is a "meta-query" - instead of returning service instances, it returns service types
    private static final String SERVICES_META_QUERY = "_services._dns-sd._udp.";

    // Timestamp format for log messages
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Debug logging enable flag
    private static volatile boolean debugEnabled = false;

    // ========================================================================
    // Instance Fields
    // ========================================================================

    // Domain enumeration service to discover browse domains (including unicast)
    private volatile DNSSDService domainEnumerator;

    // Map of domain -> active meta-query browser for that domain
    private final Map<String, DNSSDService> domainBrowsers = new ConcurrentHashMap<>();

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

        // Start domain enumeration to discover all browse domains (local + unicast)
        // The domainFound callback will start browsing each domain as it's discovered
        logInfo("Starting domain enumeration for browse domains...");
        domainEnumerator = DNSSD.enumerateDomains(DNSSD.BROWSE_DOMAINS, DNSSD.ALL_INTERFACES, this);
        logInfo("Domain enumeration started");
    }

    /**
     * Starts browsing for service types in the specified domain.
     * @param domain the domain to browse (e.g., "local." or "example.com.")
     */
    private void startBrowsingDomain(String domain) {
        // Don't start duplicate browser for same domain
        if (domainBrowsers.containsKey(domain)) {
            logDebug("Already browsing domain: " + domain);
            return;
        }

        try {
            // For unicast domains, construct full query: _services._dns-sd._udp.<domain>
            String queryType = SERVICES_META_QUERY;
            String queryDomain = domain;

            logInfo("Starting meta-query browse: type= " + queryType + ", domain= " + queryDomain);
            DNSSDService browser = DNSSD.browse(0, DNSSD.ALL_INTERFACES, queryType, queryDomain, this);
            domainBrowsers.put(domain, browser);
            logInfo("Meta-query browse started for domain: " + domain + " (browser=" + browser + ")");
        } catch (DNSSDException e) {
            logError("Failed to browse domain: " + domain + " - " + e.getMessage() + " (errorCode=" + e.getErrorCode() + ")");
        }
    }

    /**
     * Stops browsing for service types in the specified domain.
     * @param domain the domain to stop browsing
     */
    private void stopBrowsingDomain(String domain) {
        DNSSDService browser = domainBrowsers.remove(domain);
        if (browser != null) {
            logInfo("Stopping meta-query browse for domain: " + domain);
            browser.stop();
        }
    }

    // ========================================================================
    // Public Methods
    // ========================================================================

    /**
     * Checks if the browser is currently running.
     * @return true if browsing is active
     */
    public boolean isRunning() {
        return domainEnumerator != null || !domainBrowsers.isEmpty();
    }

    // ========================================================================
    // DomainListener Implementation
    // ========================================================================

    /**
     * Called when a browse domain is discovered.
     * @param domainEnum the domain enumeration service
     * @param flags operation flags
     * @param ifIndex interface index
     * @param domain the discovered domain (e.g., "local." or "example.com.")
     */
    @Override
    public void domainFound(DNSSDService domainEnum, int flags, int ifIndex, String domain) {
        logInfo("DOMAIN_FOUND: domain= " + domain + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + "), flags= " + flags);

        // Start browsing for service types in this domain
        startBrowsingDomain(domain);
    }

    /**
     * Called when a browse domain is no longer available.
     * @param domainEnum the domain enumeration service
     * @param flags operation flags
     * @param ifIndex interface index
     * @param domain the lost domain
     */
    @Override
    public void domainLost(DNSSDService domainEnum, int flags, int ifIndex, String domain) {
        logInfo("DOMAIN_LOST: domain= " + domain + ", ifIndex= " + ifIndex +
                " (" + getInterfaceName(ifIndex) + "), flags= " + flags);

        // Stop browsing for service types in this domain
        stopBrowsingDomain(domain);
    }

    // ========================================================================
    // BrowseListener Implementation
    // ========================================================================

    /**
     * Called when a browse or domain enumeration operation fails.
     * @param service DNSSDService instance
     * @param errorCode the error code
     */
    @Override
    public void operationFailed(DNSSDService service, int errorCode) {
        // Check if this is the domain enumerator failing
        if (service == domainEnumerator) {
            logError("Domain enumeration FAILED: errorCode= " + errorCode);
            domainEnumerator = null;
        } else {
            // Find which domain browser failed
            String failedDomain = null;
            for (Map.Entry<String, DNSSDService> entry : domainBrowsers.entrySet()) {
                if (entry.getValue() == service) {
                    failedDomain = entry.getKey();
                    break;
                }
            }
            if (failedDomain != null) {
                logError("Meta-query browse FAILED: domain= " + failedDomain + ", errorCode= " + errorCode);
                domainBrowsers.remove(failedDomain);
            } else {
                logError("Unknown operation FAILED: errorCode= " + errorCode);
            }
        }

        if (service != null) {
            service.stop();
        }
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

        // Find which domain this browser belongs to
        String sourceDomain = findDomainForBrowser(browser);

        logDebug("SERVICE_TYPE_FOUND: sourceDomain= " + sourceDomain + ", flags= " + flags +
                ", ifIndex= " + ifIndex + " (" + getInterfaceName(ifIndex) + "), name= " + serviceName +
                ", type= " + regType + ", domain= " + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    sourceDomain, flags, ifIndex, serviceName, regType);
            guiBrowser.addGeneralNode(element);
        } catch (Exception e) {
            logError("Failed to add service type: name= " + serviceName + ", type= " + regType +
                    ", sourceDomain= " + sourceDomain + " - " + e.getMessage());
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
        String sourceDomain = findDomainForBrowser(browser);

        logDebug("SERVICE_TYPE_LOST: sourceDomain= " + sourceDomain + ", flags= " + flags +
                ", ifIndex= " + ifIndex + " (" + getInterfaceName(ifIndex) + "), name= " + serviceName +
                ", type= " + regType + ", domain= " + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    sourceDomain, flags, ifIndex, serviceName, regType);
            guiBrowser.removeGeneralNode(element);
        } catch (Exception e) {
            logError("Failed to remove service type: name= " + serviceName + ", type= " + regType +
                    ", sourceDomain= " + sourceDomain + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Finds which domain a browser belongs to.
     * @param browser the DNSSDService to look up
     * @return the domain name, or "unknown" if not found
     */
    private String findDomainForBrowser(DNSSDService browser) {
        for (Map.Entry<String, DNSSDService> entry : domainBrowsers.entrySet()) {
            if (entry.getValue() == browser) {
                return entry.getKey();
            }
        }
        return "unknown";
    }

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
     * @param sourceDomain the domain we were browsing (from our browser tracking)
     * @param flags flags for use
     * @param ifIndex interface index
     * @param serviceName the service type name (e.g., "_http")
     * @param regType the registration type from meta-query (e.g., "_tcp.local.")
     * @return BonjourBrowserElement with actual service type and domain
     */
    private BonjourBrowserElement createElementFromMetaQuery(String sourceDomain, int flags,
                                                              int ifIndex, String serviceName,
                                                              String regType) {
        // Meta-query returns data in a special format that needs transformation:
        // - serviceName = service type without protocol (e.g., "_http")
        // - regType = protocol (e.g., "_tcp.local." - NOTE: always says "local." even for unicast!)
        // - sourceDomain = the actual domain we queried (e.g., "local." or "example.com.")
        //
        // We need to convert this to standard format:
        // - actualRegType = full service type (e.g., "_http._tcp.")
        // - actualDomain = the source domain we were browsing

        String actualRegType;

        // Build the full service type by combining serviceName with protocol
        // serviceName="_http", regType starts with "_udp." -> "_http._udp."
        // serviceName="_http", regType starts with "_tcp." -> "_http._tcp."
        if (regType.startsWith("_udp.")) {
            actualRegType = serviceName + "._udp.";
        } else {
            // Default to TCP (most common protocol)
            actualRegType = serviceName + "._tcp.";
        }

        // Use sourceDomain as the actual domain (not parsed from regType which is always "local.")
        String actualDomain = sourceDomain;
        if (actualDomain == null || actualDomain.equals("unknown")) {
            // Fallback: try to parse from regType (won't work for unicast but better than nothing)
            int dotIndex = regType.indexOf(".");
            if (dotIndex >= 0 && dotIndex < regType.length() - 1) {
                actualDomain = regType.substring(dotIndex + 1);
            } else {
                actualDomain = "local.";
            }
        }

        // Create element with empty fullName (not needed for service types)
        return new BonjourBrowserElement(
                null, flags, ifIndex, "", serviceName, actualRegType, actualDomain);
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
        if (debugEnabled) {
            System.out.println(ts() + " DEBUG [MultiServiceListener] " + msg);
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
        System.err.println(ts() + " ERROR [MultiServiceListener] " + msg);
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Stops browsing for services and releases resources.
     */
    public void stop() {
        // Stop domain enumeration
        DNSSDService enumService = domainEnumerator;
        if (enumService != null) {
            logInfo("Stopping domain enumeration...");
            domainEnumerator = null;
            enumService.stop();
        }

        // Stop all domain browsers
        int browserCount = domainBrowsers.size();
        if (browserCount > 0) {
            logInfo("Stopping " + browserCount + " domain browser(s)...");
            for (Map.Entry<String, DNSSDService> entry : domainBrowsers.entrySet()) {
                logDebug("Stopping browser for domain: " + entry.getKey());
                entry.getValue().stop();
            }
            domainBrowsers.clear();
        }

        logInfo("All meta-query browsers stopped");
    }
}
