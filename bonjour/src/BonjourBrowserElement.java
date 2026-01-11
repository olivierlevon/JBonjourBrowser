import com.apple.dnssd.*;
import java.util.Objects;

/**
 * Class to encapsulate the structures that Bonjour returns.
 * Data transfer object for service information.
 *
 * <p><b>Immutability:</b> This class has immutable fields (all private final).
 * However, referenced objects (DNSSDService, TXTRecord) may have mutable state.
 * The class is effectively immutable for use as a map key or in collections.</p>
 *
 * <p><b>Identity:</b> Two elements are considered equal if they have the same fullName.
 * The fullName uniquely identifies a service instance in mDNS.</p>
 *
 * <p>This class has two usage modes:</p>
 * <ul>
 *   <li><b>Service Found:</b> Created when a service is discovered. Contains name, regType, domain.
 *       The hostname, port, and txtRecord will be null/-1.</li>
 *   <li><b>Service Resolved:</b> Created when a service is resolved. Contains hostname, port, txtRecord.
 *       The name, regType, and domain will be null.</li>
 * </ul>
 */
public class BonjourBrowserElement {

    /** Port value indicating the port has not been set (service not yet resolved). */
    public static final int PORT_NOT_SET = -1;

    private final DNSSDService service;
    private final int flags;
    private final int ifIndex;
    private final int port;
    private final String fullName;
    private final String name;
    private final String hostname;
    private final String regType;
    private final String domain;
    private final TXTRecord txtRecord;

    /**
     * Creates browser elements when a service is found (not yet resolved).
     * @param service DNSSDService instance (browser)
     * @param flags flags for use
     * @param ifIndex interface index
     * @param fullName the service provider full name (must not be null)
     * @param name the service provider name to be resolved
     * @param regType the service type
     * @param domain the service provider domain
     * @throws NullPointerException if fullName is null
     */
    public BonjourBrowserElement(DNSSDService service, int flags, int ifIndex,
                                 String fullName, String name, String regType, String domain) {
        this.service = service;
        this.flags = flags;
        this.ifIndex = ifIndex;
        this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
        this.name = name;
        this.regType = regType;
        this.domain = domain;
        this.hostname = null;
        this.port = PORT_NOT_SET;
        this.txtRecord = null;
    }

    /**
     * Creates browser elements when a service is resolved.
     * @param service DNSSDService instance (resolver)
     * @param flags flags for use
     * @param ifIndex interface index
     * @param fullName the service provider name (must not be null)
     * @param hostname the service provider hostname
     * @param port the service provider port
     * @param txtRecord the service provider TXT record
     * @throws NullPointerException if fullName is null
     */
    public BonjourBrowserElement(DNSSDService service, int flags, int ifIndex, String fullName,
                                 String hostname, int port, TXTRecord txtRecord) {
        this.service = service;
        this.flags = flags;
        this.ifIndex = ifIndex;
        this.fullName = Objects.requireNonNull(fullName, "fullName must not be null");
        this.hostname = hostname;
        this.port = port;
        this.txtRecord = txtRecord;
        this.name = null;
        this.regType = null;
        this.domain = null;
    }

    /**
     * Gets the DNSSD service (browser or resolver) associated with this element.
     * @return the DNSSDService instance
     */
    public DNSSDService getService() {
        return service;
    }

    /**
     * Gets the flags associated with this service.
     * @return the flags value
     */
    public int getFlags() {
        return flags;
    }

    /**
     * Gets the interface index on which this service was discovered.
     * @return the interface index
     */
    public int getIfIndex() {
        return ifIndex;
    }

    /**
     * Gets the port number of the resolved service.
     * @return the port number, or {@link #PORT_NOT_SET} (-1) if not resolved
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the fully qualified service name.
     * @return the full name (always available)
     */
    public String getFullName() {
        return fullName;
    }

    /**
     * Gets the service name.
     * @return the service name, or null if this is a resolved element
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the hostname of the resolved service.
     * @return the hostname, or null if this is a found (not resolved) element
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Gets the registration type (service type).
     * @return the registration type (e.g., "_http._tcp."), or null if this is a resolved element
     */
    public String getRegType() {
        return regType;
    }

    /**
     * Gets the domain of the service.
     * @return the domain (e.g., "local."), or null if this is a resolved element
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Gets the TXT record containing service metadata.
     * @return the TXT record, or null if this is a found (not resolved) element
     */
    public TXTRecord getTxtRecord() {
        return txtRecord;
    }

    /**
     * Checks if this element represents a resolved service.
     * @return true if this element contains resolved information (hostname, port)
     */
    public boolean isResolved() {
        return hostname != null;
    }

    /**
     * Compares this element to another for equality based on fullName only.
     * Two elements are equal if they have the same fully qualified service name,
     * regardless of other fields (resolved state, port, etc.).
     *
     * @param o the object to compare
     * @return true if the objects have the same fullName
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BonjourBrowserElement that = (BonjourBrowserElement) o;
        return Objects.equals(fullName, that.fullName);
    }

    /**
     * Returns a hash code based on fullName only, consistent with equals().
     * @return hash code value
     */
    @Override
    public int hashCode() {
        return Objects.hash(fullName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BonjourBrowserElement{");
        sb.append("fullName='").append(fullName).append('\'');

        if (isResolved()) {
            sb.append(", hostname='").append(hostname).append('\'');
            sb.append(", port=").append(port);
            if (txtRecord != null) {
                sb.append(", txtRecord=[").append(txtRecord.size()).append(" entries]");
            }
        } else {
            sb.append(", name='").append(name).append('\'');
            sb.append(", regType='").append(regType).append('\'');
            sb.append(", domain='").append(domain).append('\'');
        }

        sb.append(", flags=").append(flags);
        sb.append(", ifIndex=").append(ifIndex);
        sb.append('}');
        return sb.toString();
    }
}
