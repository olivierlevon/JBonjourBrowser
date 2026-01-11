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
    // Instance Fields
    // ========================================================================

    // Reference to the UI for adding/removing/resolving service nodes
    private final BonjourBrowserInterface guiBrowser;

    // Tracks active resolver operations: fullName -> resolver service
    // Used to cancel resolvers when services disappear or on cleanup
    // ConcurrentHashMap for thread-safe access from multiple DNSSD callback threads
    private final Map<String, DNSSDService> activeResolvers = new ConcurrentHashMap<>();

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
        System.err.println("Browse/Resolve failed with error code: " + errorCode);

        // Remove failed resolver from tracking map (find by value since we don't have fullName)
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

        System.out.println("ADD flags: " + flags + ", ifIndex: " + ifIndex +
                ", Name: " + serviceName + ", Type: " + regType +
                ", Domain: " + domain);

        String fullName = null;
        try {
            // Construct the unique identifier for this service instance
            // Format: "ServiceName._type._protocol.domain." (e.g., "MyPrinter._ipp._tcp.local.")
            fullName = DNSSD.constructFullName(serviceName, regType, domain);

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
            System.err.println("Error resolving service " + serviceName + ": " + e.getMessage());
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
        System.out.println("REMOVE flags: " + flags + ", ifIndex: " + ifIndex +
                ", Name: " + serviceName + ", Type: " + regType + ", Domain: " + domain);

        try {
            String fullName = DNSSD.constructFullName(serviceName, regType, domain);

            // Stop and remove the resolver for this service
            DNSSDService resolver = activeResolvers.remove(fullName);
            if (resolver != null) {
                resolver.stop();
            }

            guiBrowser.removeNode(new BonjourBrowserElement(
                    browser, flags, ifIndex, fullName, serviceName, regType, domain));
        } catch (DNSSDException e) {
            System.err.println("Error removing service " + serviceName + ": " + e.getMessage());
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
        System.out.println("RESOLVE flags: " + flags + ", ifIndex: " + ifIndex +
                ", Name: " + fullName + ", Hostname: " + hostName + ", port: " +
                port + ", TextRecord: " + txtRecord);

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
    // Resource Management
    // ========================================================================

    /**
     * Stops all active resolvers and releases resources.
     */
    public void stopAllResolvers() {
        for (DNSSDService resolver : activeResolvers.values()) {
            resolver.stop();
        }
        activeResolvers.clear();
    }
}
