/**
  * Interface to implement BonjourBrowserImpl class
*/

public interface BonjourBrowserInterface {

/**
  * Adds a node about BonjourBrowserElement to the JTree.<br>
  * @param element BonjourBrowserElement instance<br>
  * @return true <br>
*/
  public boolean addNode(BonjourBrowserElement element);

/**
    * Adds a general service type node to the JTree.<br>
    * @param element BonjourBrowserElement instance<br>
    * @return true <br>
*/
  public boolean addGeneralNode(BonjourBrowserElement element);

/**
    * Removes a node about BonjourBrowserElement from the JTree.<br>
    * @param element BonjourBrowserElement instance<br>
    * @return true <br>
*/
  public boolean removeNode(BonjourBrowserElement element);

/**
      * Updates a node with the resolved info in the JTree.<br>
      * @param element BonjourBrowserElement instance<br>
      * @return true <br>
*/
  public boolean resolveNode(BonjourBrowserElement element);

/**
        * Subscribes a service provider with the domain to the service type.<br>
        * @param domain service provider domain<br>
        * @param regType service type<br>
        * @return true <br>
*/
  public boolean subscribe(String domain, String regType);
}
