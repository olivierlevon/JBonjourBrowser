import javax.swing.*;
import javax.swing.tree.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import com.apple.dnssd.*;

/**
 * Class to implement the BonjourBrowserInterface.
 * Manages the tree structure and service subscriptions.
 * All Swing operations are dispatched to the Event Dispatch Thread (EDT).
 */
public class BonjourBrowserImpl implements BonjourBrowserInterface {

    // ========================================================================
    // Constants
    // ========================================================================

    private static final String PLACEHOLDER_TEXT = "Place Holder";

    // Timestamp format for log messages
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Debug logging enable flag (can be toggled at runtime)
    private static volatile boolean debugEnabled = false;

    // Batch update delay in milliseconds - coalesces rapid updates
    private static final int BATCH_UPDATE_DELAY_MS = 50;

    // Maximum delay before forcing batch update (safety net for MORE_COMING)
    private static final int MAX_BATCH_DELAY_MS = 500;

    // ========================================================================
    // Instance Fields
    // ========================================================================

    // Flag to temporarily disable tree expansion handling during programmatic updates
    private volatile boolean ignoreTreeExpansion = false;

    // Maps fullName -> tree node for quick lookup when resolving services
    private final Map<String, DefaultMutableTreeNode> nodeMap = new ConcurrentHashMap<>();

    // Reference to the main UI frame
    private final BonjourBrowser browser;

    // Listener for individual service discovery and resolution
    private final BonjourBrowserSingleServiceListener singleServiceListener;

    // Active DNSSD browser for the currently expanded service type (null if none)
    private DNSSDService browserForSingleServices;

    // Queue for pending tree operations (thread-safe)
    private final Queue<TreeOperation> pendingOperations = new ConcurrentLinkedQueue<>();

    // Timer for coalescing batch updates (created lazily on EDT)
    // Using javax.swing.Timer ensures callback runs on EDT
    private javax.swing.Timer batchUpdateTimer;

    // ========================================================================
    // Inner Classes for Batch Updates
    // ========================================================================

    /** Types of tree operations that can be batched */
    private enum OpType {
        ADD_GENERAL,      // Add service type node
        REMOVE_GENERAL,   // Remove service type node
        ADD_SERVICE,      // Add service instance node
        REMOVE_SERVICE,   // Remove service instance node
        RESOLVE_SERVICE   // Add resolved info (hostname, TXT records)
    }

    /** Represents a pending tree operation */
    private static class TreeOperation {
        final OpType type;
        final BonjourBrowserElement element;

        TreeOperation(OpType type, BonjourBrowserElement element) {
            this.type = type;
            this.element = element;
        }
    }

    /**
     * Checks if the MORE_COMING flag is set.
     * @param flags the DNSSD flags
     * @return true if more results are expected immediately
     */
    private static boolean hasMoreComing(int flags) {
        return (flags & DNSSD.MORE_COMING) != 0;
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs a new BonjourBrowserImpl.<br>
     * Implements the interface of the browser.
     * @param browser BonjourBrowser instance of the browser
     */
    public BonjourBrowserImpl(BonjourBrowser browser) {
        this.browser = browser;
        this.singleServiceListener = new BonjourBrowserSingleServiceListener(this);
    }

    // ========================================================================
    // Public Accessors
    // ========================================================================

    /**
     * Checks if tree expansion events should be ignored.
     * @return true if tree expansion should be ignored
     */
    public boolean isIgnoreTreeExpansion() {
        return ignoreTreeExpansion;
    }

    // ========================================================================
    // Batch Update Processing
    // ========================================================================

    /**
     * Queues an operation and schedules batch processing.
     * Thread-safe: can be called from any thread.
     *
     * Uses the MORE_COMING flag to optimize batching:
     * - If MORE_COMING is NOT set, schedule immediate batch update (last in burst)
     * - If MORE_COMING is set, schedule delayed update as safety net
     */
    private void queueOperation(OpType type, BonjourBrowserElement element) {
        pendingOperations.add(new TreeOperation(type, element));

        if (!hasMoreComing(element.getFlags())) {
            // Last in burst - schedule immediate update
            scheduleBatchUpdate(BATCH_UPDATE_DELAY_MS);
        } else {
            // More coming - schedule delayed update as safety net
            // This ensures updates happen even if final callback never arrives
            scheduleBatchUpdate(MAX_BATCH_DELAY_MS);
        }
    }

    /**
     * Schedules the batch update timer to fire after the specified delay.
     * If the timer is already running with a shorter delay, keeps the shorter one.
     * @param delayMs delay in milliseconds before processing
     */
    private void scheduleBatchUpdate(int delayMs) {
        runOnEDT(() -> {
            if (batchUpdateTimer == null) {
                batchUpdateTimer = new javax.swing.Timer(delayMs, e -> processBatchUpdates());
                batchUpdateTimer.setRepeats(false);
                batchUpdateTimer.start();
            } else if (batchUpdateTimer.isRunning()) {
                // If new delay is shorter, restart with shorter delay
                // Otherwise let existing timer continue (it will fire sooner)
                if (delayMs < batchUpdateTimer.getDelay()) {
                    batchUpdateTimer.setDelay(delayMs);
                    batchUpdateTimer.restart();
                }
                // else: existing timer fires sooner, don't change it
            } else {
                batchUpdateTimer.setDelay(delayMs);
                batchUpdateTimer.start();
            }
        });
    }

    /**
     * Processes all pending tree operations in a single batch.
     * Must be called on the EDT.
     */
    private void processBatchUpdates() {
        int count = pendingOperations.size();
        if (count == 0) {
            return;
        }

        logDebug("Processing batch of " + count + " tree operation(s)");

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
        DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

        // Process all pending operations
        TreeOperation op;
        while ((op = pendingOperations.poll()) != null) {
            switch (op.type) {
                case ADD_GENERAL:
                    processAddGeneral(op.element, root, treeModel);
                    break;
                case REMOVE_GENERAL:
                    processRemoveGeneral(op.element, root, treeModel);
                    break;
                case ADD_SERVICE:
                    processAddService(op.element, root, treeModel);
                    break;
                case REMOVE_SERVICE:
                    processRemoveService(op.element, root, treeModel);
                    break;
                case RESOLVE_SERVICE:
                    processResolveService(op.element, treeModel);
                    break;
            }
        }

        logDebug("Batch processing complete");
    }

    /** Process ADD_GENERAL operation */
    private void processAddGeneral(BonjourBrowserElement element,
                                   DefaultMutableTreeNode root, DefaultTreeModel treeModel) {
        String domainName = element.getDomain();
        String regTypeName = element.getRegType();

        DefaultMutableTreeNode domain = addNodeInternal(domainName, root, treeModel, true);
        DefaultMutableTreeNode regtype = addNodeInternal(regTypeName, domain, treeModel, true);
        addNodeInternal(PLACEHOLDER_TEXT, regtype, treeModel, false);
    }

    /** Process REMOVE_GENERAL operation */
    private void processRemoveGeneral(BonjourBrowserElement element,
                                      DefaultMutableTreeNode root, DefaultTreeModel treeModel) {
        String domainName = element.getDomain();
        String regTypeName = element.getRegType();

        DefaultMutableTreeNode dnode = findNode(root, domainName);
        if (dnode == null) return;

        DefaultMutableTreeNode rnode = findNode(dnode, regTypeName);
        if (rnode == null) return;

        removeNodeIfEmpty(rnode, treeModel, Integer.MAX_VALUE);
        removeNodeIfEmpty(dnode, treeModel, 0);
    }

    /** Process ADD_SERVICE operation */
    private void processAddService(BonjourBrowserElement element,
                                   DefaultMutableTreeNode root, DefaultTreeModel treeModel) {
        String domainName = element.getDomain();
        String regTypeName = element.getRegType();
        String serviceName = element.getName();
        String fullName = element.getFullName();

        logDebug("processAddService: fullName= " + fullName);

        DefaultMutableTreeNode domain = addNodeInternal(domainName, root, treeModel, true);
        DefaultMutableTreeNode regtype = addNodeInternal(regTypeName, domain, treeModel, true);

        ignoreTreeExpansion = true;
        DefaultMutableTreeNode name = addNodeInternal(serviceName, regtype, treeModel, true);
        ignoreTreeExpansion = false;

        nodeMap.put(fullName, name);
        logDebug("processAddService: stored in nodeMap fullName= " + fullName);
    }

    /** Process REMOVE_SERVICE operation */
    private void processRemoveService(BonjourBrowserElement element,
                                      DefaultMutableTreeNode root, DefaultTreeModel treeModel) {
        String domainName = element.getDomain();
        String regTypeName = element.getRegType();
        String serviceName = element.getName();

        DefaultMutableTreeNode dnode = findNode(root, domainName);
        if (dnode == null) return;

        DefaultMutableTreeNode rnode = findNode(dnode, regTypeName);
        if (rnode == null) return;

        DefaultMutableTreeNode nnode = findNode(rnode, serviceName);
        if (nnode == null) return;

        removeNodeIfEmpty(nnode, treeModel, Integer.MAX_VALUE);
        removeNodeIfEmpty(rnode, treeModel, 0);
        removeNodeIfEmpty(dnode, treeModel, 0);
    }

    /** Process RESOLVE_SERVICE operation */
    private void processResolveService(BonjourBrowserElement element, DefaultTreeModel treeModel) {
        String fullName = element.getFullName();
        String hostname = element.getHostname();
        int port = element.getPort();
        TXTRecord txtRecord = element.getTxtRecord();

        DefaultMutableTreeNode name = nodeMap.get(fullName);

        if (name == null) {
            logWarn("processResolveService: node not found for fullName= " + fullName);
            return;
        }

        logDebug("processResolveService: adding hostname:port= " + hostname + ":" + port);

        String hostPort = hostname + ":" + port;
        addNodeInternal(hostPort, name, treeModel, false);

        if (txtRecord != null) {
            for (int i = 0; i < txtRecord.size(); i++) {
                String key = txtRecord.getKey(i);
                if (key != null && !key.isEmpty()) {
                    byte[] value = txtRecord.getValue(i);
                    String displayValue = formatTxtValue(value);
                    addNodeInternal(key + "=" + displayValue, name, treeModel, false);
                }
            }
        }
    }

    // ========================================================================
    // Private Helper Methods
    // ========================================================================

    /**
     * Executes a Runnable on the Event Dispatch Thread.
     * If already on EDT, executes immediately. Otherwise uses invokeLater.
     * @param runnable the code to execute on EDT
     */
    private void runOnEDT(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    /**
     * Adds a child node to the parent if it doesn't already exist.
     * Nodes are inserted in alphabetical order (case-insensitive).
     * <p><b>Must be called on the Event Dispatch Thread (EDT).</b></p>
     *
     * @param childName the name/label for the child node
     * @param parent the parent node to add the child to
     * @param treeModel the tree model to update
     * @param openUp if true, scrolls to make the new node visible
     * @return the existing or newly created child node
     */
    private DefaultMutableTreeNode addNodeInternal(String childName,
                                                   DefaultMutableTreeNode parent,
                                                   DefaultTreeModel treeModel,
                                                   boolean openUp) {
        // Check if node already exists to avoid duplicates
        DefaultMutableTreeNode existingNode = findNode(parent, childName);

        if (existingNode == null) {
            // Create new node and find sorted insertion position
            existingNode = new DefaultMutableTreeNode(childName);
            int insertIndex = findSortedInsertIndex(parent, childName);
            treeModel.insertNodeInto(existingNode, parent, insertIndex);

            // Auto-scroll to show new node if requested
            if (openUp) {
                browser.getTree().scrollPathToVisible(new TreePath(existingNode.getPath()));
            }
        }

        return existingNode;
    }

    /**
     * Finds the insertion index to maintain alphabetical order (case-insensitive).
     * @param parent the parent node
     * @param childName the name of the child to insert
     * @return the index where the child should be inserted
     */
    private int findSortedInsertIndex(DefaultMutableTreeNode parent, String childName) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            String existingName = child.toString();
            if (childName.compareToIgnoreCase(existingName) < 0) {
                return i;
            }
        }
        return childCount;  // Insert at end
    }

    /**
     * Formats a TXT record value for display.
     * Attempts to decode as valid UTF-8 text first.
     * If decoding fails or contains control characters, shows hex + ASCII representation.
     * @param value the raw byte array value (may be null)
     * @return formatted string for display
     */
    private String formatTxtValue(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }

        // Try to decode as UTF-8 with strict error handling
        try {
            java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);

            String decoded = decoder.decode(java.nio.ByteBuffer.wrap(value)).toString();

            // Check for control characters (binary data indicators)
            boolean hasControlChars = false;
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                    hasControlChars = true;
                    break;
                }
                if (c == 0x7F) {  // DEL character
                    hasControlChars = true;
                    break;
                }
            }

            if (!hasControlChars) {
                return decoded;  // Valid UTF-8 text
            }
        } catch (java.nio.charset.CharacterCodingException e) {
            // Not valid UTF-8, fall through to hex display
        }

        // Display as hex dump with ASCII representation for binary data
        StringBuilder result = new StringBuilder("[");
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < value.length; i++) {
            int b = value[i] & 0xFF;
            result.append(String.format("%02X", b));
            if (i < value.length - 1) {
                result.append(" ");
            }
            // Build ASCII representation (printable chars or '.')
            ascii.append((b >= 32 && b < 127) ? (char) b : '.');
        }
        result.append("] \"").append(ascii).append("\"");
        return result.toString();
    }

    /**
     * Finds a child node by name within the given parent node.
     *
     * @param node the parent node to search in (may be null)
     * @param name the name to search for (may be null)
     * @return the matching child node, or null if not found or parameters are null
     */
    @SuppressWarnings("unchecked")  // TreeNode.children() returns raw Enumeration
    private DefaultMutableTreeNode findNode(TreeNode node, String name) {
        if (node == null || name == null) {
            return null;
        }
        Enumeration<? extends TreeNode> children = node.children();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) children.nextElement();
            if (name.equals(n.toString())) {
                return n;
            }
        }
        return null;
    }

    /**
     * Removes a node from the tree if its child count is at or below the threshold.
     * <p><b>Must be called on the Event Dispatch Thread (EDT).</b></p>
     *
     * @param node the node to potentially remove (may be null)
     * @param treeModel the tree model to update
     * @param maxChildCount remove the node if it has this many children or fewer
     */
    private void removeNodeIfEmpty(DefaultMutableTreeNode node, DefaultTreeModel treeModel, int maxChildCount) {
        if (node != null && node.getChildCount() <= maxChildCount) {
            treeModel.removeNodeFromParent(node);
        }
    }

    // ========================================================================
    // BonjourBrowserInterface Implementation - Service Type Operations
    // ========================================================================

    /**
     * Adds a general service type node to the JTree.
     * Uses batched updates for better performance.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean addGeneralNode(BonjourBrowserElement element) {
        queueOperation(OpType.ADD_GENERAL, element);
        return true;
    }

    /**
     * Removes a general service type node from the JTree.
     * Uses batched updates for better performance.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean removeGeneralNode(BonjourBrowserElement element) {
        queueOperation(OpType.REMOVE_GENERAL, element);
        return true;
    }

    // ========================================================================
    // BonjourBrowserInterface Implementation - Subscription
    // ========================================================================

    /**
     * Subscribes to a service type to discover individual service instances.
     *
     * <p>This method cancels any previous subscription and starts browsing for
     * services of the specified type in the specified domain. Service discovery
     * results arrive asynchronously via the SingleServiceListener callbacks.</p>
     *
     * <p><b>Thread Safety:</b></p>
     * <ul>
     *   <li>This method is synchronized to prevent concurrent subscription changes</li>
     *   <li>Can be called from any thread (typically called from EDT via tree expansion)</li>
     *   <li>UI updates are dispatched to EDT via runOnEDT()</li>
     *   <li>The DNSSD.browse() call is non-blocking - it registers a callback and returns immediately</li>
     * </ul>
     *
     * <p><b>Important:</b> Only one service type can be actively browsed at a time.
     * Calling this method will stop discovery of the previously subscribed service type.</p>
     *
     * @param domain service provider domain (e.g., "local." or "example.com.")
     * @param regType service type (e.g., "_http._tcp.")
     * @return true on success, false if DNSSD browse failed
     */
    @Override
    public synchronized boolean subscribe(String domain, String regType) {
        logInfo("Subscribing to service type: " + regType + " in domain: " + domain);
        try {
            // Cancel any previous service type subscription
            // Only one service type can be actively browsed at a time
            if (browserForSingleServices != null) {
                logDebug("Stopping previous service browser");
                browserForSingleServices.stop();
                browserForSingleServices = null;
            }

            // Stop any pending service resolutions from previous subscription
            singleServiceListener.stopAllResolvers();

            // Clear the tree UI on EDT - remove old services from previously expanded type
            runOnEDT(() -> {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
                DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

                // Navigate to the service type node: root -> domain -> regType
                DefaultMutableTreeNode ndomain = findNode(root, domain);
                DefaultMutableTreeNode nregtype = findNode(ndomain, regType);

                // Remove all children (old services + placeholder) from the service type node
                // Collect first, then remove to avoid ConcurrentModificationException
                if (nregtype != null) {
                    int childCount = nregtype.getChildCount();
                    logDebug("subscribe EDT: removing " + childCount + " children from " + regType + " in " + domain);
                    List<DefaultMutableTreeNode> childrenToRemove = new ArrayList<>();
                    for (int i = 0; i < nregtype.getChildCount(); i++) {
                        childrenToRemove.add((DefaultMutableTreeNode) nregtype.getChildAt(i));
                    }
                    for (DefaultMutableTreeNode child : childrenToRemove) {
                        logDebug("subscribe EDT: removing child: " + child.getUserObject());
                        treeModel.removeNodeFromParent(child);
                    }
                } else {
                    logWarn("subscribe EDT: regtype node not found for " + regType + " in " + domain);
                }
            });

            // Start DNSSD browse for services of this type
            // Results will arrive via singleServiceListener callbacks
            browserForSingleServices = DNSSD.browse(0, DNSSD.ALL_INTERFACES, regType, domain, singleServiceListener);
            logInfo("Subscription started for: " + regType);

        } catch (DNSSDException e) {
            logError("Failed to browse for services: type= " + regType + ", domain= " + domain + " - " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        return true;
    }

    // ========================================================================
    // BonjourBrowserInterface Implementation - Service Instance Operations
    // ========================================================================

    /**
     * Adds a node about BonjourBrowserElement to the JTree.
     * Uses batched updates for better performance.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean addNode(BonjourBrowserElement element) {
        logDebug("addNode: fullName= " + element.getFullName() + ", serviceName= " + element.getName() +
                ", regType= " + element.getRegType() + ", domain= " + element.getDomain());
        queueOperation(OpType.ADD_SERVICE, element);
        return true;
    }

    /**
     * Removes a node about BonjourBrowserElement from the JTree.
     * Uses batched updates for better performance.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean removeNode(BonjourBrowserElement element) {
        // Remove from nodeMap immediately (thread-safe with ConcurrentHashMap)
        nodeMap.remove(element.getFullName());
        queueOperation(OpType.REMOVE_SERVICE, element);
        return true;
    }

    /**
     * Updates a node with the resolved info in the JTree.
     * Uses batched updates for better performance.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean resolveNode(BonjourBrowserElement element) {
        logDebug("resolveNode: fullName= " + element.getFullName() +
                ", hostname= " + element.getHostname() + ", port= " + element.getPort());
        queueOperation(OpType.RESOLVE_SERVICE, element);
        return true;
    }

    // ========================================================================
    // Logging Helpers
    // ========================================================================

    private static String ts() {
        return "[" + LocalTime.now().format(TIME_FMT) + "]";
    }

    private static void logInfo(String msg) {
        System.out.println(ts() + " INFO  [BrowserImpl] " + msg);
    }

    private static void logDebug(String msg) {
        if (debugEnabled) {
            System.out.println(ts() + " DEBUG [BrowserImpl] " + msg);
        }
    }

    /**
     * Enable or disable debug logging.
     * @param enabled true to enable debug logging
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    private static void logWarn(String msg) {
        System.err.println(ts() + " WARN  [BrowserImpl] " + msg);
    }

    private static void logError(String msg) {
        System.err.println(ts() + " ERROR [BrowserImpl] " + msg);
    }

    // ========================================================================
    // Resource Management
    // ========================================================================

    /**
     * Stops the browser for single services and cleans up all resources.
     */
    public synchronized void cleanup() {
        logInfo("Cleaning up browser resources...");

        // Stop the batch update timer
        if (batchUpdateTimer != null) {
            batchUpdateTimer.stop();
        }

        // Clear pending operations
        int pendingCount = pendingOperations.size();
        pendingOperations.clear();
        if (pendingCount > 0) {
            logDebug("Discarded " + pendingCount + " pending operation(s)");
        }

        // Stop active resolvers first
        singleServiceListener.stopAllResolvers();

        // Stop the browser
        if (browserForSingleServices != null) {
            browserForSingleServices.stop();
            browserForSingleServices = null;
        }

        // Clear the node map
        int nodeCount = nodeMap.size();
        nodeMap.clear();
        logInfo("Cleanup complete (cleared " + nodeCount + " node mappings)");
    }
}
