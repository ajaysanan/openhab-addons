/**
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.lutron.internal.hw;

import static org.openhab.binding.lutron.internal.LutronBindingConstants.BINDING_ID;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Defines common constants, which are used across the whole binding.
 *
 * @author Andrew Shilliday - Initial contribution
 * @author Ajay Sanan - Added IP bridge, keypad, and keypad LED support
 */
@NonNullByDefault
public class HwConstants {
    public static final ThingTypeUID THING_TYPE_HWSERIALBRIDGE = new ThingTypeUID(BINDING_ID, "hwserialbridge");
    public static final ThingTypeUID THING_TYPE_HWIPBRIDGE = new ThingTypeUID(BINDING_ID, "hwipbridge");
    public static final ThingTypeUID THING_TYPE_HWDIMMER = new ThingTypeUID(BINDING_ID, "hwdimmer");
    public static final ThingTypeUID THING_TYPE_HWKEYPAD = new ThingTypeUID(BINDING_ID, "hwkeypad");
    public static final ThingTypeUID THING_TYPE_HWSWITCH = new ThingTypeUID(BINDING_ID, "hwswitch");

    // Bridge properties
    public static final String HW_PROPERTY_BOOTREV = "bootRevision";
    public static final String HW_PROPERTY_PROCESSORADDRESS = "processorAddress";

    // Dimmer commands
    public static final String HW_COMMAND_ZONECHANGE = "FADEDIM";
    // Not much of a use case for Start/Stop Raise/Lower of Dimmer from inside OH
    // public static final String HW_COMMAND_ZONERAISE = "RAISEDIM, ";
    // public static final String HW_COMMAND_ZONELOWER = "LOWERDIM";
    // public static final String HW_COMMAND_ZONESTOP = "STOPDIM";
    public static final String HW_COMMAND_ZONEGET = "RDL";

    // Keypad commands/responses
    public static final String HW_KEYPADBUTTONPRESS = "KBP";
    public static final String HW_KEYPADBUTTONRELEASE = "KBR";
    public static final String HW_KEYPADBUTTONDOUBLETAP = "KBDT";
    public static final String HW_KEYPADBUTTONHOLD = "KBH";
    // Keypad commands
    public static final String HW_COMMAND_KEYPADENABLE = "KE";
    public static final String HW_COMMAND_KEYPADDISABLE = "KD";
    public static final String HW_COMMAND_KEYPADGETSTATE = "RKES";
    public static final String HW_COMMAND_KEYPADLASTBUTTON = "RKLBP";
    // LED commands
    public static final String HW_COMMAND_LEDSET = "SETLED";
    public static final String HW_COMMAND_LEDALLSET = "SETLEDS";
    public static final String HW_COMMAND_LEDGET = "RKLS";

    // Maestro commands
    public static final String HW_COMMAND_DIMMERPRESS = "DBP";
    public static final String HW_COMMAND_DIMMERRELEASE = "DBR";
    public static final String HW_COMMAND_DIMMERDOUBLETAP = "DBDT";
    public static final String HW_COMMAND_DIMMERHOLD = "DBH";

    // System commands
    public static final String HW_COMMAND_SETTIME = "ST";
    public static final String HW_COMMAND_SETDATE = "SD";
    public static final String HW_COMMAND_SETDST = "SDS";
    public static final String HW_COMMAND_GETTIME = "RST2";
    public static final String HW_COMMAND_GETDATE = "RSD";
    public static final String HW_COMMAND_REBOOT = "REBOOT";
    public static final String HW_COMMAND_CHECKBATTERY = "CHKBATT";
    public static final String HW_COMMAND_GETOSREV = "OSREV";
    public static final String HW_COMMAND_GETBOOTREV = "BOOTREV";
    public static final String HW_COMMAND_GETPROCADDR = "PROCADDR";

    // Vacation mode commands
    public static final String HW_COMMAND_VACATIONRECORD = "VMR";
    public static final String HW_COMMAND_VACATIONPLAYBACK = "VMP";
    public static final String HW_COMMAND_VACATIONDISABLE = "VMD";
    public static final String HW_COMMAND_VACATIONGETSTATE = "VMS";
    public static final String HW_COMMAND_VACATIONCHECK = "VRS";

    // Monitoring
    public static final String HW_COMMAND_MONITORKEYPADON = "KBMON";
    public static final String HW_COMMAND_MONITORKEYPADOFF = "KBMOFF";
    public static final String HW_COMMAND_MONITORDIMMERON = "DLMON";
    public static final String HW_COMMAND_MONITORDIMMEROFF = "DLMOFF";
    public static final String HW_COMMAND_MONITORPROMPTON = "PROMPTON";
    public static final String HW_COMMAND_MONITORPROMPTOFF = "PROMPTOFF";
    public static final String HW_COMMAND_MONITORDRIVERON = "DRMON";
    public static final String HW_COMMAND_MONITORDRIVEROFF = "DRMOFF";
    public static final String HW_COMMAND_MONITORGRAFIKEYEON = "GSMON";
    public static final String HW_COMMAND_MONITORGRAFIKEYEOFF = "GSMOFF";
    public static final String HW_COMMAND_MONITORLEDON = "KLMON";
    public static final String HW_COMMAND_MONITORLEDOFF = "KLMOFF";

    // Responses
    public static final String HW_RESPONSE_DIMMERLEVEL = "DL";
    public static final String HW_RESPONSE_LEDSTATE = "KLS";
    public static final String HW_RESPONSE_KEYPADSTATE = "KES";
    public static final String HW_RESPONSE_LASTKEY = "KLBP";
}