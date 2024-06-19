
# Synaccess Binding

This is a binding for the netBooter series of [Synaccess](http://www.synaccess.com) Power Distribution Units (PDUs).  HTTP, Telnet and RS232 serial control is supported by the devices, but the binding currently only fully supports telnet from a TCP/IP connection.  The serial bridge is untested.

Current fully supported devices:
* NP-02B
* NP-05B

Other Synaccess PDU devices may function but with limited reporting capabilities.

Configuration of the PDU must first be performed from the built in web server.  The System page must be configured to allow telnet access and Authorization should be set to be required.  The authentification information is set on the Administration page.  A fixed IP address is required on the Network page.

## Supported Things

The binding only supports one thing type:
* **pdu** - Power Distribution Unit

## Discovery

Discovery of the bridges is not possible.  The PDU is automatically discovered with the bridge.  Channels for the ports are created automatically based on the model of PDU.

## Binding Configuration

This binding does not require any special configuration.

## Thing Configuration

### IP Bridge

The bridge configuration requires the IP address of the bridge as well as the telnet username and password to log in to the bridge.  A port may optionally be specified but must be left at the default 23; it cannot be modified in the PDU.

Because it cannot be discovered, it is necessary that the PDU be configured with a static IP address.

The optional advanced parameter `heartbeat` can be used to set the interval between connection keepalive heartbeat messages, in minutes. It defaults to 5.  
Note that the handler will wait up to 30 seconds for a heartbeat response before attempting to reconnect.
The optional advanced parameter `reconnect` can be used to set the connection retry interval, in minutes.
It also defaults to 5.

Thing configuration file example:

```
Bridge synaccess:ipbridge:system1 [ ipAddress="192.168.1.100", user="admin", password="admin" ] {
    Thing pdu amplifier
}
```

### Serial Bridge

Untested

## Channels

Channels can be manually specified but are created automatically upon creation of the pdu thing. Items linked may take up to the heartbeat interval to update the state. The following channels are supported:

| channel       | type   | description                                                      |
|---------------|--------|------------------------------------------------------------------|
| portstatus1   | Switch | This is the power control for the first port                     |
| portstatus2   | Switch | This is the power control for the second port                    |
| portstatus[x] | Switch | This is the power control for the [x] port                       |
| allports      | String | This is a write only command channel for control of all ports    |

## Full Example

demo.things

demo.items

demo.sitemap
