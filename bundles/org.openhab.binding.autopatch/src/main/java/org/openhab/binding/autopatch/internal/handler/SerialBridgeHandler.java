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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SerialBridgeHandler} is responsible for communicating with the Autopatch
 * device through it's only serial port using fixed connection parameters of 9600/N/8/1
 *
 * Event driven serial reads are not possible due to an inability to close then re-open
 * the port.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class SerialBridgeHandler extends AutopatchBaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(SerialBridgeHandler.class);

    private final SerialPortManager portManager;
    private @Nullable SerialPortIdentifier portIdentifier;
    private @Nullable SerialPort serialPort;
    private String serialPortName = "";

    public SerialBridgeHandler(Bridge bridge, final @Reference SerialPortManager serialPortManager) {
        super(bridge);
        this.portManager = serialPortManager;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the Autopatch serial port handler");

        SerialBridgeConfig configuration = getConfigAs(SerialBridgeConfig.class);

        serialPortName = configuration.getSerialPort();
        reconnectInterval = configuration.getRefreshInterval();
        pollInterval = configuration.getPollInterval();

        if (serialPortName == "") {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial port not specified");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting");

        // start the async connect task
        scheduler.execute(() -> connect());
    }

    @Override
    protected synchronized void connect() {
        logger.debug("Autopatch RS232 Handler Connecting with Serial Port: {}.", serialPortName);

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
                super.connect();
            } catch (PortInUseException portInUseException) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Port in use: " + serialPortName);
            } catch (UnsupportedCommOperationException | IOException e) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
            }

        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid port: " + serialPortName);
        }
    }

    @Override
    public void disconnect() {
        logger.info("Autopatch serial port being closed.");

        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }

        super.disconnect();

        logger.debug("Finished closing and disposing serial port.");
    }

    @Override
    public void dispose() {
        this.disconnect();
        super.dispose();
    }

}
