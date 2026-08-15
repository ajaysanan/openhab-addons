/**
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

import static org.openhab.binding.lutron.internal.hw.HwConstants.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.CommonTriggerEvents;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base handler with logic shared by the HomeWorks serial and IP bridges: monitor-command startup, incoming message
 * dispatch to device handlers, and daily processor time sync.
 *
 * @author Ajay Sanan - Initial contribution
 */
public abstract class HwBridgeHandler extends BaseBridgeHandler {
    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");
    private ScheduledFuture<?> updateTimeJob;

    private final Logger logger = LoggerFactory.getLogger(HwBridgeHandler.class);

    private HwDiscoveryService discoveryService;

    public HwBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    public void setDiscoveryService(HwDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(HwDiscoveryService.class);
    }

    public void startUpdateProcessorTimeJob() {
        if (updateTimeJob != null) {
            logger.debug("Canceling old scheduled job");
            updateTimeJob.cancel(false);
            updateTimeJob = null;
        }
        updateTimeJob = scheduler.scheduleWithFixedDelay(this::updateProcessorTime, 0, 1, TimeUnit.DAYS);
    }

    private void updateProcessorTime() {
        LocalDateTime date = LocalDateTime.now();
        String dateString = date.format(dateFormat);
        String timeString = date.format(timeFormat);
        logger.debug("Updating HomeWorks processor date and time to {} {}", dateString, timeString);

        Bridge bridge = getThing();
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            logger.warn("HomeWorks Bridge is offline and cannot update time on HomeWorks processor.");
            if (updateTimeJob != null) {
                updateTimeJob.cancel(false);
                updateTimeJob = null;
            }
            return;
        }

        sendCommand(String.format("%s, %s", HW_COMMAND_SETDATE, dateString));
        sendCommand(String.format("%s, %s", HW_COMMAND_SETTIME, timeString));
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Unexpected command for HomeWorks Bridge: {} - {}", channelUID, command);
    }

    public abstract void sendCommand(String command);

    public void sendMonitorCommands() {
        logger.debug("Sending monitoring commands.");
        sendCommand(HW_COMMAND_MONITORPROMPTOFF);
        sendCommand(HW_COMMAND_MONITORKEYPADON);
        sendCommand(HW_COMMAND_MONITORLEDON);
        sendCommand(HW_COMMAND_MONITORGRAFIKEYEOFF);
        sendCommand(HW_COMMAND_MONITORDIMMERON);
        sendCommand(HW_COMMAND_MONITORDRIVEROFF);
    }

    public HwDeviceHandler findHandler(String address) {
        for (Thing thing : getThing().getThings()) {
            try {
                if (thing.getHandler() instanceof HwDeviceHandler handler && address.equals(handler.getAddress())) {
                    return handler;
                }
            } catch (IllegalStateException e) {
                logger.trace("Handler for id {} not initialized", address);
            }
        }
        return null;
    }

    public void declareDevice(ThingTypeUID newThingTypeUID, String address) {
        if (discoveryService != null) {
            discoveryService.declareUnknownDevice(newThingTypeUID, address);
        }
    }

    public void handleIncomingMessage(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        logger.debug("Received message from HomeWorks processor: {}", line);
        String[] data = line.replaceAll("\\s", "").toUpperCase().split(",");
        if (data.length > 1) {
            try {
                String address = data[1];
                if (HW_RESPONSE_DIMMERLEVEL.equals(data[0])) {
                    if (findHandler(address) instanceof HwDimmerHandler dimmerHandler) {
                        Integer level = Integer.parseInt(data[2]);
                        dimmerHandler.handleUpdate(level);
                    } else {
                        declareDevice(THING_TYPE_HWDIMMER, address);
                    }
                } else if (HW_KEYPADBUTTONPRESS.equals(data[0]) || HW_KEYPADBUTTONRELEASE.equals(data[0])
                        || HW_KEYPADBUTTONDOUBLETAP.equals(data[0]) || HW_KEYPADBUTTONHOLD.equals(data[0])) {
                    if (findHandler(address) instanceof HwKeypadHandler keypadHandler) {
                        keypadHandler.handleButtonEvent(data[2], getButtonEvent(data[0]));
                    } else {
                        declareDevice(THING_TYPE_HWKEYPAD, address);
                    }
                } else if (HW_RESPONSE_LEDSTATE.equals(data[0]) || HW_RESPONSE_KEYPADSTATE.equals(data[0])
                        || HW_RESPONSE_LASTKEY.equals(data[0])) {
                    if (findHandler(address) instanceof HwKeypadHandler stateHandler) {
                        stateHandler.handleUpdate(data[0], data[2]);
                    } else {
                        declareDevice(THING_TYPE_HWKEYPAD, address);
                    }
                } else {
                    logger.debug("Ignoring message {}", line);
                }
            } catch (RuntimeException e) {
                logger.error("Error parsing incoming message", e);
            }
        }
    }

    private String getButtonEvent(String command) {
        switch (command) {
            case HW_KEYPADBUTTONPRESS:
                return CommonTriggerEvents.PRESSED;
            case HW_KEYPADBUTTONRELEASE:
                return CommonTriggerEvents.RELEASED;
            case HW_KEYPADBUTTONHOLD:
                return CommonTriggerEvents.LONG_PRESSED;
            case HW_KEYPADBUTTONDOUBLETAP:
                return CommonTriggerEvents.DOUBLE_PRESSED;
            default:
                return "";
        }
    }
}