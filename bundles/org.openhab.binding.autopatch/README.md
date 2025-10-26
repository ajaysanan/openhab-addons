# Autopatch Binding

This is a binding for the PrecisDSP series of [AMX](https://www.amx.com/en-US/products/precis-dsp-stereo-rca-dvc) (formerly Autopatch) Matrix Audio and Digital Signal Processing Switchers.  Only Telnet control is supported by the devices.

Current fully supported devices:
* PrecisDSP (8x8)
* PrecisDSP (18x18)

Limitations:

## Supported Things

There are three thing types.

- `matrix`: Switcher
- `inputzone`: Input Zone
- `outputzone`: Output Zone

## Discovery

Discovery is not available/possible for this binding

## Binding Configuration

_If your binding requires or supports general configuration settings, please create a folder ```cfg``` and place the configuration file ```<bindingId>.cfg``` inside it._
_In this section, you should link to this file and provide some information about the options._
_The file could e.g. look like:_

```
# Configuration for the Autopatch Binding
#
# Default secret key for the pairing of the Autopatch Thing.
# It has to be between 10-40 (alphanumeric) characters.
# This may be changed by the user for security reasons.
secret=openHABSecret
```

_Note that it is planned to generate some part of this based on the information that is available within ```src/main/resources/OH-INF/binding``` of your binding._

_If your binding does not offer any generic configurations, you can remove this section completely._

## Thing Configuration

_Describe what is needed to manually configure a thing, either through the UI or via a thing-file._
_This should be mainly about its mandatory and optional configuration parameters._

_Note that it is planned to generate some part of this based on the XML files within ```src/main/resources/OH-INF/thing``` of your binding._

### `sample` Thing Configuration

| Name            | Type    | Description                           | Default | Required | Advanced |
|-----------------|---------|---------------------------------------|---------|----------|----------|
| hostname        | text    | Hostname or IP address of the device  | N/A     | yes      | no       |
| password        | text    | Password to access the device         | N/A     | yes      | no       |
| refreshInterval | integer | Interval the device is polled in sec. | 600     | no       | yes      |

## Channels

_Here you should provide information about available channel types, what their meaning is and how they can be used._

_Note that it is planned to generate some part of this based on the XML files within ```src/main/resources/OH-INF/thing``` of your binding._

| Channel | Type   | Read/Write | Description                 |
|---------|--------|------------|-----------------------------|
| control | Switch | RW         | This is the control channel |

Input Zone: Output Zone List can set output zones but cannot remove them from the list (use Output Zone for that).  A blank list will mute the Input zone.

## Full Example

_Provide a full usage example based on textual configuration files._
_*.things, *.items examples are mandatory as textual configuration is well used by many users._
_*.sitemap examples are optional._

### Thing Configuration

```java
Example thing configuration goes here.
```
### Item Configuration

```java
Example item configuration goes here.
```

### Sitemap Configuration

```perl
Optional Sitemap configuration goes here.
Remove this section, if not needed.
```

## Any custom content here!

To execute a disconnect, change the input zone list to empty or output zone connection to 0
_Feel free to add additional sections for whatever you think should also be mentioned about your binding!_
