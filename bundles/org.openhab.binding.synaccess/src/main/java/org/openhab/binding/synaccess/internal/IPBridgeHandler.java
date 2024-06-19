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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
public class IPBridgeHandler extends BaseBridgeHandler {
    private static final Pattern RESPONSE_REGEX = Pattern.compile("(\\$A[0-7F]|Goodbye\\!)[ ,]?([01]*)?[ ,]?([01]*)");

    private static final String DEFAULT_USER = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final int DEFAULT_RECONNECT_MINUTES = 5;
    private static final int DEFAULT_HEARTBEAT_MINUTES = 5;
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(IPBridgeHandler.class);

    private IPBridgeConfig config;

    private int reconnectInterval;
    private int heartbeatInterval;
    private int sendDelay;

    private TelnetSession session;
    private BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    private Thread messageSender;
    private ScheduledFuture<?> keepAlive;
    private ScheduledFuture<?> keepAliveReconnect;
    private ScheduledFuture<?> connectRetryJob;

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
            scheduler.submit(this::connect); // start the async connect task
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

    }

    public void setDiscoveryService(SynaccessDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    public IPBridgeConfig getIPBridgeConfig() {
        return config;
    }

    private boolean validConfiguration(IPBridgeConfig config) {
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge configuration missing");
            return false;
        }
        if (config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge address not specified");
            return false;
        }
        return true;
    }

    private void scheduleConnectRetry(long waitMinutes) {
        logger.debug("Scheduling connection retry in {} minutes", waitMinutes);
        connectRetryJob = scheduler.schedule(this::connect, waitMinutes, TimeUnit.MINUTES);
    }

    private synchronized void connect() {
        if (this.session.isConnected()) {
            config = getConfigAs(IPBridgeConfig.class);
            return;
        }

        logger.debug("Connecting to bridge at {}", config.ipAddress);

        try {
            this.session.open(config.ipAddress, config.port);
            if (!this.session.waitFor("User ID:", 2000)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "connection to invalid device");
                disconnect();
                return;
            }
            if (!login(config)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "invalid username/password");
                disconnect();
                return;
            }
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
            disconnect();
            scheduleConnectRetry(reconnectInterval); // Possibly a temporary problem. Try again later.
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_INITIALIZING_ERROR, "login interrupted");
            disconnect();
            return;
        }

        updateStatus(ThingStatus.ONLINE);
        messageSender = new Thread(this::sendCommandsThread, "Synaccess sender");
        messageSender.start();

        // sendCommand("$A5"); Don't need to do this; PDU does it if one exists otherwise will do on keepalive
        logger.debug("Starting keepAlive job with interval {} minutes", heartbeatInterval);
        keepAlive = scheduler.scheduleWithFixedDelay(this::sendKeepAlive, heartbeatInterval, heartbeatInterval,
                TimeUnit.MINUTES);
    }

    private void sendCommandsThread() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = sendQueue.take();
                logger.debug("Sending command {}", command);
                try {
                    session.writeLine(command.toString());
                } catch (IOException e) {
                    logger.warn("Communication error, will try to reconnect. Error: {}", e.getMessage());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
                    sendQueue.add(command); // Requeue command

                    reconnect();
                    // reconnect() will start a new thread; terminate this one
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

        if (connectRetryJob != null) {
            connectRetryJob.cancel(true);
        }

        if (this.keepAlive != null) {
            this.keepAlive.cancel(true);
        }

        if (this.keepAliveReconnect != null) {
            // This method can be called from the keepAliveReconnect thread. Make sure
            // we don't interrupt ourselves, as that may prevent the reconnection attempt.
            this.keepAliveReconnect.cancel(false);
        }

        if (messageSender != null && messageSender.isAlive()) {
            messageSender.interrupt();
        }

        try {
            this.session.close();
        } catch (IOException e) {
            logger.warn("Error disconnecting: {}", e.getMessage());
        }
    }

    private synchronized void reconnect() {
        logger.debug("Keepalive timeout, attempting to reconnect to the bridge");

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE);
        disconnect();
        connect();
    }

    private boolean login(IPBridgeConfig config) throws IOException, InterruptedException {
        logger.trace("Sending command {}", config.user != null ? config.user : DEFAULT_USER);
        this.session.writeLine(config.user != null ? config.user : DEFAULT_USER);
        this.session.waitFor("Password:\\*", 2000);
        logger.trace("Sending command {}", config.password != null ? config.password : DEFAULT_PASSWORD);
        this.session.writeLine(config.password != null ? config.password : DEFAULT_PASSWORD);
        // Synaccess responds with string of '*' equal in length to sent password whether correct or not so ignore those
        // int length = config.password != null ? config.password.length() : DEFAULT_PASSWORD.length();
        // this.session.waitFor("(\\*{" + length + "})", 2000);
        // "Goodbye!" or "Invalid ID/PWD" is failure but '*' string and no further response indicates success
        return !this.session.waitFor("Invalid ID.PWD", 2000);
    }

    void sendCommand(String command) {
        this.sendQueue.add(command);
    }

    private PDUHandler findThingHandler() {
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
        if (thing.getStatus() != ThingStatus.ONLINE) {
            return;
        }
        for (String line : this.session.readLines()) {
            if (line.trim().equals("")) {
                // Sometimes we get an empty line (possibly only when prompts are disabled). Ignore them.
                continue;
            }

            logger.trace("IPBridgehandler parseUpdates: Received message: -->{}<--", line);

            // System is alive, cancel reconnect task.
            if (this.keepAliveReconnect != null) {
                this.keepAliveReconnect.cancel(true);
            }

            Matcher matcher = RESPONSE_REGEX.matcher(line);
            boolean responseMatched = matcher.find();

            if (!responseMatched) {
                logger.debug("IPBridgehandler parseUpdates: Ignoring message: -->{}<--", line);
                continue;
            } else {
                // We have a good response message
                PDUHandler handler = findThingHandler();
                if (handler != null) {
                    try {
                        switch (matcher.group(1)) {
                            // case $A0 is acknowledge, sometimes with data; $A5, $A7, $A3 are echos
                            case "$A0":
                                // If has power data, evaluate it otherwise discard
                                if (matcher.group(2).length() > 1) {
                                    // Create channels if not done already
                                    handler.configureChannels(matcher.group(2).length());
                                    for (int i = 1; i <= matcher.group(2).length(); i++) {
                                        handler.handleUpdate(Integer.toString(i), matcher.group(2).substring(i - 1, i));
                                    }
                                }
                                break;
                            case "$A3":
                                if (!matcher.group(2).isEmpty() && !matcher.group(3).isEmpty()) {
                                    handler.handleUpdate(matcher.group(2), matcher.group(3));
                                }
                                break;
                            case "$A7":
                                if (!matcher.group(2).isEmpty()) {
                                    for (int i = 1; i <= handler.totalPorts; i++) {
                                        handler.handleUpdate(Integer.toString(i), matcher.group(2));
                                    }
                                }
                                break;
                            case "$AF":
                                logger.warn("Error in message", line);
                                break;
                            case "Goodbye!":
                                logger.info("Disconnecting; retry in {} minutes", reconnectInterval);
                                this.disconnect();
                                this.scheduleConnectRetry(reconnectInterval);
                                break;
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
        }
    }

    private void sendKeepAlive() {
        logger.debug("Scheduling keepalive reconnect job");

        // Reconnect if no response is received within 30 seconds.
        keepAliveReconnect = scheduler.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

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
    }

}
