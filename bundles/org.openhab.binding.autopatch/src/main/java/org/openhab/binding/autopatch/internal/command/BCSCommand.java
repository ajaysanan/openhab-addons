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

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.*;

import java.util.EnumMap;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.AutopatchBindingConstants.IOType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link BCSCommand} class builds Autopatch BCS commands.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class BCSCommand {

    public enum Datatype {
        VOLUMES("V"),
        MUTE("M"),
        UNMUTE("U"),
        EQUALIZER("E"),
        BALANCE("P"),
        BASS("F1"),
        MIDRANGE("F2"),
        TREBLE("F3"),
        INPUTSWITCH("S"),
        OUTPUTSWITCH("S"),
        UNKNOWN("");

        private @Nullable String datatype;

        private Datatype(final @Nullable String name) {
            datatype = name;
        }

        @Override
        public String toString() {
            return (datatype == null ? "" : datatype);
        }

        public static Datatype getDatatype(String code) {
            for (Datatype e : Datatype.values()) {
                if (e.datatype != null && e.datatype.equals(code)) {
                    return e;
                }
            }
            return Datatype.UNKNOWN;
        }
    }

    private static class CommandList<T> {
        // private String command;
        public String channel;
        private Class<T> state;

        CommandList(String channel, Class<T> state) {
            // this.command = command;
            this.channel = channel;
            this.state = state;
        }

    }

    private final Logger logger = LoggerFactory.getLogger(BCSCommand.class);

    // Pattern for return messages
    // Group 1 looks for error codes anywhere in the response to ignore it
    // Group 2 looks for the level, optionally
    // Group 3 looks for the zone, zones or range, required
    // Group 4 is the volume/eq/tone response type (or empty if is a switch)
    // Group 5 is the zones associated ONLY with the eq response type
    // Group 6 is the return value (or values if a range was requested)
    private static final Pattern RESPONSE_REGEX = Pattern
            .compile("([\\?XW])|S(L[0-9]+)?([IO][0-9:\\s]+)(V|P|F[0-3]|E)?([0-9:\\\\s]+)?.*\\(([0-9-M\\s]+)");

    private final static String LEVEL = "L";
    private final static String INPUTZONE = "I";
    private final static String OUTPUTZONE = "O";

    private final static String ABSOLUTE_VOLUME = "VA";
    /*
     * The following are not used/implemented
     * private final static String RELATIVE_VOLUME = "VR";
     * private final static String VOLUME_UP = "VS+";
     * private final static String VOLUME_DOWN = "VS-";
     */

    private final static String VOLUME = "V";
    private final static String VOLUME_MUTE = "VM";
    private final static String VOLUME_UNMUTE = "VU";

    private final static String GAIN = "G";

    private final static String EXECUTE = "T";
    private final static String EXECUTE_GLOBAL_PRESET = "R";
    private final static String DEFINE_GLOBAL_PRESET = "RR";
    private final static String COMMAND_CHANGE = "C";
    private final static String COMMAND_DISCONNECT = "D";
    private final static String COMMAND_STATUS = "S";

    public IOType iotype = IOType.UNKNOWN;
    public Datatype datatype = Datatype.UNKNOWN;

    // public HashMap<Integer, Integer> eqvalues = new HashMap<>();
    public String eqlist = "";
    public int zone;
    public int value;
    public String zonelist = "";
    public boolean muted = false;
    public int level = 0;

    private static final EnumMap<Datatype, CommandList<?>> channelMap = new EnumMap<>(Datatype.class);
    static {
        channelMap.put(Datatype.VOLUMES, new CommandList<DecimalType>(CHANNEL_VOLUME, DecimalType.class));
        channelMap.put(Datatype.MUTE, new CommandList<OnOffType>(CHANNEL_MUTE, OnOffType.class));
        channelMap.put(Datatype.UNMUTE, new CommandList<OnOffType>(CHANNEL_MUTE, OnOffType.class));
        channelMap.put(Datatype.INPUTSWITCH, new CommandList<StringType>(CHANNEL_OUTPUTZONELIST, StringType.class));
        channelMap.put(Datatype.OUTPUTSWITCH, new CommandList<DecimalType>(CHANNEL_CONNECTEDINPUT, DecimalType.class));
        channelMap.put(Datatype.TREBLE, new CommandList<DecimalType>(CHANNEL_TREBLE, DecimalType.class));
        channelMap.put(Datatype.BASS, new CommandList<DecimalType>(CHANNEL_BASS, DecimalType.class));
        channelMap.put(Datatype.BALANCE, new CommandList<DecimalType>(CHANNEL_BALANCE, DecimalType.class));
        channelMap.put(Datatype.EQUALIZER, new CommandList<StringType>(CHANNEL_EQUALIZER, StringType.class));
    }

    public BCSCommand() {

    }

    // Verification of only one input or output zone is allowed at a time
    public boolean decodeCommand(String command) {
        // decipher a received command
        Matcher matcher = RESPONSE_REGEX.matcher(command.toUpperCase().trim());
        boolean responseMatched = matcher.find();

        // Must have a group 3 and 6 match and no group 1 match
        if (!responseMatched || (matcher.group(1) != null) || (matcher.group(3) == null)
                || (matcher.group(6) == null)) {
            return false;
        }
        if (matcher.group(3).isEmpty() || matcher.group(6).isEmpty()) {
            return false;
        }
        try {
            // Get the level, if there is one; for now are ignoring this
            if (matcher.group(2) != null && !matcher.group(2).isEmpty()) {
                level = Integer.parseInt(matcher.group(2).replace(LEVEL, ""));
            }

            // Get the zone being addressed
            iotype = (matcher.group(3).contains(INPUTZONE)) ? IOType.INPUT : IOType.OUTPUT;
            zone = Integer.parseInt(matcher.group(3).replaceAll("[^0-9]", "").trim());

            // Set the return type
            datatype = Datatype.getDatatype(matcher.group(4) == null ? "S" : matcher.group(4));

            // Get the value(s)
            if (datatype == Datatype.EQUALIZER) {
                // for Equalizer, always read all values
                /*
                 * int[] bands = new int[0];
                 * int[] values = new int[0];
                 * bands = matcher.group(5).contains(":") ? getListRange(matcher.group(5)) : getList(matcher.group(5));
                 *
                 * values = getList(matcher.group(6));
                 * for (int i = 0; i < bands.length; i++) {
                 * eqvalues.put(bands[i], values[i]);
                 * }
                 * return (values.length == bands.length);
                 */
                return true;
            } else if (datatype == Datatype.INPUTSWITCH && iotype == IOType.INPUT) {
                zonelist = matcher.group(6);
                return true;
            } else {
                if (matcher.group(6).contains("M")) {
                    muted = true;
                } else {
                    value = Integer.parseInt(matcher.group(6).trim());
                }
                return true;
            }

        } catch (RuntimeException e) {
            logger.warn("Runtime exception while processing update: line {}: {}", command, e);
            return false;
        }
    }

    private int[] getList(String list) {
        String[] getlist = list.replaceAll("[^0-9]", "").trim().split("\\s");
        int[] intlist = { 0 };
        for (int i = 0; i < getlist.length; i++) {
            intlist[i] = Integer.parseInt(getlist[i]);
        }
        return intlist;
    }

    private int[] getListRange(String list) {
        String[] getlist = list.replaceAll("[^0-9]", "").trim().split(":");
        int[] intlist = { 0 };
        for (int i = 0; i < Integer.parseInt(getlist[1]); i++) {
            intlist[i] = Integer.parseInt(getlist[0] + i);
        }
        return intlist;
    }

    // Build command for Verifying the status of one type from one or more zones
    public static String buildCommand(Datatype datatype, IOType iotype, int level, String zonelist) {
        StringBuilder command = new StringBuilder(COMMAND_STATUS + LEVEL);
        command.append(level).append(iotype.equals(IOType.INPUT) ? INPUTZONE : OUTPUTZONE).append(zonelist);
        if (datatype != Datatype.INPUTSWITCH) {
            command.append(datatype);
        }
        command.append(EXECUTE);
        return command.toString();
    }

    // Build command for Setting one or more values
    public static String buildCommand(Datatype datatype, IOType iotype, int level, String zonelist, String value) {
        StringBuilder command = new StringBuilder(COMMAND_CHANGE + LEVEL);
        command.append(level).append(iotype.equals(IOType.INPUT) ? INPUTZONE : OUTPUTZONE).append(zonelist);
        if (datatype == Datatype.VOLUMES) {
            command.append(ABSOLUTE_VOLUME).append(Float.parseFloat(value) * 10);
        }
        command.append(EXECUTE);
        return command.toString();
    }

    public static String getChannel(Datatype datatype) {
        return channelMap.get(datatype).channel;
    }

    public static State getState(String channel) {
        return new DecimalType(1);
    }

    public static <T> Class<?> getState(Datatype datatype) {
        // Class <?> x = channelMap.get(datatype).state;

        return channelMap.get(datatype).state;

    }

    public static Datatype getDatatype(String channel) {
        for (Entry<Datatype, CommandList<?>> entry : channelMap.entrySet()) {
            if (entry.getValue().channel.equals(channel)) {
                return entry.getKey();
            }
        }
        return Datatype.UNKNOWN;
    }

}