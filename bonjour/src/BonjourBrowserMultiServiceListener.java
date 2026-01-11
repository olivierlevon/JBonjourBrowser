import com.apple.dnssd.*;
import java.util.regex.*;

/**
  * Class for implementation of a listener for general service advertisements.<br>
*/

public class BonjourBrowserMultiServiceListener implements BrowseListener, ResolveListener
{
/**
 *   DNSSDService instance for single services
*/
  protected DNSSDService _browser_for_services;
/**
 *   BonjourBrowserInterface instance for the GUI of the browser
*/
  protected BonjourBrowserInterface _gui_browser;

/**
  * Constructs a new BonjourBrowserMultiServiceListener.<br>
  * Implements a listener for general service advertisements.
  * @param browser BonjourBrowserInterface instance of the browser<br>
*/
  public BonjourBrowserMultiServiceListener(BonjourBrowserInterface browser) throws DNSSDException, InterruptedException {
     System.out.println("TestBrowse Starting");
     _browser_for_services = DNSSD.browse( 0, 0, "_services._dns-sd._udp.", "", this);
     _gui_browser = browser;
     System.out.println("TestBrowse Running");
  }

/**
  * Dsplays error message on failure.<br>
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
        domain = regType.substring(regType.indexOf(".")+1, regType.length());
        regType = serviceName + (regType.startsWith("_udp.") ? "._udp." : "._tcp.");
        _gui_browser.addGeneralNode(new BonjourBrowserElement(browser, flags, ifIndex, "", serviceName, regType, domain));
      }
      catch (Exception e) {
        e.printStackTrace();
      }
  }
/**
  * Prints a line when services go away.<br>
  * @param browser DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param name the service provider name to go away <br>
  * @param regType the service provider information to go away<br>
  * @param domain the service provider domain to go away<br>
*/

  public void serviceLost(DNSSDService browser, int flags, int ifIndex,
                          String name, String regType, String domain) {
    System.out.println("REMOVE flags:" + flags + ", ifIndex:" + ifIndex +
                       ", Name:" + name + ", Type:" + regType + ", Domain:" +
                       domain);
    }
/**
  * Prints a line when services are resolved.<br>
  * @param resolver DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param fullName the service provider name to be resolved<br>
  * @param hostName the service provider hostname to be resolved<br>
  * @param port the service provider port to be resolved<br>
  * @param txtRecord the service provider txtrecord to be resolved<br>
*/
  public void serviceResolved( DNSSDService resolver, int flags, int ifIndex, String fullName,
                                                         String hostName, int port, TXTRecord txtRecord)
 {
   System.out.println("RESOLVE flags:" + flags + ", ifIndex:" + ifIndex +
                ", Name:" + fullName + ", Hostname:" + hostName + ", port:" +
                port + ", TextRecord:" + txtRecord);

 }
 /**
   * Converts a registration type into a human-readable string. Returns original string on no-match.<br>
   * @param type the service type to map<br>
   * @return type the human-readable service type<br>
*/

  protected String	mapTypeToName( String type)
       // Convert a registration type into a human-readable string. Returns original string on no-match.
       {
               final String[]	namedServices = {
                       "_afpovertcp",	"Apple File Sharing",
                       "_http",		"World Wide Web servers",
                       "_daap",		"Digital Audio Access",
                       "_apple-sasl",	"Apple Password Servers",
                       "_distcc",		"Distributed Compiler nodes",
                       "_finger",		"Finger servers",
                       "_ichat",		"iChat clients",
                       "_presence",	"iChat AV clients",
                       "_ssh",			"SSH servers",
                       "_telnet",		"Telnet servers",
                       "_workstation",	"Macintosh Manager clients",
                       "_bootps",		"BootP servers",
                       "_xserveraid",	"XServe RAID devices",
                       "_eppc",		"Remote AppleEvents",
                       "_ftp",			"FTP services",
                       "_tftp",		"TFTP services"
               };

               for ( int i = 0; i < namedServices.length; i+=2)
                       if ( namedServices[i].equals( type))
                               return namedServices[i + 1];
               return type;
       }

/**
  * Stops browsing services.<br>
*/
  protected void finalize() {
    System.out.println("TestBrowse Stopping");
    _browser_for_services.stop();
  }

  private void jbInit() throws Exception {
  }
}


