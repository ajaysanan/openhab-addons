/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.lutron.internal.hw;

import static org.openhab.binding.lutron.internal.LutronBindingConstants.CHANNEL_SWITCH;
import static org.openhab.binding.lutron.internal.hw.HwConstants.*;

import java.util.concurrent.TimeUnit;

import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends {@link HwDeviceHandler} to support HomeWorks dimmer addresses that are internally
 * programmed as on/off switches, exposing them as a Switch channel rather than a dimmable one.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class HwSwitchHandler extends HwDeviceHandler {
    private Integer fadeTime = 1;

    private final Logger logger = LoggerFactory.getLogger(HwSwitchHandler.class);

    public HwSwitchHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        HwSwitchConfig config = getThing().getConfiguration().as(HwSwitchConfig.class);

        String address = config.getAddress();
        setAddress(address);
        if (address == null || address.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Address not set");
            return;
        }

        logger.debug("Initializing Switch Handler for address {}", address);

        fadeTime = config.getFadeTime();

        if (getThing().getBridgeUID() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN);

        scheduler.schedule(this::initDeviceState, 3, TimeUnit.SECONDS);
    }

    public void initDeviceState() {
        queryLevel();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (CHANNEL_SWITCH.equals(channelUID.getId()) && command instanceof OnOffType) {
            outputLevel(command == OnOffType.ON ? 100 : 0);
        }
    }

    private void queryLevel() {
        sendToBridge(String.format("%s, %s", HW_COMMAND_ZONEGET, getAddress()));
    }

    private void outputLevel(int level) {
        sendToBridge(String.format("%s, %s, %s, 0, %s", HW_COMMAND_ZONECHANGE, level, fadeTime, getAddress()));
    }

    public void handleUpdate(Integer level) {
        if (getThing().getStatus() == ThingStatus.UNKNOWN) {
            updateStatus(ThingStatus.ONLINE);
        }
        updateState(CHANNEL_SWITCH, OnOffType.from(level == 100));
    }
}