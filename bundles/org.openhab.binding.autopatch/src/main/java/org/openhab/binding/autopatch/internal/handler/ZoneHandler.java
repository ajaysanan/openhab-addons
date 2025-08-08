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

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.AutopatchBindingConstants.IOType;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseThingHandler to support Autopatch devices with Input/Output Zones.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
@NonNullByDefault
public abstract class ZoneHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(ZoneHandler.class);

    protected IOType zoneType = IOType.UNKNOWN;
    protected int zoneNumber;
    protected int zoneLevel;
    protected String zoneName = "";

    public ZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        if (getThing().getBridgeUID() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NOT_YET_READY);
        ZoneConfig configuration = getConfigAs(ZoneConfig.class);
        zoneNumber = configuration.getNumber();
        zoneLevel = configuration.getLevel();
        zoneName = configuration.getLabel();

        if (!repeatedZone()) {
            logger.debug("Initializing Autopatch input zone {}", zoneNumber);

            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE);
            // scheduler.execute(this::refreshAllChannels);
            // Delay a bit to allow the slow serial bridge to initially connect
            scheduler.schedule(() -> refreshAllChannels(), 3000, TimeUnit.MILLISECONDS);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Zone already exists");
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        logger.debug("Linking channel {}", channelUID.getId());
        updateChannel(channelUID.getId().toString());
    }

    protected void sendQuery(String query) {
        AutopatchBaseBridgeHandler bridgeHandler = getBridgeHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR, "No bridge associated");
            return;
        }

        bridgeHandler.sendCommand(query);
    }

    protected boolean repeatedZone() {
        // confirm not a repeated zone number usage unless is exact same "thing"
        // Is this necessary with representation property??
        Bridge bridge = getBridge();
        if (bridge != null) {
            for (Thing testthing : bridge.getThings()) {
                if (testthing.getThingTypeUID()
                        .equals(zoneType == IOType.INPUT ? THING_TYPE_INPUTZONE : THING_TYPE_OUTPUTZONE)
                        && (testthing.getUID() != thing.getUID())) {
                    BigDecimal x = new BigDecimal(
                            testthing.getConfiguration().getProperties().getOrDefault("number", 0).toString());
                    if (x.intValueExact() == zoneNumber) {
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "Duplicate Zone Number");
                        return true;
                    }
                }
            }
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

    protected void handleStateChange(BCSCommand bcs) {

        String channel = BCSCommand.getChannel(bcs.datatype);
        Class<?> x = BCSCommand.getState(bcs.datatype);

        if (isLinked(channel)) {
            if (x.isInstance(DecimalType.class)) {
                updateState(channel, new DecimalType(bcs.value / 10));
            } else if (x.isInstance(StringType.class)) {
                updateState(channel, new StringType(bcs.zonelist));
            } else if (x.isInstance(OnOffType.class)) {
                updateState(channel, bcs.muted ? OnOffType.ON : OnOffType.OFF);
            }
        }

    }

    protected abstract void refreshAllChannels();

    protected abstract void updateChannel(String channel);

}