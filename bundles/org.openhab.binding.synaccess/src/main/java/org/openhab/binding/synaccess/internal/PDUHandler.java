/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
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
package org.openhab.binding.synaccess.internal;

import static org.openhab.binding.synaccess.internal.SynaccessBindingConstants.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler responsible for communicating with a synaccess PDU.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class PDUHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(PDUHandler.class);

    public int totalPorts;

    public PDUHandler(Thing thing) {
        super(thing);
    }

    protected void configureChannels(int numberChannels) {
        int i;

        List<Channel> existingChannels = getThing().getChannels();

        if (existingChannels.isEmpty()) {
            Channel channel;
            ChannelTypeUID channelTypeUID;
            ChannelUID channelUID;

            List<Channel> channelList = new ArrayList<>();

            logger.debug("Configuring {} channels for PDU", numberChannels);
            ThingBuilder thingBuilder = editThing();

            // add channels
            for (i = 1; i <= numberChannels; i++) {
                channelTypeUID = new ChannelTypeUID(BINDING_ID, "switchState");
                channelUID = new ChannelUID(getThing().getUID(), CHANNEL_PORTSTATUS + Integer.toString(i));
                channel = ChannelBuilder.create(channelUID, "Switch").withType(channelTypeUID)
                        .withLabel("Power Port " + Integer.toString(i)).build();
                channelList.add(channel);
            }
            totalPorts = i;

            channelTypeUID = new ChannelTypeUID(BINDING_ID, "commandChannel");
            channelUID = new ChannelUID(getThing().getUID(), CHANNEL_ALLPORTS);
            channel = ChannelBuilder.create(channelUID, "String").withType(channelTypeUID).withLabel("All Power Ports")
                    .build();
            channelList.add(channel);

            thingBuilder.withChannels(channelList);
            updateThing(thingBuilder.build());
        }
    }

    @Override
    public void initialize() {
        initDeviceState();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();
        Channel channel = getThing().getChannel(id);

        if (channel == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }

        // For portstatus commands handle OnOffType and RefreshType
        if (channelUID.getId().startsWith(CHANNEL_PORTSTATUS)) {
            if (command instanceof OnOffType) {
                String outCommand = "$A3 ";
                outCommand = outCommand.concat(channelUID.getId().substring(channelUID.getId().length() - 1));
                outCommand = outCommand.concat(command.equals(OnOffType.ON) ? " 1" : " 0");
                sendCommand(outCommand);
            } else if (command instanceof RefreshType) {
                sendCommand("$A5");
            } else {
                logger.warn("Invalid command type {} received for channel {} device {}", command, channelUID,
                        getThing().getUID());
            }
            return;
        }
        // For allports command handle stringtype only; write only channel
        if (channelUID.getId().equals(CHANNEL_ALLPORTS) && (command instanceof StringType)) {
            if (command.toString().equals("ALL_ON")) {
                sendCommand("$A7 1");
            } else if (command.toString().equals("ALL_OFF")) {
                sendCommand("$A7 0");
            }
        }
    }

    public void handleUpdate(String... parameters) {
        if (getThing().getStatus() == ThingStatus.UNKNOWN) {
            updateStatus(ThingStatus.ONLINE);
        }
        if (parameters.length > 1) {
            BigDecimal state = new BigDecimal(parameters[1]);
            updateState("portstatus" + parameters[0],
                    state.compareTo(BigDecimal.ZERO) == 0 ? OnOffType.OFF : OnOffType.ON);
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        logger.debug("Bridge status changed to {} for synaccess device handler {}", bridgeStatusInfo.getStatus());

        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE
                && getThing().getStatusInfo().getStatusDetail() == ThingStatusDetail.BRIDGE_OFFLINE) {
            initDeviceState();

        } else if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    protected void initDeviceState() {
        logger.debug("Initializing device state for PDU");
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "No bridge configured");
        } else if (bridge.getStatus() == ThingStatus.ONLINE) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Awaiting initial response");
            sendCommand("$A5"); // handleUpdate() will set thing status to online when response arrives
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        // Refresh state when new item is linked; don't do this since it could send multiple requests as each channel is
        // linked.
        // if (channelUID.getId().contains("portstatus")) {
        // sendCommand("$A5");
        // }
    }

    protected @Nullable IPBridgeHandler getBridgeHandler() {
        Bridge bridge = getBridge();
        return bridge == null ? null : (IPBridgeHandler) bridge.getHandler();
    }

    protected void sendCommand(String command) {
        IPBridgeHandler bridgeHandler = getBridgeHandler();

        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR, "No bridge associated");
        } else {
            bridgeHandler.sendCommand(command);
        }
    }

}
