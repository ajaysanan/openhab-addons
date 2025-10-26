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

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.UPDATE_INPUT_CHANNELS;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.binding.autopatch.internal.command.BCSDecode;
import org.openhab.binding.autopatch.internal.command.BCSFunctions;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseThingHandler-->ZoneHandler to support Autopatch devices with Input Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public class AutopatchInputZoneHandler extends AutopatchBaseZoneHandler {
    private final Logger logger = LoggerFactory.getLogger(AutopatchInputZoneHandler.class);

    public AutopatchInputZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        zoneType = ZoneType.INPUT;
        super.initialize();
    }

    @Override
    public void refreshAllChannels() {
        logger.debug("Trying to refresh any linked Input Zone {} channels", zoneNumber);
        Bridge bridge = getBridge();
        if (bridge != null) {
            if (bridge.getStatus().equals(ThingStatus.ONLINE)) {
                for (String channelId : UPDATE_INPUT_CHANNELS) {
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
                && BCSFunctions.isValidZoneList(Integer.toString(zoneNumber), bridgehandler.numInputZones)) {
            zoneCommand(channelUID, command);
        }
    }

    @Override
    public void handleStateChange(BCSDecode bcs, int index) {
        // handles response messages from verify type commands
        String channelId = BCSCommand.getChannelId(bcs.commandName);
        Class<?> state = BCSCommand.getState(bcs.commandName);

        if (isLinked(channelId)) {
            // DecimalType is always Input Gain
            if (DecimalType.class.equals(state)) {
                updateState(channelId, new DecimalType(bcs.results.get(index)));
            } else if (StringType.class.equals(state)) {
                // StringType is always Inputzone Output connect list
                updateState(channelId, new StringType(bcs.getResultList()));
                // Also update the state of all output zones in list
                AutopatchBaseBridgeHandler bridgeHandler = this.getBridgeHandler();
                if (bridgeHandler != null) {
                    for (String zone : bcs.results) {
                        bridgeHandler.getThing().getThings().stream().filter(Thing::isEnabled).map(Thing::getHandler)
                                .filter(AutopatchOutputZoneHandler.class::isInstance)
                                .map(AutopatchOutputZoneHandler.class::cast)
                                .filter(handler -> handler.zoneType == ZoneType.OUTPUT
                                        && handler.zoneNumber == Integer.parseInt(zone))
                                .forEach(handler -> handler.changeZone(this.zoneNumber.toString()));
                    }
                }
            }
        }
    }

}