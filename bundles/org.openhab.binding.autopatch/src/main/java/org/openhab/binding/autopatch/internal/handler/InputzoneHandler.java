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
import org.openhab.binding.autopatch.internal.AutopatchBindingConstants.IOType;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.binding.autopatch.internal.command.BCSCommand.Datatype;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseThingHandler-->ZoneHandler to support Autopatch devices with Input Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public class InputzoneHandler extends ZoneHandler {
    private final Logger logger = LoggerFactory.getLogger(InputzoneHandler.class);

    public InputzoneHandler(Thing thing) {
        super(thing);
        zoneType = IOType.INPUT;
    }

    @Override
    public void refreshAllChannels() {
        logger.debug("Trying to refresh any linked Input Zone {} channels", zoneNumber);
        Bridge bridge = getBridge();
        if (bridge != null) {
            if (bridge.getStatus().equals(ThingStatus.ONLINE)) {
                for (String channel : UPDATE_INPUT_CHANNELS) {
                    updateChannel(channel);
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge not Online");
            }
        }

    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Handling command {} for channel {}", command, channelUID);

        Channel channel = getThing().getChannel(channelUID.getId());
        if (channel == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }
        if (isLinked(channelUID)) {

            if (command instanceof RefreshType) {
                updateChannel(channelUID.getId().toString());
            } else if (channelUID.getId().toString().equals(CHANNEL_VOLUME)) {
                sendQuery(BCSCommand.buildCommand(Datatype.VOLUMES, IOType.INPUT, zoneLevel,
                        Integer.toString(zoneNumber), command.toString()));
            } else {
                logger.debug("Unexpected command for Autopatch Zone: {} in {} with command {}", zoneNumber, channelUID,
                        command);
            }

        }
    }

    @Override
    public void updateChannel(String channel) {
        if (isLinked(channel)) {
            sendQuery(BCSCommand.buildCommand(BCSCommand.getDatatype(channel), IOType.INPUT, zoneLevel,
                    Integer.toString(zoneNumber)));
        }
    }

}