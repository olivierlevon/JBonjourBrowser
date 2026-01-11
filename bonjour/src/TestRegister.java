import java.net.*;
import com.apple.dnssd.*;

class TestRegister implements RegisterListener
  {
  // Display error message on failure
  public void operationFailed(DNSSDService service, int errorCode)
    {
    System.out.println("Registration failed " + errorCode);
    }

  // Display registered name on success
  public void serviceRegistered(DNSSDRegistration registration, int flags,
    String serviceName, String regType, String domain)
    {
    System.out.println("Registered Name  : " + serviceName);
    System.out.println("           Type  : " + regType);
    System.out.println("           Domain: " + domain);
    }

  // Do the registration
  public TestRegister(String name, int port)
    throws DNSSDException, InterruptedException
    {
    System.out.println("Registration Starting");
    System.out.println("Requested Name: " + name);
    System.out.println("          Port: " + port);

    TXTRecord txtRecord = new TXTRecord(  );
    txtRecord.set("txtvers", "1");
    txtRecord.set("status", "ready");
    txtRecord.set("difficulty", "medium");

    for (int i = 0; i < txtRecord.size(  ); i++)
          {
          String key = txtRecord.getKey(i);
          String value = txtRecord.getValueAsString(i);
          if (key.length() > 0)
            System.out.println("     *** " + key + "=" + value);
          }

    DNSSDRegistration r = DNSSD.register(0, DNSSD.ALL_INTERFACES,
                                         name, "_example._tcp", null,  // Name, type, and domain
                                         null, port,                   // Target host and port
                                         txtRecord, this);             // TXT record and listener object

    Thread.sleep(600000);  // Wait thirty seconds, then exit
    System.out.println("Registration Stopping");
    r.stop(  );
    }

  public static void main(String[] args)
    {
    if (args.length > 1)
      {
      System.out.println("Usage: java TestRegister name");
      System.exit(-1);
      }
    else
      {
      try
        {
        // If name specified, use it, else use default name
        String name = (args.length > 0) ? args[0] : "Magic Service";
        // Let system allocate us an available port to listen on
        ServerSocket s = new ServerSocket(0);
        new TestRegister(name, s.getLocalPort(  ));
        }
      catch(Exception e)
        {
        e.printStackTrace(  );
        System.exit(-1);
        }
      }
    }
  }
