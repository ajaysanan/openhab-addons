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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.synaccess.internal.net.TelnetSession;
import org.openhab.binding.synaccess.internal.net.TelnetSessionListener;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler responsible for communicating with a Synaccess PDU over its telnet interface. The handler uses
 * the telnet session leveraged from the lutron binding.
 *
 * There is always exactly one PDU per connection, so this single handler owns both the connection
 * lifecycle and the switch/command channels for the device - there is no separate bridge/child-thing split.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class IPBridgeHandler extends BaseThingHandler {
    private static final Pattern RESPONSE_REGEX = Pattern.compile("(\\$A[0-7F])[ ,]?([01]*)?[ ,]?([01]*)");
    private static final Pattern STATUS_REGEX = Pattern.compile("(Goodbye\\!|Password:|Invalid ID\\/PWD|HW).*");

    private final Logger logger = LoggerFactory.getLogger(IPBridgeHandler.class);

    private IPBridgeConfig config = new IPBridgeConfig();

    private int reconnectInterval;
    private int heartbeatInterval;

    private boolean authRequired;

    private TelnetSession session;
    private BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    private @Nullable Thread messageSenderThread;
    private @Nullable ScheduledFuture<?> keepaliveRecurringJob;
    private @Nullable ScheduledFuture<?> reconnectJob;
    private @Nullable ScheduledFuture<?> connectRetryRecurringJob;

    private volatile boolean isDisposed = false;

    // Channel/command state, formerly in the separate PDUHandler.
    private long lastChannelUpdateTime = 0;

    public IPBridgeHandler(Thing thing) {
        super(thing);

        this.session = new TelnetSession();

        this.session.addListener(new TelnetSessionListener() {
            @Override
            public void inputAvailable() {
                parseUpdates();
            }

            @Override
            public void error(IOException exception) {
            }
        });
    }

    @Override
    public void initialize() {
        // Reset in case this handler instance is being reused after a manual dispose()/initialize()
        // cycle (see handleConfigurationUpdate()) rather than being replaced by the handler factory.
        isDisposed = false;

        this.config = getConfigAs(IPBridgeConfig.class);
        if (validConfiguration(this.config)) {
            reconnectInterval = (config.reconnect > 0) ? config.reconnect : DEFAULT_RECONNECT_MINUTES;
            heartbeatInterval = (config.heartbeat > 0) ? config.heartbeat : DEFAULT_HEARTBEAT_MINUTES;

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "Connecting");
            // start the async connect task so can return quickly
            scheduler.submit(this::connect);
        }
    }

    private boolean validConfiguration(IPBridgeConfig config) {
        if (config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address not specified");
            return false;
        }
        return true;
    }

    private synchronized void connect() {
        if (isDisposed) {
            logger.trace("{} is disposed; ignoring connection attempt", thing.getUID());
            return;
        }

        if (this.session.isConnected()) {
            logger.trace("Device {} already connected; ignoring repeat connection", thing.getUID());
            config = getConfigAs(IPBridgeConfig.class);
            return;
        }

        logger.info("Connecting to {} at {}", thing.getUID(), config.ipAddress);

        try {
            this.session.open(config.ipAddress, config.port);
            if (!this.session.waitFor("Telnet", 2000)) {
                connectError(ThingStatusDetail.CONFIGURATION_ERROR, "device not ready or connection to invalid device");
                return;
            }
            // The device sends no connection acknowledgment. Needs auth if requests User ID
            authRequired = this.session.waitFor("User ID:", 2000);
        } catch (IOException e) {
            connectError(ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectError(ThingStatusDetail.HANDLER_INITIALIZING_ERROR, "login interrupted");
            return;
        }

        messageSenderThread = new Thread(this::sendCommandsThread, "Synaccess sender");
        messageSenderThread.start();

        logger.trace("{} connected (still needs authorization: {})", thing.getUID(), authRequired);
        if (authRequired) {
            updateStatus(ThingStatus.UNKNOWN);
            sendCommand(!config.user.isEmpty() ? config.user : DEFAULT_USER);
        } else {
            connectTasks();
        }
    }

    private void connectError(ThingStatusDetail detail, @Nullable String errMessage) {
        updateStatus(ThingStatus.OFFLINE, detail, errMessage);
        disconnect();
        if (!isDisposed) {
            // Possibly a temporary problem. Try again later.
            scheduleConnectRetry(reconnectInterval);
        }
    }

    private void connectTasks() {
        if (this.session.isConnected()) {
            scheduleKeepAlive(heartbeatInterval);

            if (connectRetryRecurringJob != null) {
                connectRetryRecurringJob.cancel(false);
            }

            Map<String, String> props = this.editProperties();
            props.remove("firmwareVersion");
            props.putIfAbsent("Connection Date", LocalDate.now().toString());
            String connects = props.putIfAbsent("Connection Attempts", "1");
            if (connects != null) {
                Integer newconn = Integer.parseInt(connects) + 1;
                props.put("Connection Attempts", newconn.toString());
            }
            this.updateProperties(props);
            sendCommand("$A5");
        }
    }

    private void scheduleConnectRetry(long delay) {
        if (connectRetryRecurringJob != null) {
            connectRetryRecurringJob.cancel(true);
        }
        logger.info("{} scheduling connection retry job in {} minutes", thing.getUID(), delay);
        connectRetryRecurringJob = scheduler.schedule(this::connect, delay, TimeUnit.MINUTES);
    }

    private void scheduleKeepAlive(long heartbeatInterval) {
        if (keepaliveRecurringJob != null) {
            keepaliveRecurringJob.cancel(true);
        }
        logger.debug("{} starting keepAlive job with interval {} minutes", thing.getUID(), heartbeatInterval);
        keepaliveRecurringJob = scheduler.scheduleWithFixedDelay(this::sendKeepAlive, heartbeatInterval,
                heartbeatInterval, TimeUnit.MINUTES);
    }

    private void sendCommandsThread() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = sendQueue.take();
                logger.trace("{} sending command {}", thing.getUID(), command);
                try {
                    session.writeLine(command.toString());
                } catch (IOException e) {
                    sendQueue.add(command); // Requeue command
                    connectError(ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                    break;
                }
                if (COMMAND_SEND_DELAY_MILLIS > 0) {
                    Thread.sleep(COMMAND_SEND_DELAY_MILLIS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void disconnect() {
        logger.debug("{} disconnecting from device", thing.getUID());

        if (connectRetryRecurringJob != null) {
            connectRetryRecurringJob.cancel(false);
            connectRetryRecurringJob = null;
        }

        if (this.keepaliveRecurringJob != null) {
            this.keepaliveRecurringJob.cancel(true);
            this.keepaliveRecurringJob = null;
        }

        if (this.reconnectJob != null) {
            // This method can be called from the keepAliveReconnect thread. Make sure
            // we don't interrupt ourselves, as that may prevent the reconnection attempt.
            this.reconnectJob.cancel(false);
            this.reconnectJob = null;
        }

        if (messageSenderThread != null) {
            messageSenderThread.interrupt();
        }

        logout();
        try {
            this.session.close();
        } catch (IOException e) {
            logger.warn("Error closing port on {}: {}", thing.getUID(), e.getMessage());
        }

    }

    private synchronized void logout() {
        try {
            if (this.session.isConnected()) {
                // try to log out gracefully
                logger.debug("Attempting to log out from {}", thing.getUID());
                this.session.writeLine("logout");
                this.session.waitFor("Goodbye!", 500);
            }
        } catch (IOException e) {
            logger.debug("Error writing to port on {}; already disconnected: {}", thing.getUID(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Error disconnecting from {}: {}", thing.getUID(), e.getMessage());
        }
    }

    private synchronized void reconnect() {
        if (isDisposed) {
            return;
        }

        logger.info("{} keepalive timeout or comm error, attempting to reconnect to the device", thing.getUID());

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE);
        disconnect();
        this.scheduleConnectRetry(1);
    }

    void sendCommand(String command) {
        // check for duplicates
        if (!sendQueue.contains(command)) {
            this.sendQueue.add(command);
        }
    }

    private void parseUpdates() {
        Thing thing = this.getThing();
        if (thing.getStatus() != ThingStatus.ONLINE && thing.getStatus() != ThingStatus.UNKNOWN) {
            return;
        }
        for (String message : this.session.readLines()) {
            // Sometimes we get an empty line (possibly only when prompts are disabled). Ignore them.
            message = message.trim().replace(">", "");
            if (message.equals("")) {
                continue;
            }
            // System is connected in some way, cancel reconnect task.
            if (this.reconnectJob != null) {
                this.reconnectJob.cancel(true);
            }
            // Split into individual lines and also discard empty lines
            String lines[] = message.split("[\\r\\n]+");

            for (String line : lines) {
                // Check for login/logout/hardware messages then response messages
                Matcher statusmatch = STATUS_REGEX.matcher(line);
                if (statusmatch.find()) {
                    logger.trace("IPBridgehandler parseUpdates: Received message from {}: -->{}<--", thing.getUID(),
                            line);
                    switch (statusmatch.group(1)) {
                        // Responds to password with string of '*' equal in length to sent password whether correct or
                        // not. "Goodbye!" or "Invalid ID/PWD" is failure but '*' string and no further response
                        // indicates success.
                        case "Goodbye!":
                            if (this.session.isConnected()) {
                                logger.debug("{} disconnected; retry in {} minutes", thing.getUID(), reconnectInterval);
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE);
                                this.reconnect();
                                return;
                            } else {
                                logger.debug("{} session disconnected", thing.getUID());
                            }
                            break;
                        case "Password:":
                            sendCommand(!config.password.isEmpty() ? config.password : DEFAULT_PASSWORD);
                            // if invalid pwd, will disconnect quickly so need to recheck the buffer
                            scheduler.schedule(this::parseUpdates, 500, TimeUnit.MILLISECONDS);
                            scheduler.schedule(this::connectTasks, 2000, TimeUnit.MILLISECONDS);
                            break;
                        case "Invalid ID/PWD":
                            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                    "invalid username/password");
                            disconnect();
                            // Do not retry connection; won't work until configuration updated
                            return;
                        case "HW":
                            updateProperty("Firmware Version", line);
                    }
                } else if (!handleResponseMessage(line)) {
                    logger.trace("{} parseUpdates: Ignoring message: -->{}<--", thing.getUID(), line);
                }
            }
        }
    }

    private boolean handleResponseMessage(String line) {
        Matcher responsematch = RESPONSE_REGEX.matcher(line);
        if (responsematch.find()) {

            if (thing.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }

            logger.trace("{} handleResponseMessage: Received message: -->{}<--", thing.getUID(), line);
            try {
                switch (responsematch.group(1)) {
                    // case $A0 is acknowledge, sometimes with data; $A5, $A7, $A3 are echos
                    case "$A0":
                        // If has power data, evaluate it
                        if (responsematch.group(2).length() > 1) {
                            // Create channels if not done already
                            configureChannels(responsematch.group(2).length());
                            for (int i = 1; i <= responsematch.group(2).length(); i++) {
                                handlePortUpdate(i, responsematch.group(2).substring(i - 1, i));
                            }
                        } else {
                            // Something valid happened without data; request data
                            sendCommand("$A5");
                        }
                        // Get the firmware version if we don't have it yet
                        Map<String, String> props = editProperties();
                        if (props.get("Firmware Version") == null || props.get("Firmware Version") == "") {
                            sendCommand("ver");
                        }
                        return true;
                    case "$AF":
                        logger.warn("{}: Error in message", thing.getUID(), line);
                        return true;
                    case "$A5", "$A3", "$A7":
                        return true;
                }
            } catch (RuntimeException e) {
                logger.warn("Runtime exception in {} while processing update: line {}: {}", thing.getUID(), line, e);
            }
        }
        return false;
    }

    private void sendKeepAlive() {
        logger.debug("{}: Scheduling reconnect attempt and sending keepalive query", thing.getUID());

        // Reconnect if no response is received within 30 seconds.
        reconnectJob = scheduler.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        sendCommand("$A5");
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        if (!isModifyingCurrentConfig(configurationParameters)) {
            return;
        }
        validateConfigurationParameters(configurationParameters);

        Configuration configuration = editConfiguration();
        for (Entry<String, Object> configurationParameter : configurationParameters.entrySet()) {
            configuration.put(configurationParameter.getKey(), configurationParameter.getValue());
        }

        IPBridgeConfig newConfig = configuration.as(IPBridgeConfig.class);
        boolean validConfig = validConfiguration(newConfig);
        boolean needsReconnect = validConfig && !this.config.sameConnectionParameters(newConfig);

        if (isInitialized()) {
            if (!validConfig || needsReconnect) {
                dispose();
                updateConfiguration(configuration);
                initialize();
            } else {
                updateConfiguration(configuration);
                this.config = newConfig;
                applyTimingConfig(newConfig);
            }
        } else {
            // Mirrors BaseThingHandler's default behavior for the not-yet-initialized case.
            updateConfiguration(configuration);
            ThingHandlerCallback callback = getCallback();
            if (callback != null) {
                callback.configurationUpdated(getThing());
            } else {
                logger.warn("Handler {} tried updating its configuration although the handler was already disposed.",
                        getClass().getSimpleName());
            }
        }
    }

    /**
     * Applies reconnect/heartbeat/delay changes in place. The live session (if any) is left
     * connected; only the keepalive job is rescheduled, and only if its interval actually
     * changed. reconnectInterval and sendDelay are read fresh wherever they're used, so no
     * further action is needed for those.
     */
    private void applyTimingConfig(IPBridgeConfig newConfig) {
        reconnectInterval = (newConfig.reconnect > 0) ? newConfig.reconnect : DEFAULT_RECONNECT_MINUTES;

        int newHeartbeatInterval = (newConfig.heartbeat > 0) ? newConfig.heartbeat : DEFAULT_HEARTBEAT_MINUTES;
        if (newHeartbeatInterval != heartbeatInterval) {
            heartbeatInterval = newHeartbeatInterval;
            if (this.session.isConnected()) {
                scheduleKeepAlive(heartbeatInterval);
            }
        }
    }

    @Override
    public synchronized void dispose() {
        logger.trace("Disposing {}", thing.getUID());
        isDisposed = true;
        disconnect();
        super.dispose();
    }

    // --- Port switch channels / commands (formerly the separate PDUHandler) ---

    private void configureChannels(int numberChannels) {
        List<Channel> existingChannels = getThing().getChannels();

        if (existingChannels.isEmpty()) {
            logger.debug("Configuring {} channels for PDU {}", numberChannels, thing.getUID());
            ThingBuilder thingBuilder = editThing();
            List<Channel> channelList = new ArrayList<>();

            // port names are 1 based (portstatus1, portstatus2, etc)
            for (int i = 1; i <= numberChannels; i++) {
                ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, "switchState");
                ChannelUID channelUID = new ChannelUID(getThing().getUID(), CHANNEL_PORTSTATUS + Integer.toString(i));
                Channel channel = ChannelBuilder.create(channelUID, "Switch").withType(channelTypeUID)
                        .withLabel("Power Port " + Integer.toString(i)).build();
                channelList.add(channel);
            }

            ChannelTypeUID allPortsTypeUID = new ChannelTypeUID(BINDING_ID, "commandChannel");
            ChannelUID allPortsUID = new ChannelUID(getThing().getUID(), CHANNEL_ALLPORTS);
            Channel allPortsChannel = ChannelBuilder.create(allPortsUID, "String").withType(allPortsTypeUID)
                    .withLabel("All Power Ports").build();
            channelList.add(allPortsChannel);

            thingBuilder.withChannels(channelList);
            updateThing(thingBuilder.build());
        }
    }

    private void handlePortUpdate(int port, String status) {
        // Parameter is the port status (0 or 1) for the given port (1 based)
        BigDecimal state = new BigDecimal(status);
        OnOffType onOff = state.compareTo(BigDecimal.ZERO) == 0 ? OnOffType.OFF : OnOffType.ON;
        logger.debug("{}: updating {}{} to {}", thing.getUID(), CHANNEL_PORTSTATUS, port, onOff);
        updateState(CHANNEL_PORTSTATUS + port, onOff);
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        // Refresh state when new item is linked. Suppress multiple requests sent within 3 seconds
        if ((Instant.now().toEpochMilli() - lastChannelUpdateTime > 3000)
                && channelUID.getId().contains(CHANNEL_PORTSTATUS)) {
            lastChannelUpdateTime = Instant.now().toEpochMilli();
            sendCommand("$A5");
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

        // For portstatus commands handle OnOffType and RefreshType
        if (id.startsWith(CHANNEL_PORTSTATUS)) {
            if (command instanceof OnOffType) {
                StringBuilder outCommand = new StringBuilder("$A3 ");
                // switch 1 based channel port number to 0 based value for the device
                outCommand.append(Integer.parseInt(id.substring(id.length() - 1)) - 1);
                outCommand.append(command.equals(OnOffType.ON) ? " 1" : " 0");
                sendCommand(outCommand.toString());
            } else if (command instanceof RefreshType) {
                sendCommand("$A5");
            } else {
                logger.warn("Invalid command type {} received for channel {} device {}", command, channelUID,
                        getThing().getUID());
            }
            return;
        }
        // For allports command handle stringtype only; write only channel
        if (id.equals(CHANNEL_ALLPORTS) && (command instanceof StringType)) {
            if (command.toString().equals("ALL_ON")) {
                sendCommand("$A7 1");
            } else if (command.toString().equals("ALL_OFF")) {
                sendCommand("$A7 0");
            }
        }
    }
}