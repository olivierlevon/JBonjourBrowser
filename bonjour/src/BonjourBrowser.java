import javax.swing.*;
import javax.swing.tree.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Application version and URLs
    private static final String VERSION = "2.0.0";
    private static final String GITHUB_URL = "https://github.com/olivierlevon/JBonjourBrowser";
    private static final String README_URL = GITHUB_URL + "#readme";
    private static final String RELEASES_API_URL = "https://api.github.com/repos/olivierlevon/JBonjourBrowser/releases/latest";

    // Debug logging enable flag
    private static volatile boolean debugEnabled = false;

    // ========================================================================
    // Instance Fields
    // ========================================================================

    /** The tree component displaying services hierarchy. */
    private final JTree tree;
    /** Button to reload/refresh the service list. */
    private final JButton reloadServicesBtn;
    /** Scroll pane containing the tree. */
    private final JScrollPane treeScrollPane;
    /** Implementation handling tree node management. */
    private final BonjourBrowserImpl bonjourBrowserImpl;
    /** Listener for multi-service discovery (meta-query). */
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

        // Create menu bar with Help menu
        JMenuBar menuBar = new JMenuBar();
        JMenu helpMenu = new JMenu("Help");
        JMenuItem docItem = new JMenuItem("Online Documentation");
        docItem.addActionListener(e -> openDocumentation());
        helpMenu.add(docItem);
        JMenuItem updateItem = new JMenuItem("Check for Updates...");
        updateItem.addActionListener(e -> checkForUpdates());
        helpMenu.add(updateItem);
        helpMenu.addSeparator();
        JMenuItem aboutItem = new JMenuItem("About...");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);
        menuBar.add(helpMenu);
        this.setJMenuBar(menuBar);

        // Configure tree - hide root since we show domains at top level
        tree.setRootVisible(false);
        tree.addTreeExpansionListener(new TreeExpansionHandler());

        // Use custom cell renderer with HTML for Unicode font fallback
        tree.setCellRenderer(new UnicodeCellRenderer());

        // Add copy functionality (Ctrl+C and right-click menu)
        setupCopySupport();

        // Add tree panel - fills available space (weight 1.0), anchored north, expands both directions
        this.getContentPane().add(treeScrollPane,
                new GridBagConstraints(0, 0, 1, 1, 1.0, 1.0,
                        GridBagConstraints.NORTH, GridBagConstraints.BOTH,
                        new Insets(0, 0, 0, 0), TREE_PANEL_IPADX, TREE_PANEL_IPADY));

        // Add reload button - fixed size (weight 0.0), centered
        this.getContentPane().add(reloadServicesBtn,
                new GridBagConstraints(0, 1, 1, 1, 0.0, 0.0,
                        GridBagConstraints.CENTER, GridBagConstraints.NONE,
                        new Insets(BUTTON_TOP_INSET, 0, 10, 0), 40, BUTTON_IPADY));

        // Connect tree to scroll pane
        treeScrollPane.setViewportView(tree);
    }

    /**
     * Sets up copy support for the tree (Ctrl+C and right-click context menu).
     */
    private void setupCopySupport() {
        // Create popup menu with Copy option
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK));
        copyItem.addActionListener(e -> copySelectedNodeToClipboard());
        popupMenu.add(copyItem);

        // Add right-click listener
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    // Select the node under cursor if not already selected
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        tree.setSelectionPath(path);
                    }
                    if (tree.getSelectionPath() != null) {
                        popupMenu.show(tree, e.getX(), e.getY());
                    }
                }
            }
        });

        // Add Ctrl+C keyboard shortcut
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), "copy");
        tree.getActionMap().put("copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                copySelectedNodeToClipboard();
            }
        });
    }

    /**
     * Copies the selected tree node's text to the system clipboard.
     */
    private void copySelectedNodeToClipboard() {
        TreePath path = tree.getSelectionPath();
        if (path != null) {
            Object node = path.getLastPathComponent();
            String text = node.toString();
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            logInfo("Copied to clipboard: " + text);
        }
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

        // Display Java version info
        System.out.println("=== Java Environment ===");
        System.out.println("Java Version: " + System.getProperty("java.version"));
        System.out.println("Java Vendor: " + System.getProperty("java.vendor"));
        System.out.println("Java Home: " + System.getProperty("java.home"));
        System.out.println("File Encoding: " + System.getProperty("file.encoding"));
        System.out.println("Default Charset: " + java.nio.charset.Charset.defaultCharset());
        System.out.println("========================");

        // Set FlatLaf Look and Feel for better Unicode font support
        try {
            UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());
        } catch (Exception e) {
            logError("Failed to set FlatLaf Look and Feel: " + e.getMessage());
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

    /**
     * Shows the About dialog with application information.
     */
    private void showAboutDialog() {
        // Create message with clickable links
        JEditorPane editorPane = new JEditorPane("text/html", """
                <html>
                <body style="margin-right: 15px;">
                <h2>Java Bonjour Browser</h2>
                <p><b>Version %s</b></p>
                <p>A cross-platform Bonjour/Zeroconf service browser.</p>
                <p><b>Original Authors (2006):</b><br>
                Denis Abramov, Myounghwan Lee<br>
                Internet Real Time Lab, Columbia University<br>
                <a href="https://www.cs.columbia.edu/~hgs/research/projects/bonjour/report/project.htm">Original Project</a></p>
                <p><b>Reworked by (2026):</b><br>
                Olivier Levon</p>
                <p><b>Repository:</b><br>
                <a href="%s">%s</a></p>
                <p style="margin-bottom: 15px;"><b>License:</b><br>
                <a href="https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html">LGPL-2.1+</a></p>
                <hr>
                <p style="color: gray;">
                <b>Java:</b> %s (%s)<br>
                <b>Encoding:</b> %s<br>
                <b>OS:</b> %s %s</p>
                </body>
                </html>
                """.formatted(
                    VERSION,
                    GITHUB_URL, GITHUB_URL,
                    System.getProperty("java.version"),
                    System.getProperty("java.vendor"),
                    java.nio.charset.Charset.defaultCharset(),
                    System.getProperty("os.name"),
                    System.getProperty("os.version")
                ));

        editorPane.setEditable(false);
        editorPane.setOpaque(false);
        editorPane.addHyperlinkListener(e -> {
            if (e.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (Exception ex) {
                    logError("Failed to open link: " + ex.getMessage());
                }
            }
        });

        JOptionPane.showMessageDialog(this,
                editorPane,
                "About Bonjour Browser",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Opens the online documentation (GitHub README) in the default browser.
     */
    private void openDocumentation() {
        try {
            Desktop.getDesktop().browse(new URI(README_URL));
        } catch (Exception e) {
            logError("Failed to open documentation: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Could not open browser.\nPlease visit: " + README_URL,
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Checks for updates by querying the GitHub releases API.
     */
    private void checkForUpdates() {
        // Run in background to avoid blocking UI
        Thread updateThread = new Thread(() -> {
            try {
                URL url = new URI(RELEASES_API_URL).toURL();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    // Simple JSON parsing for "tag_name"
                    String json = response.toString();
                    String latestVersion = extractTagName(json);

                    SwingUtilities.invokeLater(() -> {
                        if (latestVersion != null && !latestVersion.equals("v" + VERSION)
                                && !latestVersion.equals(VERSION)) {
                            int choice = JOptionPane.showConfirmDialog(this,
                                    "A new version is available: " + latestVersion + "\n" +
                                    "Current version: " + VERSION + "\n\n" +
                                    "Would you like to visit the download page?",
                                    "Update Available",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.INFORMATION_MESSAGE);
                            if (choice == JOptionPane.YES_OPTION) {
                                try {
                                    Desktop.getDesktop().browse(new URI(GITHUB_URL + "/releases/latest"));
                                } catch (Exception e) {
                                    logError("Failed to open releases page: " + e.getMessage());
                                }
                            }
                        } else {
                            JOptionPane.showMessageDialog(this,
                                    "You are running the latest version (" + VERSION + ").",
                                    "No Updates Available",
                                    JOptionPane.INFORMATION_MESSAGE);
                        }
                    });
                }
            } catch (Exception e) {
                logError("Failed to check for updates: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this,
                            "Could not check for updates.\nPlease check your internet connection.",
                            "Update Check Failed",
                            JOptionPane.WARNING_MESSAGE);
                });
            }
        }, "UpdateChecker");
        updateThread.setDaemon(true);
        updateThread.start();
    }

    // Regex pattern for extracting tag_name from GitHub API JSON response
    // Matches: "tag_name": "value" or "tag_name":"value" (with optional whitespace)
    // Uses non-greedy matching to avoid capturing beyond the closing quote
    // Handles escaped quotes within the value via negative lookbehind
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    /**
     * Extracts the tag_name value from a GitHub API JSON response.
     * Uses regex for more robust parsing than naive string manipulation.
     * @param json the JSON response string
     * @return the tag_name value, or null if not found or invalid
     */
    private static String extractTagName(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Matcher matcher = TAG_NAME_PATTERN.matcher(json);
        if (matcher.find()) {
            String value = matcher.group(1);
            // Unescape common JSON escape sequences
            return value.replace("\\\"", "\"")
                       .replace("\\\\", "\\")
                       .replace("\\/", "/");
        }
        return null;
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

    /**
     * Custom tree cell renderer that uses HTML rendering for Unicode font fallback.
     * HTML text in Swing uses composite font rendering which handles
     * characters from multiple Unicode blocks (Tamil, Sinhala, CJK, emoji, etc.)
     */
    private static class UnicodeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            // Get the default rendering
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);

            // Wrap text in HTML to enable font fallback for Unicode characters
            String text = value != null ? value.toString() : "";
            // Escape HTML special characters
            text = text.replace("&", "&amp;")
                       .replace("<", "&lt;")
                       .replace(">", "&gt;");
            setText("<html>" + text + "</html>");

            return this;
        }
    }
}
