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

import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.binding.autopatch.internal.command.BCSDecode;
import org.openhab.binding.autopatch.internal.command.BCSFunctions;
import org.openhab.binding.autopatch.internal.config.AutopatchZoneConfig;
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
 * This class extends the BaseThingHandler to support Autopatch devices with Input/Output Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public abstract class AutopatchBaseZoneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(AutopatchBaseZoneHandler.class);

    private AutopatchZoneConfig configuration = new AutopatchZoneConfig();

    protected ZoneType zoneType = ZoneType.UNINITIALIZED;
    protected Integer zoneNumber = 0;
    protected Integer zoneLevel = 0;
    protected String zoneName = "";

    public AutopatchBaseZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        if (getThing().getBridgeUID() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NOT_YET_READY);
        configuration = getConfigAs(AutopatchZoneConfig.class);
        zoneNumber = configuration.getNumber();
        zoneLevel = configuration.getLevel();

        if (!repeatedZone(configuration)) {
            logger.debug("Initializing Autopatch {} zone {}:{}", zoneType.toString(), zoneNumber, zoneName);

            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
            // Delay a bit to allow the slow serial bridge to initially connect
            scheduler.schedule(() -> refreshAllChannels(), 3000, TimeUnit.MILLISECONDS);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Duplicate Zone Number");
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("Linking channel {}", channelUID.getId());
        updateChannel(channelUID.getId().toString());
    }

    protected void sendMessage(String query) {
        AutopatchBaseBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR, "No bridge associated");
            return;
        }
        bridgeHandler.sendCommand(query);
    }

    protected boolean repeatedZone(AutopatchZoneConfig config) {
        // confirm not a repeated zone and level number usage
        Bridge bridge = getBridge();
        if ((bridge != null) && bridge.getThings().stream().filter(Thing::isEnabled).map(Thing::getHandler)
                .filter(AutopatchBaseZoneHandler.class::isInstance).map(AutopatchBaseZoneHandler.class::cast)
                .filter(handler -> !this.equals(handler) && handler.zoneType == this.zoneType)
                .map(handler -> handler.getConfigAs(AutopatchZoneConfig.class)).map(AutopatchZoneConfig.class::cast)
                .anyMatch(conf -> config.sameZoneParameters(conf))) {
            return true;
        }
        return false;
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
            logger.debug("Bridge Status changed to Online so refreshing Input Zone channels");
            refreshAllChannels();
        } else if (bridgeStatusInfo.getStatus().equals(ThingStatus.OFFLINE)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    public void zoneCommand(ChannelUID channelUID, Command command) {
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
            if (command instanceof RefreshType) {
                updateChannel(channelId);
            } else if (commandtype.equals(CommandType.RESET)) {
                sendMessage(BCSFunctions.buildChangeCommand(CommandType.VOLUME, zoneLevel, zoneNumber.toString(), "0"));
                sendMessage(
                        BCSFunctions.buildChangeCommand(CommandType.MUTEZONE, zoneLevel, zoneNumber.toString(), "ON"));
                sendMessage(
                        BCSFunctions.buildChangeCommand(CommandType.BALANCE, zoneLevel, zoneNumber.toString(), "0"));
                sendMessage(BCSFunctions.buildChangeCommand(CommandType.BASS, zoneLevel, zoneNumber.toString(), "0"));
                sendMessage(BCSFunctions.buildChangeCommand(CommandType.TREBLE, zoneLevel, zoneNumber.toString(), "0"));
                sendMessage(BCSFunctions.buildChangeCommand(CommandType.EQUALIZER, zoneLevel, zoneNumber.toString(),
                        "0 0 0 0 0 0 0 0 0 0"));
                scheduler.schedule(this::refreshAllChannels, 10, TimeUnit.SECONDS);
            } else {
                sendMessage(BCSFunctions.buildChangeCommand(commandtype, zoneLevel, zoneNumber.toString(),
                        command.toString()));
                updateChannel(channelId);
            }
        }
    }

    public void updateChannel(String channelId) {
        if (isLinked(channelId)) {
            CommandType command = BCSCommand.getCommandType(channelId);
            if (command != null) {
                sendMessage(BCSFunctions.buildStatusCommand(command, zoneLevel, zoneNumber.toString()));
            }
        }
    }

    protected abstract void handleStateChange(BCSDecode bcs, int index);

    protected abstract void refreshAllChannels();

}