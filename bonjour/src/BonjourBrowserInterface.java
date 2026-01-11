/**
 * Interface defining operations for managing Bonjour service discovery UI.
 * Provides methods for adding, removing, and updating service nodes in the browser tree.
 *
 * <p>This interface supports two types of nodes:</p>
 * <ul>
 *   <li><b>General nodes:</b> Represent service types (e.g., "_http._tcp."). These are
 *       discovered via the meta-query and shown as expandable folders.</li>
 *   <li><b>Service nodes:</b> Represent individual service instances. These are discovered
 *       when a service type is expanded and can be resolved for details.</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe. Methods may be called from
 * any thread (including DNSSD callback threads). Implementations should dispatch Swing
 * operations to the Event Dispatch Thread (EDT) internally.</p>
 *
 * @see BonjourBrowserImpl
 * @see BonjourBrowserElement
 */
public interface BonjourBrowserInterface {

    // ========================================================================
    // Service Type (General Node) Operations
    // ========================================================================

    /**
     * Adds a general service type node to the browser tree.
     * Creates the domain and service type hierarchy if not already present.
     *
     * @param element BonjourBrowserElement containing regType and domain
     *                (typically created from meta-query results)
     * @return true if the operation was initiated successfully
     * @throws NullPointerException if element is null
     */
    boolean addGeneralNode(BonjourBrowserElement element);

    /**
     * Removes a general service type node from the browser tree.
     * Also removes the parent domain node if it becomes empty.
     *
     * @param element BonjourBrowserElement containing regType and domain
     *                identifying the node to remove
     * @return true if the operation was initiated successfully
     * @throws NullPointerException if element is null
     */
    boolean removeGeneralNode(BonjourBrowserElement element);

    // ========================================================================
    // Service Instance Node Operations
    // ========================================================================

    /**
     * Adds a service instance node to the browser tree.
     * The node is added under its service type (regType) within its domain.
     *
     * @param element BonjourBrowserElement containing fullName, name, regType, and domain
     *                (created when a service is found, not yet resolved)
     * @return true if the operation was initiated successfully
     * @throws NullPointerException if element is null
     */
    boolean addNode(BonjourBrowserElement element);

    /**
     * Removes a service instance node from the browser tree.
     * Also removes parent nodes (regType, domain) if they become empty.
     *
     * @param element BonjourBrowserElement containing fullName, name, regType, and domain
     *                identifying the service instance to remove
     * @return true if the operation was initiated successfully
     * @throws NullPointerException if element is null
     */
    boolean removeNode(BonjourBrowserElement element);

    /**
     * Updates a service instance node with resolved information.
     * Adds child nodes showing hostname:port and TXT record entries.
     *
     * @param element BonjourBrowserElement containing fullName, hostname, port, and txtRecord
     *                (created when a service is resolved)
     * @return true if the operation was initiated successfully, false if the node was not found
     * @throws NullPointerException if element is null
     */
    boolean resolveNode(BonjourBrowserElement element);

    // ========================================================================
    // Subscription Operations
    // ========================================================================

    /**
     * Subscribes to a service type to discover individual service instances.
     * Clears existing children under the service type and starts browsing
     * for services of that type.
     *
     * <p>This is typically called when the user expands a service type node
     * in the browser tree.</p>
     *
     * @param domain the service domain (e.g., "local.")
     * @param regType the service type to browse (e.g., "_http._tcp.")
     * @return true if subscription started successfully, false on error
     * @throws NullPointerException if domain or regType is null
     */
    boolean subscribe(String domain, String regType);
}
