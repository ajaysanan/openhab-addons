# Quozl Temperature Sensor Binding

This is a binding for a one wire (1-wire) temperature sensor built around the DS1820 sensors.  The device allow up to 4 DS1820 sensors that continually report via a programmed 12C509 PIC.  The design is old but functions very reliably.  Sensors can be up to 200 yards/meters from the control device. The unit is powered from the serial port; no external power is required in most cases (USB serial devices possibly may not satisfy this requirement).

The design is from the year 2000 and does require construction of the control system as well as possible programming of the PIC.  Details and information are here:

http://quozl.netrek.org/ts/

## Supported Things

The binding only supports one thing type, which is the sensor unit itself:
* **tempsensor** - Temperature Sensor Unit

## Discovery

Discovery is not possible.  

## Thing Configuration

Only two configuration parameters are available and both are required.

- **Serial Port (serialPort)**

Selection of the serial port (e.g "COM1" or "/dev/ttyUSB1"). The device runs at a fixed non-configurable speed of 2400, N, 8, 1.

- **Temperature Units (tempUnits)**

The controller is hardwired as Celsius or Fahrenheit and this must be specified in the configuration.  However, the device reports whether it is Celsius or Fahrenheit upon initialization; if detected, this will override the user supplied parameter.

Thing configuration file example:

```
    quozltempsensor:tempsensor:sensor1 [ serialPort="COM1", tempUnits="Celsius" ]
```

## Channels

Channels can be manually specified but are created automatically upon creation of the tempsensor thing. The following channels are supported:

| channel       | type               | description                                          |
|---------------|--------------------|------------------------------------------------------|
| probe1        | Number:Temperature | This is the temperature for the first sensor         |
| probe2        | Number:Temperature | This is the temperature for the second sensor        |
| probe3        | Number:Temperature | This is the temperature for the third sensor         |
| probe4        | Number:Temperature | This is the temperature for the fourth sensor        |

## Items

```
    Number:Temperature  Sensor1_Probe1 "Temperature [%.2f %unit%]" {channel="quozltempsensor:tempsensor:sensor1:probe1"}
```