
# Synaccess Binding

This is a binding for the netBooter series of [Synaccess](http://www.synaccess.com) Power Distribution Units (PDUs).  HTTP, Telnet and RS232 serial control is supported by the devices, but the binding currently only fully supports telnet from a TCP/IP connection.  There is no implementation of the serial bridge yet.

Current fully supported devices:
* NP-02B
* NP-05B

Device firmware versions earlier than version 95 will not function correctly; authentication responses have changed.

Other Synaccess PDU devices may function but with limited reporting capabilities.

Configuration of the PDU must first be performed from the built in web server.  The System page must be configured to allow telnet access. Authorization should be set to be required, but is not mandatory.  The authentication information can be set on the Administration page (recommended).  A fixed IP address is required on the Network page.

## Supported Things

The binding supports three thing types:
* **ipbridge** - Bridge for IP connections/Telnet
* **serialbridge** - Bridge for Serial connections (not yet implemented)
* **pdu** - Power Distribution Unit

## Discovery

Discovery of the bridges is not possible.  The PDU is automatically discovered with the bridge.  Channels for the ports are created automatically based on the discovered model of PDU.

## Binding Configuration

This binding does not require any special configuration.

## Thing Configuration

### IP Bridge Thing Configuration

| Name        | Type    | Default       | Required | Advanced | Description                                    |
|-------------|---------|---------------|----------|----------|------------------------------------------------|
| ipAddress   | text    | 192.168.1.100 | yes      | no       | IP address of the PDU                          |
| port        | integer | 23            | yes      | yes      | Telnet port                                    |
| user        | text    | N/A           | no       | no       | User name                                      |
| password    | text    | N/A           | no       | no       | Password                                       |
| reconnect   | integer | 5             | no       | yes      | Minutes between connection retries (0=default) |
| heartbeat   | integer | 5             | no       | yes      | Minutes between polls (0=default)              |

Because it cannot be discovered, it is necessary that the PDU be configured with a static IP address.
The port value can be changed, but the PDU only responds at port 23 and this setting cannot be changed in the PDU.

The optional advanced parameter `heartbeat` can be used to set the interval between connection keepalive heartbeat messages, in minutes. It defaults to 5. It is not advised to set this over 10 minutes if the PDU is at it's default setting to disconnect telnet after 10 minutes.
Note that the handler will wait up to 30 seconds for a response before attempting to reconnect.
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

allports accepts the commands ALL_ON and ALL_OFF

## Full Example

demo.things

demo.items

demo.sitemap
