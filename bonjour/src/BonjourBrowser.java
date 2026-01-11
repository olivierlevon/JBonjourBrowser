import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * <p>Title: Java BonjourBrowser</p>
 *
 * <p>Description: A cross-platform Bonjour/Zeroconf service browser.</p>
 *
 * <p>An application like Bonjour Browser is necessary to view the services that are present
 * and being announced in a network where devices are Bonjour-capable. While Bonjour Browser
 * does a very good job on the Mac OS X platform, we will need an application that can work
 * similarly, display all services and run on several platforms. Hence it was necessary to
 * build a tool like Bonjour Browser in Java that could run on multiple platforms.</p>
 *
 * <p>A tool like Bonjour Browser is especially helpful while troubleshooting service discovery
 * in the IRT Lab's 7DS research, since the project involves mobile disconnected networks
 * where devices are often very transient and services are announced and disappear shortly afterwards.</p>
 *
 * @author Denis Abramov
 * @author Myounghwan Lee
 * @version 1.0
 */
public class BonjourBrowser extends JFrame {

    // ========================================================================
    // Constants
    // ========================================================================

    private static final int WINDOW_WIDTH = 600;
    private static final int WINDOW_HEIGHT = 720;
    private static final int TREE_PANEL_IPADX = 500;
    private static final int TREE_PANEL_IPADY = 600;
    private static final int BUTTON_TOP_INSET = 20;
    private static final int BUTTON_IPADY = 15;

    // Timestamp format for log messages
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // Debug logging enable flag
    private static volatile boolean debugEnabled = false;

    // ========================================================================
    // Instance Fields
    // ========================================================================

    private final JTree tree;
    private final JButton reloadServicesBtn;
    private final JScrollPane treeScrollPane;
    private final BonjourBrowserImpl bonjourBrowserImpl;
    private volatile BonjourBrowserMultiServiceListener multiServiceListener;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Constructs a new BonjourBrowser.<br>
     * Sets the GUI property and initializes the browser implementation.
     */
    public BonjourBrowser() {
        // Initialize UI components
        tree = new JTree();
        reloadServicesBtn = new JButton();
        treeScrollPane = new JScrollPane();

        // Create the browser implementation that manages tree nodes
        bonjourBrowserImpl = new BonjourBrowserImpl(this);

        // Setup UI layout and event handlers
        initComponents();
    }

    // ========================================================================
    // UI Initialization
    // ========================================================================

    private void initComponents() {
        // Use GridBagLayout for flexible component positioning
        GridBagLayout gridBagLayout = new GridBagLayout();
        this.getContentPane().setLayout(gridBagLayout);

        // Configure reload button appearance and behavior
        reloadServicesBtn.setBorder(BorderFactory.createRaisedBevelBorder());
        reloadServicesBtn.setText("Reload Services");
        reloadServicesBtn.addActionListener(new ReloadServicesBtnActionListener());

        // Configure window behavior - use custom close handler for cleanup
        this.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowCloseHandler());
        this.setTitle("Bonjour Service Browser");

        // Configure tree - hide root since we show domains at top level
        tree.setRootVisible(false);
        tree.addTreeExpansionListener(new TreeExpansionHandler());

        // Add tree panel - fills available space (weight 1.0), anchored north, expands both directions
        this.getContentPane().add(treeScrollPane,
                new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0,
                        GridBagConstraints.NORTH, GridBagConstraints.BOTH,
                        new Insets(0, 0, 0, 0), TREE_PANEL_IPADX, TREE_PANEL_IPADY));

        // Add reload button - fixed size (weight 0.0), anchored south, stretches horizontally
        this.getContentPane().add(reloadServicesBtn,
                new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0,
                        GridBagConstraints.SOUTH, GridBagConstraints.HORIZONTAL,
                        new Insets(BUTTON_TOP_INSET, 0, 0, 0), 0, BUTTON_IPADY));

        // Connect tree to scroll pane
        treeScrollPane.setViewportView(tree);
    }

    // ========================================================================
    // Public Accessors
    // ========================================================================

    /**
     * Gets the JTree for the browser.
     * @return the JTree instance
     */
    public JTree getTree() {
        return tree;
    }

    /**
     * Gets the BonjourBrowserImpl instance.
     * @return the BonjourBrowserImpl instance
     */
    public BonjourBrowserImpl getBonjourBrowserImpl() {
        return bonjourBrowserImpl;
    }

    // ========================================================================
    // Application Entry Point
    // ========================================================================

    /**
     * Main method of this class.<br>
     * Creates {@link BonjourBrowser} class<br>
     * Implements BonjourBrowser by {@link BonjourBrowserImpl} class.<br>
     * @param args command line arguments (-debug to enable debug logging)
     */
    public static void main(String[] args) {
        // Check for -debug flag
        for (String arg : args) {
            if ("-debug".equalsIgnoreCase(arg)) {
                setDebugEnabled(true);
                System.out.println("Debug logging enabled");
            }
        }

        // All Swing operations must be on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> {
            BonjourBrowser browser = null;
            try {
                browser = new BonjourBrowser();
                browser.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
                browser.setLocationRelativeTo(null);  // Center on screen
                browser.reloadServices();
                browser.setVisible(true);
            } catch (Exception e) {
                logError("Failed to start BonjourBrowser: " + e.getMessage());
                e.printStackTrace();
                if (browser != null) {
                    browser.dispose();
                }
                JOptionPane.showMessageDialog(null,
                        "Failed to start BonjourBrowser: " + e.getMessage(),
                        "Startup Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Enables or disables debug logging globally for all classes.
     * @param enabled true to enable debug logging
     */
    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        BonjourBrowserImpl.setDebugEnabled(enabled);
        BonjourBrowserMultiServiceListener.setDebugEnabled(enabled);
        BonjourBrowserSingleServiceListener.setDebugEnabled(enabled);
    }

    // ========================================================================
    // Service Management
    // ========================================================================

    /**
     * Reloads the service browser by stopping any active listeners and
     * starting a fresh service discovery.
     *
     * <p>This method:</p>
     * <ul>
     *   <li>Stops the previous multi-service listener if running</li>
     *   <li>Cleans up browser implementation resources</li>
     *   <li>Resets the tree model</li>
     *   <li>Starts a new service discovery browse</li>
     * </ul>
     *
     * <p>Thread Safety: This method can be called from any thread.
     * If called from a non-EDT thread, it will dispatch to the EDT automatically.</p>
     */
    public void reloadServices() {
        // Ensure we're on EDT for Swing operations
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadServices);
            return;
        }

        logInfo("Reloading services...");

        // Stop the previous listener if exists
        BonjourBrowserMultiServiceListener oldListener = multiServiceListener;
        if (oldListener != null) {
            oldListener.stop();
            multiServiceListener = null;
        }

        // Clean up the impl resources
        bonjourBrowserImpl.cleanup();

        // Reset the tree model
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("");
        DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
        tree.setModel(treeModel);

        try {
            multiServiceListener = new BonjourBrowserMultiServiceListener(bonjourBrowserImpl);
            logInfo("Service reload complete");
        } catch (Exception e) {
            logError("Failed to browse services: " + e.getMessage());
            e.printStackTrace();
            JOptionPane.showMessageDialog(this,
                    "Failed to browse services: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Cleans up all resources before shutdown.
     */
    private void cleanup() {
        logInfo("Application shutdown - cleaning up...");

        // Stop the multi-service listener
        BonjourBrowserMultiServiceListener listener = multiServiceListener;
        if (listener != null) {
            listener.stop();
            multiServiceListener = null;
        }

        // Clean up the browser implementation
        bonjourBrowserImpl.cleanup();

        logInfo("Application cleanup completed");
    }

    // ========================================================================
    // Event Handling
    // ========================================================================

    /**
     * Handles tree node expansion events to trigger service discovery.
     *
     * <p>This method only acts when a service type node (depth 3) is expanded:
     * Path structure: [root, domain, regType]. When expanded, it subscribes
     * to that service type to discover individual service instances.</p>
     *
     * <p>Expansion of other nodes (domain nodes, service instance nodes) is ignored.</p>
     *
     * @param e the tree expansion event
     */
    private void handleTreeExpanded(TreeExpansionEvent e) {
        TreePath path = e.getPath();

        // Only react to service type expansions (depth 3: root -> domain -> regType)
        // Ignore domain expansions (depth 2) and service instance expansions (depth 4+)
        if (path.getPathCount() != 3 || bonjourBrowserImpl.isIgnoreTreeExpansion()) {
            return;
        }

        // Extract domain and service type from tree path
        // Path structure: [invisible root, domain (e.g. "local."), regType (e.g. "_http._tcp.")]
        DefaultMutableTreeNode domain = (DefaultMutableTreeNode) path.getPathComponent(1);
        DefaultMutableTreeNode regType = (DefaultMutableTreeNode) path.getPathComponent(2);

        // Get string values - toString() handles null userObject safely
        String domainStr = domain.toString();
        String regTypeStr = regType.toString();

        // Start browsing for services of this type in this domain
        bonjourBrowserImpl.subscribe(domainStr, regTypeStr);
    }

    // ========================================================================
    // Logging Helpers
    // ========================================================================

    private static String ts() {
        return "[" + LocalTime.now().format(TIME_FMT) + "]";
    }

    private static void logInfo(String msg) {
        System.out.println(ts() + " INFO  [BonjourBrowser] " + msg);
    }

    private static void logError(String msg) {
        System.err.println(ts() + " ERROR [BonjourBrowser] " + msg);
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    private class ReloadServicesBtnActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            reloadServices();
        }
    }

    private class TreeExpansionHandler implements TreeExpansionListener {
        @Override
        public void treeCollapsed(TreeExpansionEvent e) {
            // No action needed on collapse
        }

        @Override
        public void treeExpanded(TreeExpansionEvent e) {
            handleTreeExpanded(e);
        }
    }

    private class WindowCloseHandler extends WindowAdapter {
        @Override
        public void windowClosing(WindowEvent e) {
            cleanup();
            dispose();
            System.exit(0);
        }
    }
}
