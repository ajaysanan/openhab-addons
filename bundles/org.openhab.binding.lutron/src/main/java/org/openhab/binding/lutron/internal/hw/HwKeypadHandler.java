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

import static org.openhab.binding.lutron.internal.LutronBindingConstants.BINDING_ID;
import static org.openhab.binding.lutron.internal.hw.HwConstants.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openhab.binding.lutron.internal.hw.HwKeypadModelData.KeypadLayout;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends {@link HwDeviceHandler} to support legacy HomeWorks wired seeTouch keypads, building
 * button and LED channels dynamically based on the configured keypad model.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class HwKeypadHandler extends HwDeviceHandler {
    private static final String CHANNEL_BUTTON_PREFIX = "button";
    private static final String CHANNEL_LED_PREFIX = "led";
    private static final String CHANNEL_LASTKEYPRESSED = "lastkeypressed";
    private static final String CHANNEL_KEYPADENABLED = "keypadenabled";

    private final Logger logger = LoggerFactory.getLogger(HwKeypadHandler.class);

    public HwKeypadHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        HwKeypadConfig config = getConfigAs(HwKeypadConfig.class);

        String address = config.getAddress();
        setAddress(address);
        if (address == null || address.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Address not set");
            return;
        }

        logger.debug("Initializing Keypad Handler for address {}, model {}", address, config.getModel());

        if (getThing().getBridgeUID() == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }

        KeypadLayout layout = HwKeypadModelData.getLayout(config.getModel());
        configureChannels(layout);

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Awaiting initial response");
        scheduler.schedule(this::initDeviceState, 3, TimeUnit.SECONDS);
    }

    private void configureChannels(KeypadLayout layout) {
        logger.debug("Configuring channels for keypad {}", getAddress());

        // Preserve the thing-type's static channels (lastkeypressed, keypadenabled); rebuild only the
        // model-dependent button/LED channels.
        List<Channel> channelList = new ArrayList<>();
        for (Channel existing : getThing().getChannels()) {
            String id = existing.getUID().getId();
            if (!id.startsWith(CHANNEL_BUTTON_PREFIX) && !id.startsWith(CHANNEL_LED_PREFIX)) {
                channelList.add(existing);
            }
        }

        ChannelTypeUID triggerType = new ChannelTypeUID(BINDING_ID, "buttonTrigger");
        for (Integer buttonNumber : layout.getButtonNumbers()) {
            ChannelUID channelUID = new ChannelUID(getThing().getUID(), CHANNEL_BUTTON_PREFIX + buttonNumber);
            Channel channel = ChannelBuilder.create(channelUID).withType(triggerType).withKind(ChannelKind.TRIGGER)
                    .withLabel("Button " + buttonNumber).build();
            channelList.add(channel);
        }

        ChannelTypeUID ledType = new ChannelTypeUID(BINDING_ID, "ledIndicatorAdvanced");
        for (Integer ledNumber : layout.getLedButtonNumbers()) {
            ChannelUID channelUID = new ChannelUID(getThing().getUID(), CHANNEL_LED_PREFIX + ledNumber);
            Channel channel = ChannelBuilder.create(channelUID, "Switch").withType(ledType)
                    .withLabel("LED " + ledNumber).build();
            channelList.add(channel);
        }

        ThingBuilder thingBuilder = editThing();
        thingBuilder.withChannels(channelList);
        updateThing(thingBuilder.build());

        logger.debug("Done configuring channels for keypad {}", getAddress());
    }

    public void initDeviceState() {
        logger.debug("Initializing device state for Keypad {}", getAddress());
        sendToBridge(String.format("%s, %s", HW_COMMAND_LEDGET, getAddress()));
        sendToBridge(String.format("%s, %s", HW_COMMAND_KEYPADGETSTATE, getAddress()));
        sendToBridge(String.format("%s, %s", HW_COMMAND_KEYPADLASTBUTTON, getAddress()));
    }

    public void handleButtonEvent(String button, String event) {
        logger.trace("Handling trigger for button {}: {} from keypad {}", button, event, getAddress());
        String channelId = CHANNEL_BUTTON_PREFIX + button;
        if (getThing().getChannel(channelId) != null) {
            triggerChannel(channelId, event);
        } else {
            logger.debug("Ignoring event for unconfigured button {} on keypad {}", button, getAddress());
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();
        Channel channel = getThing().getChannel(id);

        if (channel == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }

        if (id.startsWith(CHANNEL_LED_PREFIX)) {
            handleLedCommand(id, command);
        } else if (CHANNEL_KEYPADENABLED.equals(id)) {
            handleEnabledCommand(command);
        } else if (CHANNEL_LASTKEYPRESSED.equals(id)) {
            if (command instanceof RefreshType) {
                sendToBridge(String.format("%s, %s", HW_COMMAND_KEYPADLASTBUTTON, getAddress()));
            }
        }
        // Button channels are trigger-kind and never receive commands.
    }

    private void handleLedCommand(String channelId, Command command) {
        String ledNumber = channelId.substring(CHANNEL_LED_PREFIX.length());
        if (command instanceof OnOffType) {
            String ledState = (command == OnOffType.ON) ? "1" : "0";
            sendToBridge(String.format("%s, %s, %s, %s", HW_COMMAND_LEDSET, getAddress(), ledNumber, ledState));
        } else if (command instanceof RefreshType) {
            sendToBridge(String.format("%s, %s", HW_COMMAND_LEDGET, getAddress()));
        } else {
            logger.warn("Invalid command type {} received for channel {} device {}", command, channelId,
                    getThing().getUID());
        }
    }

    private void handleEnabledCommand(Command command) {
        if (command instanceof OnOffType) {
            String cmd = (command == OnOffType.ON) ? HW_COMMAND_KEYPADENABLE : HW_COMMAND_KEYPADDISABLE;
            sendToBridge(String.format("%s, %s", cmd, getAddress()));
            // No response is sent from the keypad after enable/disable, so request it explicitly.
            sendToBridge(String.format("%s, %s", HW_COMMAND_KEYPADGETSTATE, getAddress()));
        } else if (command instanceof RefreshType) {
            sendToBridge(String.format("%s, %s", HW_COMMAND_KEYPADGETSTATE, getAddress()));
        } else {
            logger.warn("Invalid command type {} received for channel keypadenabled device {}", command,
                    getThing().getUID());
        }
    }

    public void handleUpdate(String command, String state) {
        logger.trace("Handling status for {}: {} from keypad {}", command, state, getAddress());

        if (getThing().getStatus() == ThingStatus.UNKNOWN) {
            updateStatus(ThingStatus.ONLINE); // set thing status online if this is an initial response
        }

        if (HW_RESPONSE_LEDSTATE.equals(command)) {
            for (int i = 0; i < state.length(); i++) {
                String channelId = CHANNEL_LED_PREFIX + (i + 1);
                if (getThing().getChannel(channelId) != null) {
                    updateState(channelId, OnOffType.from(state.charAt(i) == '1'));
                }
            }
        } else if (HW_RESPONSE_KEYPADSTATE.equals(command)) {
            updateState(CHANNEL_KEYPADENABLED, OnOffType.from("ENABLED".equals(state)));
        } else if (HW_RESPONSE_LASTKEY.equals(command)) {
            updateState(CHANNEL_LASTKEYPRESSED, new StringType(state));
        }
    }
}