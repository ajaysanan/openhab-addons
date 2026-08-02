/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
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
package org.openhab.binding.autopatch.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link AutopatchBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class AutopatchBindingConstants {

    public enum IOType {
        INPUT,
        OUTPUT,
        COMMAND,
        UNKNOWN;
    }

    private static final String BINDING_ID = "autopatch";

    // Bridge Type UIDs
    public static final ThingTypeUID THING_TYPE_IPBRIDGE = new ThingTypeUID(BINDING_ID, "ipbridge");
    public static final ThingTypeUID THING_TYPE_SERIALBRIDGE = new ThingTypeUID(BINDING_ID, "serialbridge");

    // List of all Thing Type UIDs
    // public static final ThingTypeUID THING_TYPE_AUDIOMATRIX = new ThingTypeUID(BINDING_ID, "audiomatrix");
    public static final ThingTypeUID THING_TYPE_INPUTZONE = new ThingTypeUID(BINDING_ID, "inputzone");
    public static final ThingTypeUID THING_TYPE_OUTPUTZONE = new ThingTypeUID(BINDING_ID, "outputzone");
    public static final ThingTypeUID THING_TYPE_OUTPUTZONEGROUP = new ThingTypeUID(BINDING_ID, "outputzonegroup");

    // public static final Set<String> UPDATE_INPUT_CHANNELS = new HashSet<>();
    // public static final Set<String> UPDATE_OUTPUT_CHANNELS = new HashSet<>();

    // List of all channel groups
    public static final String CHANNEL_GROUP_NONE = "";
    public static final String CHANNEL_GROUP_INPUT_SETTINGS = "input-settings";
    public static final String CHANNEL_GROUP_OUTPUT_SETTINGS = "output-settings";
    public static final String CHANNEL_GROUP_OUTPUTGROUP_SETTINGS = "output-group-settings";
    public static final String CHANNEL_GROUP_DSP_SETTINGS = "dsp-settings";
    public static final String CHANNEL_GROUP_OUTPUT_COMMANDS = "output-commands";

    // List of all channel types
    public static final String CHANNEL_TYPE_NUMBER = "rwtype-connectedinput";
    public static final String CHANNEL_TYPE_STRING = "rwtype-gain";
    public static final String CHANNEL_TYPE_TONE = "rwtype-tone";
    public static final String CHANNEL_TYPE_BALANCE = "rwtype-balance";
    public static final String CHANNEL_TYPE_EQUALIZER = "rwtype-equalizer";
    public static final String CHANNEL_TYPE_ZONECOMMAND = "wotype-zonecommand";
    public static final String CHANNEL_TYPE_DEVICECOMMAND = "wotype-devicecommand";
    public static final String CHANNEL_TYPE_GLOBALPRESET = "wotype-globalpreset";
    public static final String CHANNEL_TYPE_OUTPUTZONE_NUMERIC_MIXED = "rwtype-outputzone-numeric-mixed";
    public static final String CHANNEL_TYPE_OUTPUTZONE_BINARY_MIXED = "rwtype-outputzone-binary-mixed";

    // List of all Channel IDs
    public static final String CHANNEL_GAIN = "settings#gain";
    public static final String CHANNEL_OUTPUTZONELIST = "settings#outputzonelist";
    public static final String CHANNEL_VOLUME = "settings#volume";
    public static final String CHANNEL_MAXVOLUME = "settings#maxvolume";
    public static final String CHANNEL_MUTE = "settings#mute";
    public static final String CHANNEL_CONNECTEDINPUT = "settings#connectedinput";
    public static final String CHANNEL_RESET = "settings#reset";
    public static final String CHANNEL_TREBLE = "dspsettings#treble";
    public static final String CHANNEL_BASS = "dspsettings#bass";
    public static final String CHANNEL_BALANCE = "dspsettings#balance";
    public static final String CHANNEL_EQUALIZER = "dspsettings#equalizer";
    public static final String CHANNEL_COMMAND = "commands#command";
    public static final String CHANNEL_CREATEGLOBALPRESET = "commands#createglobalpreset";
    public static final String CHANNEL_EXECUTEGLOBALPRESET = "commands#executeglobalpreset";

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_IPBRIDGE,
            THING_TYPE_SERIALBRIDGE, THING_TYPE_INPUTZONE, THING_TYPE_OUTPUTZONE, THING_TYPE_OUTPUTZONEGROUP);

    public static final Set<ThingTypeUID> SUPPORTED_BRIDGE_TYPES_UIDS = Set.of(THING_TYPE_IPBRIDGE,
            THING_TYPE_SERIALBRIDGE);

    public static final Set<String> UPDATE_INPUT_CHANNELS = Set.of(CHANNEL_GAIN, CHANNEL_OUTPUTZONELIST);

    public static final Set<String> UPDATE_OUTPUT_CHANNELS = Set.of(CHANNEL_VOLUME, CHANNEL_MUTE,
            CHANNEL_CONNECTEDINPUT, CHANNEL_TREBLE, CHANNEL_BASS, CHANNEL_BALANCE, CHANNEL_EQUALIZER);

    public static final Set<String> UPDATE_OUTPUT_GROUP_CHANNELS = Set.of(CHANNEL_VOLUME, CHANNEL_MUTE,
            CHANNEL_CONNECTEDINPUT);

    public static final Set<String> DSP_CHANNELS = Set.of(CHANNEL_TREBLE, CHANNEL_BASS, CHANNEL_BALANCE,
            CHANNEL_EQUALIZER);

    /*
     * static {
     * UPDATE_INPUT_CHANNELS.add(CHANNEL_GAIN);
     * UPDATE_INPUT_CHANNELS.add(CHANNEL_OUTPUTZONELIST);
     * }
     */

    /*
     * static {
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_VOLUME);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_MUTE);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_CONNECTEDINPUT);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_TREBLE);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_BASS);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_BALANCE);
     * UPDATE_OUTPUT_CHANNELS.add(CHANNEL_EQUALIZER);
     * }
     */
}
