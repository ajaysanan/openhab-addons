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

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;
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
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler responsible for communicating with the Synaccess PDU. The handler uses the telnet session leveraged
 * from the lutron binding.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class IPBridgeHandler extends BaseBridgeHandler {
    private static final Pattern RESPONSE_REGEX = Pattern.compile("(\\$A[0-7F])[ ,]?([01]*)?[ ,]?([01]*)");
    private static final Pattern STATUS_REGEX = Pattern.compile("(Goodbye\\!|Password:|Invalid ID\\/PWD|HW).*");

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final int DEFAULT_RECONNECT_MINUTES = 5;
    private static final int DEFAULT_HEARTBEAT_MINUTES = 5;
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(IPBridgeHandler.class);

    private IPBridgeConfig config = new IPBridgeConfig();

    private int reconnectInterval;
    private int heartbeatInterval;
    private int sendDelay;

    private boolean authRequired;

    private TelnetSession session;
    private BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    private @Nullable Thread messageSenderThread;
    private @Nullable ScheduledFuture<?> keepaliveRecurringJob;
    private @Nullable ScheduledFuture<?> reconnectJob;
    private @Nullable ScheduledFuture<?> connectRetryRecurringJob;

    protected @Nullable SynaccessDiscoveryService discoveryService;

    public IPBridgeHandler(Bridge bridge) {
        super(bridge);

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
        this.config = getThing().getConfiguration().as(IPBridgeConfig.class);
        if (validConfiguration(this.config)) {
            reconnectInterval = (config.reconnect > 0) ? config.reconnect : DEFAULT_RECONNECT_MINUTES;
            heartbeatInterval = (config.heartbeat > 0) ? config.heartbeat : DEFAULT_HEARTBEAT_MINUTES;
            sendDelay = (config.delay < 0) ? 0 : config.delay;

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "Connecting");
            // start the async connect task so can return quickly
            scheduler.submit(this::connect);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // No commands in the Bridge Thing
    }

    public void setDiscoveryService(SynaccessDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public IPBridgeConfig getIPBridgeConfig() {
        return config;
    }

    private boolean validConfiguration(IPBridgeConfig config) {
        if (config.ipAddress == "") {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge configuration missing");
            return false;
        }
        if (config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge address not specified");
            return false;
        }
        return true;
    }

    private void scheduleConnectRetry(long delay, long interval) {
        if (connectRetryRecurringJob == null) {
            logger.debug("Scheduling connection retry job in {} minutes with interval {} minutes", delay, interval);
            connectRetryRecurringJob = scheduler.scheduleWithFixedDelay(this::connect, delay, interval,
                    TimeUnit.MINUTES);
        }
    }

    private void scheduleKeepAlive(long heartbeatInterval) {
        if (keepaliveRecurringJob == null) {
            logger.debug("Starting keepAlive job with interval {} minutes", heartbeatInterval);
            keepaliveRecurringJob = scheduler.scheduleWithFixedDelay(this::sendKeepAlive, heartbeatInterval,
                    heartbeatInterval, TimeUnit.MINUTES);
        }
    }

    private synchronized void connect() {
        if (this.session.isConnected()) {
            config = getConfigAs(IPBridgeConfig.class);
            return;
        }

        logger.debug("Connecting to bridge at {}", config.ipAddress);

        try {
            this.session.open(config.ipAddress, config.port);
            if (!this.session.waitFor("Telnet", 2000)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "connection to invalid device");
                disconnect();
                return;
            }
            // The device sends no connection acknowledgment. Needs auth if requests User ID
            authRequired = this.session.waitFor("User ID:", 2000);
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            disconnect();
            scheduleConnectRetry(reconnectInterval, reconnectInterval * 5); // Possibly a temporary problem. Try again
                                                                            // later.
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR, "login interrupted");
            disconnect();
            scheduleConnectRetry(reconnectInterval, reconnectInterval * 5); // Possibly a temporary problem. Try again
                                                                            // later.
            return;
        }

        messageSenderThread = new Thread(this::sendCommandsThread, "Synaccess sender");
        messageSenderThread.start();

        if (authRequired) {
            updateStatus(ThingStatus.UNKNOWN);
            sendCommand(config.user != "" ? config.user : DEFAULT_USER);
        } else {
            connectTasks();
        }
    }

    private void connectTasks() {
        if (this.session.isConnected()) {
            scheduleKeepAlive(heartbeatInterval);

            if (connectRetryRecurringJob != null) {
                connectRetryRecurringJob.cancel(true);
            }

            Map<String, String> props = this.editProperties();
            props.putIfAbsent("Connection Date", LocalDate.now().toString());
            String connects = props.putIfAbsent("Connection Attempts", "0");
            if (connects != null) {
                Integer newconn = Integer.parseInt(connects) + 1;
                props.put("Connection Attempts", newconn.toString());
            }
            this.updateProperties(props);
            sendCommand("$A5");
        }
    }

    private void sendCommandsThread() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = sendQueue.take();
                logger.trace("Sending command {}", command);
                try {
                    session.writeLine(command.toString());
                } catch (IOException e) {
                    logger.warn("Communication error, will try to reconnect. Error: {}", e.getMessage());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                    sendQueue.add(command); // Requeue command

                    reconnect();
                    // reconnect() will start a new commands thread but will first disconnect to terminate this one
                    break;
                }
                if (sendDelay > 0) {
                    Thread.sleep(sendDelay); // introduce delay to throttle send rate
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void disconnect() {
        logger.debug("Disconnecting from bridge");

        if (connectRetryRecurringJob != null) {
            connectRetryRecurringJob.cancel(true);
        }

        if (this.keepaliveRecurringJob != null) {
            this.keepaliveRecurringJob.cancel(true);
        }

        if (this.reconnectJob != null) {
            // This method can be called from the keepAliveReconnect thread. Make sure
            // we don't interrupt ourselves, as that may prevent the reconnection attempt.
            this.reconnectJob.cancel(false);
        }

        if (messageSenderThread != null) {
            messageSenderThread.interrupt();
        }

        try {
            if (this.session.isConnected()) {
                // try to log out gracefully
                logger.debug("Attempting to log out");
                this.session.writeLine("logout");
                this.session.waitFor("Goodbye!", 500);
            }
            this.session.close();
        } catch (IOException e) {
            logger.warn("Error disconnecting: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Error disconnecting: {}", e.getMessage());
        }
    }

    private synchronized void reconnect() {
        logger.debug("Keepalive timeout or comm error, attempting to reconnect to the bridge");

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE);
        disconnect();
        this.scheduleConnectRetry(1, reconnectInterval);
    }

    void sendCommand(String command) {
        this.sendQueue.add(command);
    }

    private @Nullable PDUHandler findThingHandler() {
        for (Thing thing : getThing().getThings()) {
            if (thing.getHandler() instanceof PDUHandler) {
                PDUHandler handler = (PDUHandler) thing.getHandler();

                try {
                    if (handler != null) {
                        return handler;
                    }
                } catch (IllegalStateException e) {
                    logger.trace("Handler not initialized");
                }
            }
        }
        if (discoveryService != null) {
            discoveryService.notifyDiscoveredPDU(config.ipAddress);
        }
        return null;
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
                    logger.trace("IPBridgehandler parseUpdates: Received message: -->{}<--", line);
                    switch (statusmatch.group(1)) {
                        // Responds to password with string of '*' equal in length to sent password whether correct or
                        // not. "Goodbye!" or "Invalid ID/PWD" is failure but '*' string and no further response
                        // indicates success.
                        case "Goodbye!":
                            if (this.session.isConnected()) {
                                logger.debug("Disconnected; retry in {} minutes", reconnectInterval);
                                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE);
                                this.reconnect();
                                return;
                            } else {
                                logger.debug("Session disconnected");
                            }
                            break;
                        case "Password:":
                            sendCommand(config.password != "" ? config.password : DEFAULT_PASSWORD);
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
                    logger.trace("IPBridgehandler parseUpdates: Ignoring message: -->{}<--", line);
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

            PDUHandler handler = findThingHandler();
            if ((handler != null)) {
                logger.trace("IPBridgehandler parseUpdates: Received message: -->{}<--", line);
                try {
                    switch (responsematch.group(1)) {
                        // case $A0 is acknowledge, sometimes with data; $A5, $A7, $A3 are echos
                        case "$A0":
                            // If has power data, evaluate it otherwise discard
                            if (responsematch.group(2).length() > 1) {
                                // Create channels if not done already
                                handler.configureChannels(responsematch.group(2).length());
                                for (int i = 1; i <= responsematch.group(2).length(); i++) {
                                    handler.handleUpdate(Integer.toString(i),
                                            responsematch.group(2).substring(i - 1, i));
                                }
                            }
                            // Get the firmware version if we don't have it yet
                            Map<String, String> props = editProperties();
                            if (props.get("Firmware Version") == null || props.get("Firmware Version") == "") {
                                sendCommand("ver");
                            }
                            return true;
                        case "$A3":
                            if (!responsematch.group(2).isEmpty() && !responsematch.group(3).isEmpty()) {
                                handler.handleUpdate(responsematch.group(2), responsematch.group(3));
                            }
                            return true;
                        case "$A7":
                            if (!responsematch.group(2).isEmpty()) {
                                for (int i = 1; i <= handler.totalPorts; i++) {
                                    handler.handleUpdate(Integer.toString(i), responsematch.group(2));
                                }
                            }
                            return true;
                        case "$AF":
                            logger.warn("Error in message", line);
                            return true;
                        case "$A5":
                            return true;
                    }
                } catch (RuntimeException e) {
                    logger.warn("Runtime exception while processing update: line {}: {}", line, e);
                }
            } else {
                if (discoveryService != null) {
                    discoveryService.notifyDiscoveredPDU(config.ipAddress);
                }
            }
        }
        return false;
    }

    private void sendKeepAlive() {
        logger.debug("Scheduling keepalive reconnect job");

        // Reconnect if no response is received within 30 seconds.
        reconnectJob = scheduler.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        logger.trace("Sending keepalive query");
        sendCommand("$A5");
    }

    @Override
    public void thingUpdated(Thing thing) {
        IPBridgeConfig newConfig = thing.getConfiguration().as(IPBridgeConfig.class);
        boolean validConfig = validConfiguration(newConfig);
        boolean needsReconnect = validConfig && !this.config.sameConnectionParameters(newConfig);

        if (!validConfig || needsReconnect) {
            dispose();
        }

        this.thing = thing;
        this.config = newConfig;

        if (needsReconnect) {
            initialize();
        }
    }

    @Override
    public void dispose() {
        disconnect();
        super.dispose();
    }

}
