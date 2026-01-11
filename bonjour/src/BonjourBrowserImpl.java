import javax.swing.*;
import javax.swing.tree.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.apple.dnssd.*;

/**
 * Class to implement the BonjourBrowserInterface.
 * Manages the tree structure and service subscriptions.
 * All Swing operations are dispatched to the Event Dispatch Thread (EDT).
 */
public class BonjourBrowserImpl implements BonjourBrowserInterface {

    private static final String PLACEHOLDER_TEXT = "Place Holder";

    private volatile boolean ignoreTreeExpansion = false;
    private final Map<String, DefaultMutableTreeNode> nodeMap = new ConcurrentHashMap<>();
    private final BonjourBrowser browser;
    private final BonjourBrowserSingleServiceListener singleServiceListener;
    private DNSSDService browserForSingleServices;

    /**
     * Constructs a new BonjourBrowserImpl.<br>
     * Implements the interface of the browser.
     * @param browser BonjourBrowser instance of the browser
     */
    public BonjourBrowserImpl(BonjourBrowser browser) {
        this.browser = browser;
        this.singleServiceListener = new BonjourBrowserSingleServiceListener(this);
    }

    /**
     * Checks if tree expansion events should be ignored.
     * @return true if tree expansion should be ignored
     */
    public boolean isIgnoreTreeExpansion() {
        return ignoreTreeExpansion;
    }

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
        DefaultMutableTreeNode existingNode = findNode(parent, childName);
        if (existingNode == null) {
            existingNode = new DefaultMutableTreeNode(childName);
            treeModel.insertNodeInto(existingNode, parent, parent.getChildCount());
            if (openUp) {
                browser.getTree().scrollPathToVisible(new TreePath(existingNode.getPath()));
            }
        }
        return existingNode;
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

    /**
     * Subscribes a service provider with the domain to the service type.
     * @param domain service provider domain
     * @param regType service type
     * @return true on success
     */
    @Override
    public synchronized boolean subscribe(String domain, String regType) {
        try {
            // Stop previous browser and all active resolvers
            if (browserForSingleServices != null) {
                browserForSingleServices.stop();
                browserForSingleServices = null;
            }
            singleServiceListener.stopAllResolvers();

            // Clear children on EDT
            runOnEDT(() -> {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
                DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

                DefaultMutableTreeNode ndomain = findNode(root, domain);
                DefaultMutableTreeNode nregtype = findNode(ndomain, regType);

                // Remove all children from the regtype node
                if (nregtype != null) {
                    List<DefaultMutableTreeNode> childrenToRemove = new ArrayList<>();
                    for (int i = 0; i < nregtype.getChildCount(); i++) {
                        childrenToRemove.add((DefaultMutableTreeNode) nregtype.getChildAt(i));
                    }
                    for (DefaultMutableTreeNode child : childrenToRemove) {
                        treeModel.removeNodeFromParent(child);
                    }
                }
            });

            browserForSingleServices = DNSSD.browse(0, DNSSD.ALL_INTERFACES, regType, domain, singleServiceListener);

        } catch (DNSSDException e) {
            System.err.println("Failed to subscribe to service type " + regType + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        return true;
    }

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

        runOnEDT(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) browser.getTree().getModel().getRoot();
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();

            DefaultMutableTreeNode domain = addNodeInternal(domainName, root, treeModel, true);
            DefaultMutableTreeNode regtype = addNodeInternal(regTypeName, domain, treeModel, true);

            ignoreTreeExpansion = true;
            DefaultMutableTreeNode name = addNodeInternal(serviceName, regtype, treeModel, true);
            ignoreTreeExpansion = false;

            nodeMap.put(fullName, name);
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

        // Check if node exists (ConcurrentHashMap is thread-safe)
        if (!nodeMap.containsKey(fullName)) {
            return false;
        }

        runOnEDT(() -> {
            DefaultTreeModel treeModel = (DefaultTreeModel) browser.getTree().getModel();
            DefaultMutableTreeNode name = nodeMap.get(fullName);

            if (name == null) {
                return;
            }

            String hostPort = hostname + ":" + port;
            addNodeInternal(hostPort, name, treeModel, false);

            if (txtRecord != null) {
                for (int i = 0; i < txtRecord.size(); i++) {
                    String key = txtRecord.getKey(i);
                    String value = txtRecord.getValueAsString(i);
                    if (key != null && !key.isEmpty()) {
                        String displayValue = (value != null) ? value : "";
                        addNodeInternal(key + "=" + displayValue, name, treeModel, false);
                    }
                }
            }
        });

        return true;
    }

    /**
     * Stops the browser for single services and cleans up all resources.
     */
    public synchronized void cleanup() {
        // Stop active resolvers first
        singleServiceListener.stopAllResolvers();

        // Stop the browser
        if (browserForSingleServices != null) {
            browserForSingleServices.stop();
            browserForSingleServices = null;
        }

        // Clear the node map
        nodeMap.clear();
    }
}
