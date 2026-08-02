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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.config.AutopatchSerialBridgeConfig;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AutopatchSerialBridgeHandler} is responsible for communicating with the Autopatch
 * device through it's only serial port using fixed connection parameters of 9600/N/8/1
 *
 * Event driven serial reads are not possible due to an inability to close then re-open
 * the port.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class AutopatchSerialBridgeHandler extends AutopatchBaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(AutopatchSerialBridgeHandler.class);

    AutopatchSerialBridgeConfig configuration = new AutopatchSerialBridgeConfig();

    private final SerialPortManager portManager;
    private @Nullable SerialPortIdentifier portIdentifier;
    private @Nullable SerialPort serialPort;
    private String serialPortName = "";
    private int pollInterval;
    private ThingRegistry thingRegistry;

    // reader thread is for polling of the serial port
    // recurrent and at fixed specified intervals
    // have to poll; cannot just attach a listener because the serial libraries don't work properly with it
    protected @Nullable ScheduledFuture<?> reader;

    private boolean deviceIsConnected = false;

    public AutopatchSerialBridgeHandler(Bridge bridge, final @Reference SerialPortManager serialPortManager,
            ThingRegistry thingRegistry) {
        super(bridge);
        this.portManager = serialPortManager;
        this.thingRegistry = thingRegistry;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the Autopatch serial port handler");

        configuration = getConfigAs(AutopatchSerialBridgeConfig.class);

        serialPortName = configuration.getSerialPort();
        reconnectInterval = configuration.getRefreshInterval();
        pollInterval = configuration.getPollInterval();
        sendDelay = configuration.getSendDelay();

        if (validConfiguration(configuration)) {
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting");

            // start the async connect task
            scheduler.execute(() -> connect());
        }

    }

    private boolean validConfiguration(AutopatchSerialBridgeConfig config) {
        if (isDuplicateBridge(config)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Duplicate bridge.");
            return false;
        }

        if (serialPortName == "") {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial port not specified");
            return false;
        }
        return true;
    }

    @Override
    protected synchronized void connect() {
        logger.debug("Autopatch RS232 Handler Connecting with Serial Port: {}.", serialPortName);

        if (deviceIsConnected) {
            logger.trace("Device already connected; ignoring repeat connection");
            return;
        }

        portIdentifier = portManager.getIdentifier(serialPortName);
        if (portIdentifier != null) {
            try {
                this.serialPort = portIdentifier.open(this.getClass().getName(), 2000);
                logger.debug("Connection established using {}.  Configuring IO parameters. ", serialPortName);

                Objects.requireNonNull(serialPort);
                serialPort.setSerialPortParams(9600, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                        SerialPort.PARITY_NONE);
                Objects.requireNonNull(serialPort);
                dataOutput = new OutputStreamWriter(serialPort.getOutputStream(), "US-ASCII");
                Objects.requireNonNull(serialPort);
                dataInput = new BufferedReader(new InputStreamReader(serialPort.getInputStream(), "US-ASCII"));

                updateStatus(ThingStatus.ONLINE);
                logger.debug("Starting data poll job with interval {} seconds", pollInterval);
                reader = scheduler.scheduleWithFixedDelay(this::checkData, pollInterval, pollInterval,
                        TimeUnit.SECONDS);
                deviceIsConnected = true;
                super.connect();
            } catch (PortInUseException portInUseException) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Port in use: " + serialPortName);
                disconnect();
                scheduleConnectRetry(reconnectInterval); // Possibly a temporary problem. Try again later.
            } catch (UnsupportedCommOperationException | IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
                disconnect();
                scheduleConnectRetry(reconnectInterval); // Possibly a temporary problem. Try again later.
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid port: " + serialPortName);
        }
    }

    protected void checkData() {
        logger.trace("Poll: checking for data from port.");
        try {

            String data = getData();
            if (data != "") {
                // System is connected in some way, cancel reconnect task.
                if (this.keepAliveReconnectJob != null) {
                    this.keepAliveReconnectJob.cancel(true);
                }
                handleIncomingMessage(data);
            }

        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error reading from port");
            scheduleConnectRetry(reconnectInterval);
            this.disconnect();
        } catch (InterruptedException e) {

        }
    }

    @Override
    public void disconnect() {
        logger.info("Autopatch serial port being closed.");

        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }

        if (this.reader != null) {
            this.reader.cancel(true);
        }

        deviceIsConnected = false;

        logger.debug("Finished closing serial port.");

        super.disconnect();
    }

    @Override
    protected synchronized void reconnect() {
        logger.debug("Keepalive timeout, attempting to reconnect to the bridge");

        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE);
        disconnect();
        connect();
    }

    @Override
    public void dispose() {
        this.disconnect();
        super.dispose();
    }

    private boolean isDuplicateBridge(AutopatchSerialBridgeConfig config) {
        return thingRegistry.getAll().stream().map(Thing::getHandler)
                .filter(AutopatchSerialBridgeHandler.class::isInstance).map(AutopatchSerialBridgeHandler.class::cast)
                .filter(handler -> !this.equals(handler)).map(AutopatchSerialBridgeHandler::getBridgeConfig)
                .map(AutopatchSerialBridgeConfig.class::cast).anyMatch(conf -> config.sameConnectionParameters(conf));
    }

    private void scheduleConnectRetry(long waitMinutes) {
        if (connectRetryJob != null) {
            connectRetryJob.cancel(true);
        }
        logger.debug("Scheduling connection retry in {} minutes", waitMinutes);
        connectRetryJob = scheduler.schedule(this::connect, waitMinutes, TimeUnit.MINUTES);
    }

}
