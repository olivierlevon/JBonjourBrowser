import com.apple.dnssd.*;
import java.util.regex.*;

/**
  * Class for implementation of a listener for specific service advertisements.<br>
*/

public class BonjourBrowserSingleServiceListener implements BrowseListener, ResolveListener
{
/**
  *   BonjourBrowserInterface instance for the GUI of the browser
*/
  protected BonjourBrowserInterface _gui_browser;
/**
  * Constructs a new BonjourBrowserSingleServiceListener.<br>
  * Implements a listener for specific service advertisements.<br>
  * @param gui_intf BonjourBrowserInterface instance of the browser<br>
*/

  public BonjourBrowserSingleServiceListener(BonjourBrowserInterface gui_intf) {
    _gui_browser = gui_intf;
  }

  /**
    * Displays error message on failure.<br>
    * @param service DNSSDService instance<br>
    * @param errorCode the errocode to display <br>
  */
  public void operationFailed(DNSSDService service, int errorCode) {
    System.out.println("Browse failed " + errorCode);
    System.exit( -1);
  }

  /**
    * Displays services we discover.<br>
    * @param browser DNSSDService instance<br>
    * @param flags flags for use <br>
    * @param ifIndex ifIndex for use <br>
    * @param serviceName the service provider name to find <br>
    * @param regType the service provider information to find<br>
    * @param domain the service provider domain to find<br>
    * @see BonjourBrowserElement
    * @see BonjourBrowserInterface#addGeneralNode(BonjourBrowserElement element)
  */
  public void serviceFound(DNSSDService browser, int flags, int ifIndex,
                           String serviceName, String regType, String domain) {

    System.out.println("ADD flags:" + flags + ", ifIndex:" + ifIndex +
                       ", Name:" + serviceName + ", Type:" + regType +
                       ", Domain:" +
                       domain);

    try {
        String fullName = DNSSD.constructFullName(serviceName, regType, domain);
        _gui_browser.addNode(new BonjourBrowserElement(browser, flags, ifIndex, fullName, serviceName, regType, domain));
        DNSSDService r = DNSSD.resolve(0, DNSSD.ALL_INTERFACES, serviceName, regType, domain, this);

      }
      catch (Exception e) {
        e.printStackTrace();
      }
  }

/**
  * Removes the services when services go away.<br>
  * @param browser DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param name the service provider name to go away <br>
  * @param regType the service provider information to go away<br>
  * @param domain the service provider domain to go away<br>
  * @see BonjourBrowserElement
  * @see BonjourBrowserInterface#removeNode(BonjourBrowserElement element)
*/
 public void serviceLost(DNSSDService browser, int flags, int ifIndex,
                          String name, String regType, String domain) {
    System.out.println("REMOVE flags:" + flags + ", ifIndex:" + ifIndex +
                       ", Name:" + name + ", Type:" + regType + ", Domain:" +
                       domain);

      try {
        String fullName = DNSSD.constructFullName(name, regType, domain);
        _gui_browser.removeNode(new BonjourBrowserElement(browser, flags, ifIndex,
            fullName, name, regType, domain));
      }
      catch (Exception e) {
        e.printStackTrace();
      }
    }
/**
  * Resolves the services when services are resolved.<br>
  * @param resolver DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param fullName the service provider name to be resolved<br>
  * @param hostName the service provider hostname to be resolved<br>
  * @param port the service provider port to be resolved<br>
  * @param txtRecord the service provider txtrecord to be resolved<br>
  * @see BonjourBrowserElement
  * @see BonjourBrowserInterface#resolveNode(BonjourBrowserElement element)
*/
  public void serviceResolved( DNSSDService resolver, int flags, int ifIndex, String fullName,
                                                         String hostName, int port, TXTRecord txtRecord)
 {
   System.out.println("RESOLVE flags:" + flags + ", ifIndex:" + ifIndex +
                ", Name:" + fullName + ", Hostname:" + hostName + ", port:" +
                port + ", TextRecord:" + txtRecord);

  _gui_browser.resolveNode(new BonjourBrowserElement(resolver, flags, ifIndex, fullName, hostName, port, txtRecord));
 }
}

