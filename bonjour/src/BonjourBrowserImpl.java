import javax.swing.*;
import javax.swing.tree.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
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
            // Create new node and insert at end of parent's children
            existingNode = new DefaultMutableTreeNode(childName);
            treeModel.insertNodeInto(existingNode, parent, parent.getChildCount());

            // Auto-scroll to show new node if requested
            if (openUp) {
                browser.getTree().scrollPathToVisible(new TreePath(existingNode.getPath()));
            }
        }

        return existingNode;
    }

    /**
     * Formats a TXT record value for display.
     * If the value is printable text, returns it as a UTF-8 string.
     * If it contains binary data, returns a hex representation.
     * @param value the raw byte array value (may be null)
     * @return formatted string for display
     */
    private String formatTxtValue(byte[] value) {
        if (value == null || value.length == 0) {
            return "";
        }

        // Check if bytes contain binary control characters (0x00-0x1F except tab/newline/return, and 0x7F)
        // High bytes (0x80-0xFF) are allowed as they're valid UTF-8
        boolean hasBinaryData = false;
        for (byte b : value) {
            int unsigned = b & 0xFF;
            // Control characters (except whitespace) and DEL indicate binary data
            if (unsigned < 32 && unsigned != '\t' && unsigned != '\n' && unsigned != '\r') {
                hasBinaryData = true;
                break;
            }
            if (unsigned == 0x7F) {  // DEL character
                hasBinaryData = true;
                break;
            }
        }

        if (!hasBinaryData) {
            // Decode as UTF-8 string
            return new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }

        // Display as hex for binary data
        StringBuilder hex = new StringBuilder("0x");
        for (byte b : value) {
            hex.append(String.format("%02X", b & 0xFF));
        }
        return hex.toString();
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
        Enumeration<TreeNode> children = node.children();
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
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean addGeneralNode(BonjourBrowserElement element) {
        final String domainName = element.getDomain();
        final String regTypeName = element.getRegType();

        runOnEDT(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

            DefaultMutableTreeNode domain = addNodeInternal(domainName, root, treeModel, true);
            DefaultMutableTreeNode regtype = addNodeInternal(regTypeName, domain, treeModel, true);
            addNodeInternal(PLACEHOLDER_TEXT, regtype, treeModel, false);
        });

        return true;
    }

    /**
     * Removes a general service type node from the JTree.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean removeGeneralNode(BonjourBrowserElement element) {
        final String domainName = element.getDomain();
        final String regTypeName = element.getRegType();

        runOnEDT(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

            DefaultMutableTreeNode dnode = findNode(root, domainName);
            if (dnode == null) {
                return;
            }

            DefaultMutableTreeNode rnode = findNode(dnode, regTypeName);
            if (rnode == null) {
                return;
            }

            // Remove the regtype node (and all its children)
            removeNodeIfEmpty(rnode, treeModel, Integer.MAX_VALUE);
            // Remove domain if now empty
            removeNodeIfEmpty(dnode, treeModel, 0);
        });

        return true;
    }

    // ========================================================================
    // BonjourBrowserInterface Implementation - Subscription
    // ========================================================================

    /**
     * Subscribes a service provider with the domain to the service type.
     * @param domain service provider domain
     * @param regType service type
     * @return true on success
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
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean addNode(BonjourBrowserElement element) {
        final String domainName = element.getDomain();
        final String regTypeName = element.getRegType();
        final String serviceName = element.getName();
        final String fullName = element.getFullName();

        logDebug("addNode: fullName= " + fullName + ", serviceName= " + serviceName +
                ", regType= " + regTypeName + ", domain= " + domainName);

        runOnEDT(() -> {
            logDebug("addNode EDT: processing fullName= " + fullName);

            DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

            DefaultMutableTreeNode domain = addNodeInternal(domainName, root, treeModel, true);
            DefaultMutableTreeNode regtype = addNodeInternal(regTypeName, domain, treeModel, true);

            ignoreTreeExpansion = true;
            DefaultMutableTreeNode name = addNodeInternal(serviceName, regtype, treeModel, true);
            ignoreTreeExpansion = false;

            nodeMap.put(fullName, name);
            logDebug("addNode EDT: stored in nodeMap fullName= " + fullName);
        });

        return true;
    }

    /**
     * Removes a node about BonjourBrowserElement from the JTree.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean removeNode(BonjourBrowserElement element) {
        final String domainName = element.getDomain();
        final String regTypeName = element.getRegType();
        final String serviceName = element.getName();
        final String fullName = element.getFullName();

        // Remove from nodeMap immediately (thread-safe with ConcurrentHashMap)
        nodeMap.remove(fullName);

        runOnEDT(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

            DefaultMutableTreeNode dnode = findNode(root, domainName);
            if (dnode == null) {
                return;
            }

            DefaultMutableTreeNode rnode = findNode(dnode, regTypeName);
            if (rnode == null) {
                return;
            }

            DefaultMutableTreeNode nnode = findNode(rnode, serviceName);
            if (nnode == null) {
                return;
            }

            removeNodeIfEmpty(nnode, treeModel, Integer.MAX_VALUE);
            removeNodeIfEmpty(rnode, treeModel, 0);
            removeNodeIfEmpty(dnode, treeModel, 0);
        });

        return true;
    }

    /**
     * Updates a node with the resolved info in the JTree.
     * @param element BonjourBrowserElement instance
     * @return true on success
     */
    @Override
    public boolean resolveNode(BonjourBrowserElement element) {
        final String fullName = element.getFullName();
        final String hostname = element.getHostname();
        final int port = element.getPort();
        final TXTRecord txtRecord = element.getTxtRecord();

        logDebug("resolveNode: fullName= " + fullName + ", hostname= " + hostname + ", port= " + port);

        // Schedule resolve on EDT - this ensures it runs AFTER any pending addNode EDT work
        runOnEDT(() -> {
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();
            DefaultMutableTreeNode name = nodeMap.get(fullName);

            if (name == null) {
                logWarn("resolveNode EDT: node not found for fullName= " + fullName);
                return;
            }

            logDebug("resolveNode EDT: adding hostname:port= " + hostname + ":" + port + " to node= " + name);

            String hostPort = hostname + ":" + port;
            addNodeInternal(hostPort, name, treeModel, false);

            if (txtRecord != null) {
                for (int i = 0; i < txtRecord.size(); i++) {
                    String key = txtRecord.getKey(i);
                    if (key != null && !key.isEmpty()) {
                        String displayValue = formatTxtValue(txtRecord.getValue(i));
                        addNodeInternal(key + "=" + displayValue, name, treeModel, false);
                    }
                }
            }
        });

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

    /** Enable or disable debug logging */
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
