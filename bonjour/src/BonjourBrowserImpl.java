import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.util.*;
import com.borland.jbcl.layout.*;
import javax.swing.tree.*;
import javax.swing.text.*;
import com.apple.dnssd.*;

/**
  * Class to implement the BonjourBrowserInterface
*/

public class BonjourBrowserImpl implements BonjourBrowserInterface {

/**
*   Ignores tree expansion, if true.
*/
  public boolean _ignore_tree_expansion = false;
/**
* Hash map for the tree of the browser
*/
  protected HashMap _map = new HashMap();
/**
*   BonjourBrowser instance for the browser
*/
  protected BonjourBrowser _browser;
/**
*   BonjourBrowserSingleServiceListener instance for single services of the browser
*/
  protected BonjourBrowserSingleServiceListener _single_service_listener;
/**
*   DNSSDService instance for single services
*/
  protected DNSSDService _browser_for_single_services;

/**
  * Constructs a new BonjourBrowserImpl.<br>
  * Implements the interface of the browser.
  * @param browser BonjourBrowser instance of the browser<br>
*/

  public BonjourBrowserImpl(BonjourBrowser browser) {
    _browser = browser;
    _single_service_listener = new BonjourBrowserSingleServiceListener(this);
  }

  private DefaultMutableTreeNode addNode(String child_name,
                                          DefaultMutableTreeNode parent,
                                          DefaultTreeModel treeModel,
                                          boolean open_up) {
      DefaultMutableTreeNode dnode = findNode( (DefaultMutableTreeNode) parent, child_name);
      if (dnode == null) {
        dnode = new DefaultMutableTreeNode(child_name);
        treeModel.insertNodeInto(dnode, parent, parent.getChildCount());
        if (open_up)
        _browser.tree.scrollPathToVisible(new TreePath(dnode.getPath()));
      }
      return dnode;
  }

/**
  * Adds a general service type node to the JTree.<br>
  * @param element BonjourBrowserElement instance<br>
  * @return true <br>
*/

  public synchronized boolean addGeneralNode(BonjourBrowserElement element) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) _browser.tree.getModel().getRoot();
    DefaultTreeModel treeModel = ( (DefaultTreeModel) _browser.tree.getModel());

    //domain node
    DefaultMutableTreeNode domain = addNode(element._domain, root, treeModel, true);
    DefaultMutableTreeNode regtype = addNode(element._regType, domain, treeModel, true);
    DefaultMutableTreeNode place_holder = addNode("Place Holder", regtype, treeModel, false);
    return true;
  }

/**
  * Subscribes a service provider with the domain to the service type.<br>
  * @param domain service provider domain<br>
  * @param regType service type<br>
  * @return true <br>
*/

  public synchronized boolean subscribe(String domain, String regType) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) _browser.tree.getModel().getRoot();
    DefaultTreeModel treeModel = ( (DefaultTreeModel) _browser.tree.getModel());

    try {
    if (_browser_for_single_services != null) {
      _browser_for_single_services.stop();
      _browser_for_single_services = null;
    }

    DefaultMutableTreeNode ndomain = findNode(root, domain);
    DefaultMutableTreeNode nregtype = findNode(ndomain, regType);
    for (int i = 0; i < nregtype.getChildCount(); i++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) nregtype.getChildAt(i);
      treeModel.removeNodeFromParent(child);
    }

    _browser_for_single_services = DNSSD.browse( 0, 0, regType, "", _single_service_listener);

    } catch (Exception e) {
      e.printStackTrace();
    }

    return true;
  }

/**
  * Adds a node about BonjourBrowserElement to the JTree.<br>
  * @param element BonjourBrowserElement instance<br>
  * @return true <br>
*/

  public synchronized boolean addNode(BonjourBrowserElement element) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) _browser.tree.getModel().getRoot();
    DefaultTreeModel treeModel = ( (DefaultTreeModel) _browser.tree.getModel());

    //domain node
    DefaultMutableTreeNode domain = addNode(element._domain, root, treeModel, true);
    DefaultMutableTreeNode regtype = addNode(element._regType, domain, treeModel, true);
    this._ignore_tree_expansion = true;
    DefaultMutableTreeNode name = addNode(element._name, regtype, treeModel, true);
    this._ignore_tree_expansion = false;
    _map.put(element._fullName, name);

    return true;
  }

  private DefaultMutableTreeNode findNode(TreeNode node, String name) {
    if (node.getChildCount() >= 0) {
      for (Enumeration e = node.children(); e.hasMoreElements(); ) {
        DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
        if (name.equals(n.toString())) {
          return n;
        }
      }
    }
    return null;
  }


  private boolean removeNode(DefaultMutableTreeNode node, int childCount) {

    if (node != null && node.getChildCount() <= childCount) {
      ( (DefaultTreeModel) _browser.tree.getModel()).removeNodeFromParent(node);
      return true;
    }
    else {
      return false;
    }
  }

/**
  * Removes a node about BonjourBrowserElement from the JTree.<br>
  * @param element BonjourBrowserElement instance<br>
  * @return true <br>
*/

  public synchronized boolean removeNode(BonjourBrowserElement element) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) _browser.tree.getModel().
        getRoot();

    //domain node
    DefaultMutableTreeNode dnode = findNode( (DefaultMutableTreeNode) _browser.tree.
                                            getModel().getRoot(),
                                            element._domain);
    if (dnode == null) {
      return false;
    }

    //reg node
    DefaultMutableTreeNode rnode = findNode(dnode, element._regType);
    if (rnode == null) {
      return false;
    }

    //name node
    DefaultMutableTreeNode nnode = findNode(rnode, element._name);
    if (nnode == null) {
      return false;
    }

    removeNode(nnode, 9999);
    removeNode(rnode, 0);
    removeNode(dnode, 0);
    _map.remove(element._fullName);
    return true;
  }

/**
  * Updates a node with the resolved info in the JTree.<br>
  * @param element BonjourBrowserElement instance<br>
  * @return true <br>
*/

  public synchronized boolean resolveNode(BonjourBrowserElement element) {

    DefaultTreeModel treeModel = ( (DefaultTreeModel) _browser.tree.getModel());
    DefaultMutableTreeNode name = (DefaultMutableTreeNode) _map.get(element._fullName);
    String ip = element._hostname + ":" + element._port;
    addNode(ip, name, treeModel, false);

    //add the TXTRecord
    if (element._txtRecord != null) {
      for (int i = 0; i < element._txtRecord.size(); i++) {
        String key = element._txtRecord.getKey(i);
        String value = element._txtRecord.getValueAsString(i);
        if (key.length() > 0)
          addNode(key + "=" + value, name, treeModel, false);
      }
    }

    return true;
  }


// Finds the path in tree as specified by the array of names. The names array is a
  // sequence of names where names[0] is the root and names[i] is a child of names[i-1].
  // Comparison is done using String.equals(). Returns null if not found.
  private TreePath findByName(JTree tree, String[] names) {
    TreeNode root = (TreeNode) tree.getModel().getRoot();
    return find2(tree, new TreePath(root), names, 0, true);
  }

  private TreePath find2(JTree tree, TreePath parent, Object[] nodes, int depth,
                         boolean byName) {
    TreeNode node = (TreeNode) parent.getLastPathComponent();
    Object o = node;

    // If by name, convert node to a string
    if (byName) {
      o = o.toString();
    }

    // If equal, go down the branch
    if (o.equals(nodes[depth])) {
      // If at end, return match
      if (depth == nodes.length - 1) {
        return parent;
      }

      // Traverse children
      if (node.getChildCount() >= 0) {
        for (Enumeration e = node.children(); e.hasMoreElements(); ) {
          TreeNode n = (TreeNode) e.nextElement();
          TreePath path = parent.pathByAddingChild(n);
          TreePath result = find2(tree, path, nodes, depth + 1, byName);
          // Found a match
          if (result != null) {
            return result;
          }
        }
      }
    }

    // No match at this branch
    return null;
  }

}
