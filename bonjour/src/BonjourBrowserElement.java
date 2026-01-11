import com.apple.dnssd.*;

/**
  * Class to encapsulate the structures that Bonjour returns
*/

public class BonjourBrowserElement {
/**
 *   DNSSDService instance for the browser
*/

  public DNSSDService _browser;
  int _flags, _ifIndex, _port;
  String _fullName, _name, _hostname, _regType, _domain;
  TXTRecord _txtRecord;

/**
  * Creates browser elements to inplement browser interface when service is  found.<br>
  * class to encapsulate the structures that Bonjour returns
  * @param browser DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param fullName the service provider full name<br>
  * @param name the service provider name to be resolved<br>
  * @param regType the service type<br>
  * @param domain the service provider domain<br>
  * @see BonjourBrowserInterface
  * @see BonjourBrowserImpl
*/

  public BonjourBrowserElement(DNSSDService browser, int flags, int ifIndex,
                           String fullName, String name, String regType, String domain) {
  _browser = browser;
  _flags = flags;
  _ifIndex = ifIndex;
  _fullName = fullName;
  _name = name;
  _regType = regType;
  _domain = domain;
  }

/**
  * Creates browser elements to inplement browser interface when service is resolved.<br>
  * @param resolver DNSSDService instance<br>
  * @param flags flags for use <br>
  * @param ifIndex ifIndex for use <br>
  * @param fullName the service provider name<br>
  * @param hostName the service provider hostname<br>
  * @param port the service provider port<br>
  * @param txtRecord the service provider txtrecord<br>
  * @see BonjourBrowserInterface
  * @see BonjourBrowserImpl
*/

  public BonjourBrowserElement(DNSSDService resolver, int flags, int ifIndex, String fullName,
                                                         String hostName, int port, TXTRecord txtRecord)
 {
   _browser = resolver;
   _flags = flags;
   _ifIndex = ifIndex;
   _fullName = fullName;
   _hostname = hostName;
   _port = port;
   _txtRecord = txtRecord;
 }

}
