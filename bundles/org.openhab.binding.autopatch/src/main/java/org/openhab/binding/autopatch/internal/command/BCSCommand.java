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
import static org.openhab.binding.autopatch.internal.command.BCSConstants.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;

/**
 * The {@link BCSCommand} class builds Autopatch BCS commands.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public final class BCSCommand {

    /*
     * CommandName
     * ZoneType (I or O)
     * XX CommandCode (C, S, or D) dynamically set
     * XX Level and Level Number is always entered
     * XX ZoneCode and Zone(s) (Input or Output) are always entered
     * ResponseCode (if S)
     * SubcommandCode (if C)
     * RequiresSubzones in Response or Subcommand (bool) (true only for EQ)
     * RequiresDSPCommand (bool) (true for tone and EQ)
     * Channel
     * Data type
     *
     *
     * ?? AllowMultipleZones
     *
     * Volume, Output, "VA", "V", false, false, CHANNEL_VOLUME, PercentType
     * Gain, Input, "VA", "V", false, false, CHANNEL_VOLUME, DecimalType
     * MuteZone, Output, "VM", "", "", false, false, null
     * XX UnmuteZone, Output, "VU", "", "", false, false, null
     * InputSwitch, Input, "O", "", false, false, CHANNEL_OUTPUTZONELIST, StringType
     * OutputSwitch, Output, "I", "", false, false, CHANNEL_CONNECTEDINPUT, DecimalType
     * Bass, Output, "F1", "F1", false, true, CHANNEL_BASS, DecimalType
     * Treble, Output, "F3", "F3", false, true, CHANNEL_TREBLE, DecimalType
     * Balance, Output, "P", "P", false, false, CHANNEL_BALANCE, DecimalType
     * Equalizer, Output, "E", "E", true, true, CHANNEL_EQUALIZER, StringType
     *
     * Special case for Volume Response with muted zone: M
     */

    private static final EnumMap<CommandType, CommandList<?>> BCSMap = new EnumMap<>(CommandType.class);
    static {
        BCSMap.put(CommandType.VOLUME, new CommandList<PercentType>(ZoneType.OUTPUT, VOLUME_ABSOLUTE, STATUS_VOLUME,
                false, false, CHANNEL_VOLUME, PercentType.class));
        BCSMap.put(CommandType.GAIN, new CommandList<DecimalType>(ZoneType.INPUT, VOLUME_ABSOLUTE, STATUS_VOLUME, false,
                false, CHANNEL_GAIN, DecimalType.class));
        BCSMap.put(CommandType.MUTEZONE, new CommandList<OnOffType>(ZoneType.OUTPUT, VOLUME_MUTE, STATUS_VOLUME, false,
                false, CHANNEL_MUTE, OnOffType.class));
        BCSMap.put(CommandType.INPUTSWITCH, new CommandList<StringType>(ZoneType.INPUT, ZONE_OUTPUT, "", false, false,
                CHANNEL_OUTPUTZONELIST, StringType.class));
        BCSMap.put(CommandType.OUTPUTSWITCH, new CommandList<DecimalType>(ZoneType.OUTPUT, ZONE_INPUT, "", false, false,
                CHANNEL_CONNECTEDINPUT, DecimalType.class));
        BCSMap.put(CommandType.BASS, new CommandList<DecimalType>(ZoneType.OUTPUT, BASS, BASS, false, true,
                CHANNEL_BASS, DecimalType.class));
        BCSMap.put(CommandType.TREBLE, new CommandList<DecimalType>(ZoneType.OUTPUT, TREBLE, TREBLE, false, true,
                CHANNEL_TREBLE, DecimalType.class));
        BCSMap.put(CommandType.BALANCE, new CommandList<DecimalType>(ZoneType.OUTPUT, BALANCE, BALANCE, false, false,
                CHANNEL_BALANCE, DecimalType.class));
        BCSMap.put(CommandType.EQUALIZER, new CommandList<StringType>(ZoneType.OUTPUT, EQUALIZER, EQUALIZER, true, true,
                CHANNEL_EQUALIZER, StringType.class));
        BCSMap.put(CommandType.RESET,
                new CommandList<StringType>(ZoneType.OUTPUT, "", "", false, false, CHANNEL_RESET, StringType.class));
    }

    private static class CommandList<T> {
        // private String command;
        private ZoneType zoneType;
        private String subcommandCode;
        private String responseCode;
        private String channelId;
        private Boolean requiresSubzones;
        private Boolean requiresDSPCommand;
        private Class<T> state;

        CommandList(ZoneType zoneType, String subcommandCode, String responseCode, Boolean requiresSubzones,
                Boolean requiresDSPCommand, String channelId, Class<T> state) {
            this.zoneType = zoneType;
            this.subcommandCode = subcommandCode;
            this.responseCode = responseCode;
            this.requiresSubzones = requiresSubzones;
            this.requiresDSPCommand = requiresDSPCommand;
            this.channelId = channelId;
            this.state = state;
        }

    }

    public static CommandType getCommandType(@Nullable String code, ZoneType type) {
        // if code is null, means a switch type with no subcommandCode; assign opposite zone subcommand code
        String revisedCode = (code == null) ? (type == ZoneType.INPUT ? ZONE_OUTPUT : ZONE_INPUT) : code;

        for (Map.Entry<CommandType, CommandList<?>> entry : BCSMap.entrySet()) {
            if ((entry.getValue().responseCode.equals(revisedCode)
                    || entry.getValue().subcommandCode.equals(revisedCode)) && entry.getValue().zoneType == type) {
                return entry.getKey();
            }
        }
        return CommandType.ERROR;
    }

    public static @Nullable CommandType getCommandType(String channel) {
        return BCSMap.entrySet().stream().filter(entry -> entry.getValue().channelId.equals(channel)).map(Entry::getKey)
                .findFirst().orElse(null);
    }

    public static String getChannelId(CommandType command) {
        return BCSMap.get(command).channelId;
    }

    public static <T> Class<?> getState(CommandType command) {
        return BCSMap.get(command).state;
    }

    public static @Nullable <T> Class<?> getState(String channel) {
        return BCSMap.entrySet().stream().filter(entry -> entry.getValue().channelId.equals(channel))
                .map(entry -> entry.getValue().state).findFirst().orElse(null);
    }

    public static String getResponseCode(CommandType command) {
        return BCSMap.get(command).responseCode;
    }

    public static String getCommandCode(CommandType command) {
        return BCSMap.get(command).subcommandCode;
    }

    public static Boolean requiresSubzones(CommandType command) {
        return BCSMap.get(command).requiresSubzones;
    }

    public static Boolean requiresDSPCommand(CommandType command) {
        return BCSMap.get(command).requiresDSPCommand;
    }

    public static ZoneType getZoneType(CommandType command) {
        return BCSMap.get(command).zoneType;
    }

}