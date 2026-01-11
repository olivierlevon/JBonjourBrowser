import com.apple.dnssd.*;
import java.util.Objects;

/**
 * Class for implementation of a listener for general service advertisements.
 * Discovers service types available on the network using the meta-query.
 */
public class BonjourBrowserMultiServiceListener implements BrowseListener {

    private static final String SERVICES_META_QUERY = "_services._dns-sd._udp.";

    private volatile DNSSDService browserForServices;
    private final BonjourBrowserInterface guiBrowser;

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
        System.out.println("BonjourBrowserMultiServiceListener Starting");
        browserForServices = DNSSD.browse(0, DNSSD.ALL_INTERFACES, SERVICES_META_QUERY, "", this);
        System.out.println("BonjourBrowserMultiServiceListener Running");
    }

    /**
     * Checks if the browser is currently running.
     * @return true if browsing is active
     */
    public boolean isRunning() {
        return browserForServices != null;
    }

    /**
     * Called when a browse operation fails.
     * @param service DNSSDService instance
     * @param errorCode the error code
     */
    @Override
    public void operationFailed(DNSSDService service, int errorCode) {
        System.err.println("BonjourBrowserMultiServiceListener browse failed with error code: " + errorCode);
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

        System.out.println("ADD flags:" + flags + ", ifIndex:" + ifIndex +
                ", Name:" + serviceName + ", Type:" + regType +
                ", Domain:" + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    browser, flags, ifIndex, serviceName, regType, domain);
            guiBrowser.addGeneralNode(element);
        } catch (Exception e) {
            System.err.println("Error processing discovered service type: " + e.getMessage());
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
        System.out.println("REMOVE flags:" + flags + ", ifIndex:" + ifIndex +
                ", Name:" + serviceName + ", Type:" + regType + ", Domain:" + domain);

        try {
            BonjourBrowserElement element = createElementFromMetaQuery(
                    browser, flags, ifIndex, serviceName, regType, domain);
            guiBrowser.removeGeneralNode(element);
        } catch (Exception e) {
            System.err.println("Error removing service type: " + e.getMessage());
            e.printStackTrace();
        }
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
        String actualDomain;
        String actualRegType;

        // Extract domain from regType (e.g., "_tcp.local." -> "local.")
        int dotIndex = regType.indexOf(".");
        if (dotIndex >= 0 && dotIndex < regType.length() - 1) {
            actualDomain = regType.substring(dotIndex + 1);
        } else {
            actualDomain = domain;
        }

        // Build actual registration type from serviceName and protocol
        if (regType.startsWith("_udp.")) {
            actualRegType = serviceName + "._udp.";
        } else {
            actualRegType = serviceName + "._tcp.";
        }

        return new BonjourBrowserElement(
                browser, flags, ifIndex, "", serviceName, actualRegType, actualDomain);
    }

    /**
     * Stops browsing for services and releases resources.
     */
    public void stop() {
        DNSSDService service = browserForServices;
        if (service != null) {
            System.out.println("BonjourBrowserMultiServiceListener Stopping");
            browserForServices = null;
            service.stop();
        }
    }
}
