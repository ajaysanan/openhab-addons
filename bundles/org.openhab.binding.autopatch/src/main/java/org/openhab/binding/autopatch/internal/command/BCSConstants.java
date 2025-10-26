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
package org.openhab.binding.autopatch.internal.command;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link BCSConstants} class defines common constants, which are
 * used for the BCS Command Structure.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class BCSConstants {

    // Commands
    public final static String COMMAND_CHANGE = "C";
    public final static String COMMAND_DISCONNECT = "D";
    public final static String COMMAND_STATUS = "S";

    public final static String LEVEL = "L";

    // Zones
    public final static String ZONE_INPUT = "I";
    public final static String ZONE_OUTPUT = "O";

    // Volume Subcommands (Input and Output zones)
    public final static String VOLUME_ABSOLUTE = "VA";
    public final static String VOLUME_RELATIVE = "VR";
    public final static String VOLUME_UP = "VS+";
    public final static String VOLUME_DOWN = "VS-";

    // DSP Subcommands (Output zones only)
    public static final String BALANCE = "P";
    public static final String BASS = "F1";
    public static final String MID = "F2";
    public static final String TREBLE = "F3";
    public static final String EQUALIZER = "E";
    // public static final String EQUALIZER_COMMAND = "E1:10";
    public static final String DSPCOMMAND = "G";

    // Volume Subcommands (Output zones only)
    public final static String VOLUME_MUTE = "VM";
    public final static String VOLUME_UNMUTE = "VU";

    // public final static String GAIN = "G";

    public final static String EXECUTE = "T";
    public final static String EXECUTE_GLOBAL_PRESET = "R";
    public final static String DEFINE_GLOBAL_PRESET = "RR";

    // Responses
    public final static String STATUS_VOLUME = "V";

    public static final String[] EQBANDS = { "32 Hz", "64 Hz", "125 Hz", "250 Hz", "500Hz", "1 kHz", "2 kHz", "4 kHz",
            "8 kHz", "16 kHz" };

    public static final String ERROR = "X";

    public enum CommandType {
        VOLUME,
        GAIN,
        MUTEZONE,
        UNMUTEZONE,
        INPUTSWITCH,
        OUTPUTSWITCH,
        BASS,
        TREBLE,
        BALANCE,
        EQUALIZER,
        ERROR
    }

    public enum ZoneType {
        INPUT,
        OUTPUT,
        UNINITIALIZED
    }

}
