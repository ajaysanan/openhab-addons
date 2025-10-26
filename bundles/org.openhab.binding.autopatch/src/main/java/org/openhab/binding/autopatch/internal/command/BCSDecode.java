package org.openhab.binding.autopatch.internal.command;

import static org.openhab.binding.autopatch.internal.command.BCSConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NonNullByDefault
public class BCSDecode {
    public List<@Nullable Integer> zones = new ArrayList<@Nullable Integer>();
    private List<@Nullable Integer> eqfreqbands = new ArrayList<@Nullable Integer>();

    private List<@Nullable Integer> rawresults = new ArrayList<@Nullable Integer>();
    public List<String> results = new ArrayList<String>();

    public int level = 0; // default level 0
    public boolean error = false;

    public ZoneType zonetype = ZoneType.UNINITIALIZED;
    public CommandType commandName = CommandType.ERROR;

    private final Logger logger = LoggerFactory.getLogger(BCSDecode.class);

    // Pattern for return messages
    // Group 1 looks for error codes anywhere in the response to ignore it
    // Group 2 looks for the level, optionally
    // Group 3 looks for the zone, zones or range, required
    // Group 4 is the volume/eq/tone response type (or empty if is a switch)
    // Group 5 is the zones associated ONLY with the eq response type
    // Group 6 is the return value (or values if a range was requested)
    private static final Pattern RESPONSE_REGEX = Pattern
            .compile("([\\?XW])|S(L[0-9]+)?([IO][0-9:\\s]+)(V|P|F[0-3]|E)?([0-9:\\\\s]+)?.*\\(([0-9-M\\s]+)");

    // Verification of only one input or output zone is allowed at a time, for now
    // Switches: I: One to many; O: One to one
    // Disconnects: I or O: Multiple
    // Volume or Gain: Change: Multiple; Verify: Single
    // DSP: Change or Verify: Single
    // global commands (presets and reboot) don't send incoming messages
    // change and disconnect commands also don't send incoming messages

    public BCSDecode(String response) {
        // decipher a received command
        Matcher matcher = RESPONSE_REGEX.matcher(response.toUpperCase().trim());
        boolean responseMatched = matcher.find();

        // Must have a group 3 and 6 match and no group 1 match
        if (!responseMatched || noMatch(matcher)) {
            error = true;
            return;
        }

        try {
            // Get the level, if there is one; for now are obtaining but ignoring this
            if (matcher.group(2) != null && !matcher.group(2).isEmpty()) {
                level = Integer.parseInt(matcher.group(2).replace(LEVEL, ""));
            }

            // Get the type and zone(s) being addressed
            zonetype = (matcher.group(3).contains(ZONE_INPUT)) ? ZoneType.INPUT : ZoneType.OUTPUT;
            zones = BCSFunctions.getList(matcher.group(3));

            // Set the response type
            commandName = BCSCommand.getCommandType(matcher.group(4), zonetype);

            // Set the equalizer zone list (may be an empty arrayList)
            eqfreqbands = BCSFunctions.getList(matcher.group(5));

            // Get the value(s)
            rawresults = BCSFunctions.getList(matcher.group(6));

            // format the final results (and save as properly formatted strings)
            results = rawresults.stream().map(r -> BCSFunctions.BCSResponseToString(commandName, r))
                    .collect(Collectors.toList());

            if (rawresults.size() != results.size() || commandName == CommandType.ERROR) {
                error = true;
                return;
            }

        } catch (RuntimeException e) {
            logger.warn("Runtime exception while processing update: line {}: {}", response, e);
            error = true;
        }
    }

    private boolean noMatch(Matcher matcher) {
        return (matcher.group(1) != null) || matcher.group(3) == null || matcher.group(6) == null
                || matcher.group(3).isEmpty() || matcher.group(6).isEmpty();
    }

    public String getResultList() {
        // add Z to each zone to make searching easier
        StringBuilder finallist = new StringBuilder();
        for (Integer result : rawresults) {
            finallist.append("Z").append(result).append(" ");
        }
        return finallist.toString().trim();
    }

    public String getEQList() {
        StringBuilder finallist = new StringBuilder();
        for (String result : results) {
            finallist.append(result).append(" ");
        }
        return finallist.toString().trim();
    }

}
