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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.TooManyListenersException;

import org.openhab.core.io.transport.serial.PortInUseException;
import org.openhab.core.io.transport.serial.SerialPort;
import org.openhab.core.io.transport.serial.SerialPortEvent;
import org.openhab.core.io.transport.serial.SerialPortEventListener;
import org.openhab.core.io.transport.serial.SerialPortIdentifier;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.io.transport.serial.UnsupportedCommOperationException;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is the main handler for HomeWorks RS232 Processors.
 *
 * @author Andrew Shilliday - Initial contribution
 * @author Ajay Sanan - Refactored onto the shared HwBridgeHandler base
 */
public class HwSerialBridgeHandler extends HwBridgeHandler implements SerialPortEventListener {
    private final Logger logger = LoggerFactory.getLogger(HwSerialBridgeHandler.class);

    private String serialPortName;
    private int baudRate;
    private Boolean updateTime;

    private final SerialPortManager serialPortManager;
    private SerialPort serialPort;
    private OutputStreamWriter serialOutput;
    private BufferedReader serialInput;

    public HwSerialBridgeHandler(Bridge bridge, SerialPortManager serialPortManager) {
        super(bridge);
        this.serialPortManager = serialPortManager;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the Lutron HomeWorks RS232 bridge handler");
        HwSerialBridgeConfig configuration = getConfigAs(HwSerialBridgeConfig.class);
        serialPortName = configuration.getSerialPort();
        updateTime = configuration.getUpdateTime();
        if (configuration.getBaudRate() == null) {
            baudRate = HwSerialBridgeConfig.DEFAULT_BAUD;
        } else {
            baudRate = configuration.getBaudRate().intValue();
        }

        if (serialPortName == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Serial port not specified");
            return;
        }

        logger.debug("Lutron HomeWorks RS232 Bridge Handler Initializing.");
        logger.debug("   Serial Port: {},", serialPortName);
        logger.debug("   Baud:        {},", baudRate);

        scheduler.execute(() -> openConnection());
    }

    private void openConnection() {
        SerialPortIdentifier portIdentifier = serialPortManager.getIdentifier(serialPortName);
        if (portIdentifier == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid port: " + serialPortName);
            return;
        }

        try {
            logger.info("Connecting to Lutron HomeWorks Processor using {}.", serialPortName);
            serialPort = portIdentifier.open(this.getClass().getName(), 2000);

            logger.debug("Connection established using {}.  Configuring IO parameters. ", serialPortName);

            int db = SerialPort.DATABITS_8, sb = SerialPort.STOPBITS_1, p = SerialPort.PARITY_NONE;
            serialPort.setSerialPortParams(baudRate, db, sb, p);
            serialPort.enableReceiveThreshold(1);
            serialPort.disableReceiveTimeout();
            serialOutput = new OutputStreamWriter(serialPort.getOutputStream(), "US-ASCII");
            serialInput = new BufferedReader(new InputStreamReader(serialPort.getInputStream(), "US-ASCII"));

            serialPort.addEventListener(this);
            serialPort.notifyOnDataAvailable(true);

            sendMonitorCommands();
            requestInitialStatus();

            updateStatus(ThingStatus.ONLINE);

            if (updateTime) {
                startUpdateProcessorTimeJob();
            }
        } catch (PortInUseException portInUseException) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Port in use: " + serialPortName);
        } catch (UnsupportedCommOperationException | IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
        } catch (TooManyListenersException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Too many listeners to serial port.");
        }
    }

    /**
     * Receives Serial Port Events and reads Serial Port Data.
     *
     * @param serialPortEvent
     */
    @Override
    public void serialEvent(SerialPortEvent serialPortEvent) {
        if (serialPortEvent.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
            try {
                while (true) {
                    String messageLine = serialInput.readLine();
                    if (messageLine == null) {
                        break;
                    }
                    handleIncomingMessage(messageLine);
                }
            } catch (IOException e) {
                logger.debug("Error reading from serial port: {}", e.getMessage(), e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error reading from port");
            }
        }
    }

    @Override
    public void sendCommand(String command) {
        try {
            logger.debug("HomeWorks bridge sending command: {}", command);
            serialOutput.write(command + "\r");
            serialOutput.flush();
        } catch (IOException e) {
            logger.debug("Error writing to serial port: {}", e.getMessage(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error writing to port.");
        }
    }

    @Override
    public void dispose() {
        logger.info("HomeWorks bridge being disposed.");
        if (serialPort != null) {
            serialPort.close();
        }
        serialPort = null;
        serialInput = null;
        serialOutput = null;

        super.dispose();

        logger.debug("Finished disposing bridge.");
    }
}