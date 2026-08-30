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

import static org.openhab.binding.lutron.internal.hw.HwConstants.HW_COMMAND_GETDATE;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;

import org.openhab.binding.lutron.internal.net.TelnetSession;
import org.openhab.binding.lutron.internal.net.TelnetSessionListener;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler responsible for communicating with a HomeWorks processor over its Ethernet interface.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class HwIPBridgeHandler extends HwBridgeHandler {
    private static final String PROMPT_LOGIN = "LOGIN:";
    private static final String PROMPT_LNET = "LNET>";
    private static final String LOGIN_MATCH_REGEX = "(LOGIN:|login successful|LNET>)";

    private static final String DEFAULT_USER = "LutronGUI";
    private static final String DEFAULT_PASSWORD = "jetski";
    private static final int DEFAULT_RECONNECT_MINUTES = 5;
    private static final int DEFAULT_HEARTBEAT_MINUTES = 15;
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 30;

    private final Logger logger = LoggerFactory.getLogger(HwIPBridgeHandler.class);

    private HwIPBridgeConfig config;
    private int reconnectInterval;
    private int heartbeatInterval;
    private int sendDelay;

    private final TelnetSession session;
    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    private Thread messageSender;
    private ScheduledFuture<?> keepAlive;
    private ScheduledFuture<?> keepAliveReconnect;
    private ScheduledFuture<?> connectRetryJob;

    public HwIPBridgeHandler(Bridge bridge) {
        super(bridge);
        this.session = new TelnetSession();
    }

    public HwIPBridgeConfig getIPBridgeConfig() {
        return config;
    }

    @Override
    public void initialize() {
        this.config = getThing().getConfiguration().as(HwIPBridgeConfig.class);

        if (validConfiguration(this.config)) {
            logger.debug("Lutron HomeWorks IP Bridge Handler Initializing.");
            logger.debug("   IP Address: {}", config.getIpAddress());
            logger.debug("   Port:       {}", config.getPort());

            Integer reconnect = config.getReconnect();
            reconnectInterval = (reconnect != null && reconnect > 0) ? reconnect : DEFAULT_RECONNECT_MINUTES;

            Integer heartbeat = config.getHeartbeat();
            heartbeatInterval = (heartbeat != null && heartbeat > 0) ? heartbeat : DEFAULT_HEARTBEAT_MINUTES;

            Integer delay = config.getDelay();
            sendDelay = (delay != null && delay > 0) ? delay : 0;

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "Connecting");
            scheduler.submit(this::connect); // start the async connect task
        }
    }

    private boolean validConfiguration(HwIPBridgeConfig config) {
        if (config == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "bridge configuration missing");
            return false;
        }

        if (config.getIpAddress() == null || config.getIpAddress().isEmpty()) {
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
            return;
        }

        logger.debug("Connecting to bridge at {}:{}", config.getIpAddress(), config.getPort());

        try {
            if (!login(config)) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "invalid username/password");
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

        this.session.clearListeners();
        this.session.addListener(new TelnetSessionListener() {
            @Override
            public void inputAvailable() {
                parseUpdates();
            }

            @Override
            public void error(IOException exception) {
            }
        });

        sendMonitorCommands();
        requestInitialStatus();

        messageSender = new Thread(this::sendCommandsThread, "Lutron HomeWorks sender");
        messageSender.start();

        if (Boolean.TRUE.equals(config.getUpdateTime())) {
            startUpdateProcessorTimeJob();
        }

        updateStatus(ThingStatus.ONLINE);

        logger.debug("Starting keepAlive job with interval {}", heartbeatInterval);
        keepAlive = scheduler.scheduleWithFixedDelay(this::sendKeepAlive, heartbeatInterval, heartbeatInterval,
                TimeUnit.MINUTES);
    }

    private void sendCommandsThread() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = sendQueue.take();

                try {
                    session.writeLine(command);
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

    private boolean login(HwIPBridgeConfig config) throws IOException, InterruptedException {
        this.session.open(config.getIpAddress(), config.getPort());
        this.session.waitFor("(" + PROMPT_LOGIN + ")", 1000);

        String user = (config.getUser() != null) ? config.getUser() : DEFAULT_USER;
        String password = (config.getPassword() != null) ? config.getPassword() : DEFAULT_PASSWORD;
        this.session.writeLine(user + "," + password);

        MatchResult matchResult = this.session.waitFor(LOGIN_MATCH_REGEX, 1000);

        String matched;
        try {
            matched = matchResult.group();
        } catch (IllegalStateException e) {
            // No response within the timeout window — a communication hiccup, not a credentials
            // rejection. Route through the IOException path so connect() schedules a retry instead
            // of parking the bridge in CONFIGURATION_ERROR indefinitely.
            throw new IOException("Timed out waiting for login response from bridge");
        }

        return matched.contains(PROMPT_LNET) || matched.contains("login successful");
    }

    @Override
    public void sendCommand(String command) {
        this.sendQueue.add(command);
    }

    private void parseUpdates() {
        for (String line : this.session.readLines()) {
            if (line.trim().isEmpty()) {
                // Sometimes we get an empty line (possibly only when prompts are disabled). Ignore them.
                continue;
            }

            // System is alive, cancel reconnect task.
            if (this.keepAliveReconnect != null) {
                this.keepAliveReconnect.cancel(true);
            }

            handleIncomingMessage(line);
        }
    }

    private void sendKeepAlive() {
        logger.debug("Scheduling keepalive reconnect job");

        // Reconnect if no response is received within 30 seconds.
        keepAliveReconnect = scheduler.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        logger.trace("Sending keepalive query");
        sendCommand(HW_COMMAND_GETDATE);
    }

    @Override
    public void thingUpdated(Thing thing) {
        HwIPBridgeConfig newConfig = thing.getConfiguration().as(HwIPBridgeConfig.class);
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