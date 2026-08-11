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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSFunctions;
import org.openhab.binding.autopatch.internal.config.AutopatchGroupConfig;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseThingHandler-->ZoneHandler to support Autopatch devices with Output Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public class AutopatchOutputZoneGroupHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(AutopatchOutputZoneGroupHandler.class);

    private AutopatchGroupConfig configuration = new AutopatchGroupConfig();

    // protected ZoneType zoneType = ZoneType.UNINITIALIZED;
    private List<@Nullable Integer> zoneNumbers = new ArrayList<@Nullable Integer>();
    private Integer zoneLevel = 0;
    private String zoneName = "";

    public AutopatchOutputZoneGroupHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        if (getThing().getBridgeUID() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NOT_YET_READY);
        configuration = getConfigAs(AutopatchGroupConfig.class);
        // zoneType = ZoneType.OUTPUT;
        zoneNumbers = BCSFunctions.getList(configuration.getNumbers());
        zoneLevel = configuration.getLevel();
        if (zoneNumbers.size() > 0) {
            logger.debug("Initializing Autopatch output group zone(s) {}:{}", zoneName, zoneNumbers.toString());
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
            // // Delay a bit to allow the slow serial bridge to initially connect
            // scheduler.schedule(() -> refreshAllChannels(), 3000, TimeUnit.MILLISECONDS);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Invalid Zone Numbers");
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        super.channelLinked(channelUID);
        refreshFromMember(channelUID.getId());
    }

    private void refreshFromMember(String channelId) {
        Integer zoneNumber = zoneNumbers.stream().filter(Objects::nonNull).findFirst().orElse(null);
        if (zoneNumber == null) {
            logger.warn("No zones configured for group {}; cannot refresh channel {}", getThing().getUID(), channelId);
            return;
        }
        if (isLinked(channelId)) {
            CommandType command = BCSCommand.getCommandType(channelId);
            if (command != null) {
                sendMessage(BCSFunctions.buildStatusCommand(command, zoneLevel, zoneNumber.toString()));
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            refreshFromMember(channelUID.getId());
            return;
        }
        AutopatchBaseBridgeHandler bridgehandler = getBridgeHandler();
        String zones = zoneNumbers.toString().replaceAll("[\\[,\\]]", "");
        if ((bridgehandler != null) && BCSFunctions.isValidZoneList(zones, bridgehandler.numOutputZones)) {
            logger.debug("Handling command {} for channel {}", command, channelUID);

            Channel channel = getThing().getChannel(channelUID.getId());
            String channelId = channelUID.getId();
            CommandType commandtype = BCSCommand.getCommandType(channelId);

            if (channel == null || commandtype == null) {
                logger.warn("Received invalid command or invalid channel {} for device {}", channelUID,
                        getThing().getUID());
                return;
            }
            if (isLinked(channelUID)) {
                sendMessage(BCSFunctions.buildChangeCommand(commandtype, zoneLevel, zones, command.toString()));
                for (Integer zone : zoneNumbers) {
                    if (zone != null) {
                        sendMessage(BCSFunctions.buildStatusCommand(commandtype, zoneLevel, zone.toString()));
                    }
                }
            }

        }
    }

    protected void sendMessage(String query) {
        AutopatchBaseBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR, "No bridge associated");
            return;
        }
        bridgeHandler.sendCommand(query);
    }

    protected @Nullable AutopatchBaseBridgeHandler getBridgeHandler() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            return null;
        } else if (!(bridge.getHandler() instanceof AutopatchBaseBridgeHandler)) {
            return null;
        } else {
            return (AutopatchBaseBridgeHandler) bridge.getHandler();
        }
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus().equals(ThingStatus.ONLINE)
                && getThing().getStatusInfo().getStatusDetail().equals(ThingStatusDetail.BRIDGE_OFFLINE)) {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
        } else if (bridgeStatusInfo.getStatus().equals(ThingStatus.OFFLINE)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public List<@Nullable Integer> getZonenumbers() {
        return zoneNumbers;
    }

    public void updateChannelState(String channelId, String value) {
        logger.trace("  Updating {} to {}", channelId, value);
        updateState(channelId, new StringType(value));
    }

}