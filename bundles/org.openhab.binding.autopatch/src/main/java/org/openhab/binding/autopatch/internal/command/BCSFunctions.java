package org.openhab.binding.autopatch.internal.command;

import static org.openhab.binding.autopatch.internal.command.BCSConstants.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;

@NonNullByDefault
public class BCSFunctions {

    public static boolean isValidZoneList(String zonelist, int maxZones) {
        // Check for too many zones or invalid zone numbers
        // List<Integer> zoneNumbers = new ArrayList<Integer>();
        int zones = 0;
        for (String field : zonelist.split(" ")) {
            zones++;
            if (!field.matches("^[+-]?\\d+$")) {
                return false;
            }
            try {
                Integer zonenum = Integer.parseInt(field);
                if ((zonenum > maxZones) || (zonenum < 1)) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }

        }

        if (zones > maxZones) {
            return false;
        }

        return true;
    }

    private static boolean isValidEQList(String eqlist) {
        String[] fields = eqlist.split(" ");
        if (fields.length != 10) {
            return false;
        }
        for (String field : fields) {
            if (!field.matches("^[+-]?\\d+$")) {
                return false;
            }
            try {
                Float.parseFloat(field);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static List<@Nullable Integer> getList(@Nullable String list) {
        if (list == null) {
            return new ArrayList<@Nullable Integer>();
        }
        String[] getlist = list.replaceAll("[^0-9-:M ]", "").trim().split("\\s");
        List<@Nullable Integer> intlist = new ArrayList<@Nullable Integer>();
        try {
            for (int i = 0; i < getlist.length; i++) {
                if (getlist[i].contains(":")) {
                    intlist.addAll(getListRange(getlist[i]));
                } else {
                    // add null for muted response zones
                    intlist.add(getlist[i].equals("M") ? null : Integer.valueOf(getlist[i]));
                }
            }
        } catch (NumberFormatException e) {
            // just return what we have so far
            return intlist;
        }
        return intlist;
    }

    public static List<Integer> getListRange(String list) {
        String[] getlist = list.replaceAll("[^0-9-:]", "").trim().split(":");
        ArrayList<Integer> intlist = new ArrayList<Integer>();
        try {
            for (int i = Integer.parseInt(getlist[0]); i <= Integer.parseInt(getlist[1]); i++) {
                intlist.add(i);
            }
        } catch (NumberFormatException e) {
            // just return what we have so far
            return intlist;
        }
        return intlist;
    }

    public static String BCSResponseToString(CommandType commandName, @Nullable Integer response) {
        switch (commandName) {
            case GAIN:
            case TREBLE:
            case BASS:
            case EQUALIZER:
                return (response == null ? "" : String.format("%.1f", Float.valueOf(response) / 10));
            case INPUTSWITCH:
            case OUTPUTSWITCH:
            case MUTEZONE:
            case BALANCE:
                return (response == null ? "" : response.toString());
            case VOLUME:
                return (response == null ? "" : Integer.toString((int) Math.round(((response + 700) / 10) * 1.25)));
            default:
                break;
        }
        return "";

    }

    public static String OHStringToBCS(CommandType commandName, String value) {
        switch (commandName) {
            case VOLUME:
                return Integer.toString((int) (Float.parseFloat(value) / .125) - 700);
            case GAIN:
            case TREBLE:
            case BASS:
            case EQUALIZER:
                StringBuilder sb = new StringBuilder();
                for (String s : value.replaceAll("[^0-9 ]", "").trim().split("\\s")) {
                    sb.append((int) (Float.parseFloat(s) * 10)).append(" ");
                }
                return sb.toString().trim();
            case INPUTSWITCH:
                return Arrays.toString(getList(value.trim().replace("Z", "")).toArray()).replaceAll("[\\[\\],]", "");
            case BALANCE:
            case OUTPUTSWITCH:
                return value.trim().replace("Z", "");
            default:
                break;
        }
        return "";
    }

    // Build command for Verifying the status of one type from one or more zones
    public static String buildStatusCommand(CommandType commandType, int level, String zonelist) {
        StringBuilder command = new StringBuilder(COMMAND_STATUS);
        return command.append(buildCommand(commandType, level, zonelist).toString()).toString();
    }

    // Build command for Disconnecting from one or more zones
    public static String buildDisconnectCommand(CommandType commandType, int level, String zonelist) {
        StringBuilder command = new StringBuilder(COMMAND_DISCONNECT);
        return command.append(buildCommand(commandType, level, zonelist).toString()).toString();
    }

    public static StringBuilder buildCommand(CommandType commandType, int level, String zonelist) {
        StringBuilder command = new StringBuilder(LEVEL);
        command.append(level).append(BCSCommand.getZoneType(commandType) == ZoneType.INPUT ? ZONE_INPUT : ZONE_OUTPUT)
                .append(zonelist);
        command.append(BCSCommand.getResponseCode(commandType));
        if (BCSCommand.requiresSubzones(commandType)) {
            command.append("1:10");
        }
        command.append(EXECUTE);
        return command;
    }

    // Build command for Setting one or more values
    public static String buildChangeCommand(CommandType commandType, int level, String zonelist, String value) {
        StringBuilder command = new StringBuilder(COMMAND_CHANGE + LEVEL);
        try {
            command.append(level)
                    .append((BCSCommand.getZoneType(commandType) == ZoneType.INPUT ? ZONE_INPUT : ZONE_OUTPUT))
                    .append(zonelist).append(BCSCommand.getCommandCode(commandType));
            switch (commandType) {
                case VOLUME:
                case BALANCE:
                case GAIN:
                case TREBLE:
                case BASS:
                    command.append(BCSCommand.requiresDSPCommand(commandType) ? DSPCOMMAND : "");
                    command.append(OHStringToBCS(commandType, value));
                    break;
                case OUTPUTSWITCH:
                    // if value = 0, execute disconnect
                    if ((Integer.parseInt(value) == 0) || value.isEmpty()) {
                        return buildDisconnectCommand(commandType, level, zonelist);
                    } else {
                        command.append(OHStringToBCS(commandType, value));
                    }
                    break;
                case INPUTSWITCH:
                    // if no value execute disconnect
                    if (value.isEmpty()) {
                        return buildDisconnectCommand(commandType, level, zonelist);
                    } else {
                        command.append(OHStringToBCS(commandType, value));
                    }
                    break;
                case MUTEZONE:
                    // special case because "Unmute" can't be detected from the channel
                    if (value.equals("OFF")) {
                        command = new StringBuilder(command.toString().replace("VM", "VU"));
                    }
                    break;
                case EQUALIZER:
                    // Flush if invalid list of freqs
                    if (isValidEQList(value.trim())) {
                        command.append("1:10");
                        command.append(BCSCommand.requiresDSPCommand(commandType) ? DSPCOMMAND : "");
                        command.append(OHStringToBCS(commandType, value));
                    } else {
                        return "";
                    }
                    break;
                default:
                    return "";
            }
            return command.append(EXECUTE).toString();
        } catch (NumberFormatException e) {
            return "";
        }

    }

}
