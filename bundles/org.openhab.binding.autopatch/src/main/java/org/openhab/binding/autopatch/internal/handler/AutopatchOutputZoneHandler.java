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
package org.openhab.binding.autopatch.internal.handler;

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.binding.autopatch.internal.command.BCSDecode;
import org.openhab.binding.autopatch.internal.command.BCSFunctions;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseThingHandler-->ZoneHandler to support Autopatch devices with Output Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public class AutopatchOutputZoneHandler extends AutopatchBaseZoneHandler {
    private final Logger logger = LoggerFactory.getLogger(AutopatchOutputZoneHandler.class);

    private State muteState = UnDefType.UNDEF;
    private State volumeState = UnDefType.UNDEF;
    private State connectedInputState = UnDefType.UNDEF;

    public AutopatchOutputZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        zoneType = ZoneType.OUTPUT;
        super.initialize();
    }

    @Override
    public void refreshAllChannels() {
        logger.debug("Trying to refresh any linked Output Zone {} channels", zoneNumber);
        Bridge bridge = getBridge();
        if (bridge != null) {
            if (bridge.getStatus().equals(ThingStatus.ONLINE)) {
                for (String channelId : UPDATE_OUTPUT_CHANNELS) {
                    updateChannel(channelId);
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge not Online");
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        AutopatchBaseBridgeHandler bridgehandler = getBridgeHandler();
        if ((bridgehandler != null)
                && BCSFunctions.isValidZoneList(Integer.toString(zoneNumber), bridgehandler.numOutputZones)) {
            zoneCommand(channelUID, command);
        }
    }

    @Override
    protected void handleStateChange(BCSDecode bcs, int index) {
        // handles response messages from verify type commands
        String channelId = BCSCommand.getChannelId(bcs.commandName);
        Class<?> state = BCSCommand.getState(bcs.commandName);

        if (PercentType.class.equals(state) || OnOffType.class.equals(state)) {
            // PercentType is always Volume (only one zone verification allowed at a time; null=mute)
            // OnOffType is always Mute (but will never be set as the type)
            // Always handle volume and mute together
            if (isLinked(CHANNEL_MUTE)) {
                this.muteState = (bcs.getResult(index) == "") ? OnOffType.ON : OnOffType.OFF;
                updateState(CHANNEL_MUTE, muteState);
            }
            if (bcs.getResult(index) != "" && isLinked(channelId)) {
                this.volumeState = new PercentType(bcs.getResult(index));
                updateState(channelId, volumeState);
            }
            updateGroupZones(bcs.commandName);
        } else if (DecimalType.class.equals(state) && isLinked(channelId)) {
            // DecimalType is Switch, Balance, Bass, Treble or EQ
            this.connectedInputState = new DecimalType(bcs.getResult(index));
            updateState(channelId, connectedInputState);
            // If Output Switch type, also update the Input zone connect list
            if (bcs.commandName.equals(CommandType.OUTPUTSWITCH) && Integer.parseInt(bcs.getResult(index)) > 0) {
                sendMessage(BCSFunctions.buildStatusCommand(CommandType.INPUTSWITCH, zoneLevel, bcs.getResult(index)));
            }
            updateGroupZones(bcs.commandName);
        } else if (StringType.class.equals(state) && isLinked(channelId)) {
            // StringType is always Equalizer
            updateState(channelId, new StringType(bcs.getEQList()));
        }
    }

    private void updateGroupZones(CommandType command) {
        // States have been updated from the message; notify BaseBridgeHandler to update any Group Zones
        AutopatchBaseBridgeHandler bridgehandler = getBridgeHandler();
        if (bridgehandler != null) {
            bridgehandler.outputzoneStateChange(command, zoneNumber);
        }
    }

    protected void changeZone(String zone) {
        updateState(CHANNEL_CONNECTEDINPUT, new StringType(zone));
    }

    public State getMuteState() {
        return muteState;
    }

    public State getVolumeState() {
        return volumeState;
    }

    public State getConnectedInputState() {
        return connectedInputState;
    }

}