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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
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
public class IPBridgeHandler extends AutopatchBaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(SerialBridgeHandler.class);

    // private Socket socket;

    private @Nullable InetAddress hostifAddress;
    private String hostipv4Address;

    private String ipAddress = "";
    private int port = 4999;

    private boolean deviceIsConnected = false;
    Socket socket = new Socket();

    private static final int SOCKET_CONNECT_TIMEOUT = 1500;

    public IPBridgeHandler(Bridge bridge, String hostipv4Address) {
        super(bridge);
        // scheduledFuture = null;
        this.hostipv4Address = hostipv4Address;
        deviceIsConnected = false;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the Autopatch IP port handler");

        IPBridgeConfig configuration = getConfigAs(IPBridgeConfig.class);

        ipAddress = configuration.getipAddress();
        port = configuration.getport();
        reconnectInterval = configuration.getRefreshInterval();
        pollInterval = configuration.getPollInterval();

        try {
            hostifAddress = InetAddress.getByName(hostipv4Address);
            NetworkInterface netIF = NetworkInterface.getByInetAddress(hostifAddress);
            logger.debug("Handler using address {} on network interface {}", hostifAddress.getHostAddress(),
                    netIF != null ? netIF.getName() : "UNKNOWN");
        } catch (SocketException e) {
            logger.error("Handler got Socket exception creating multicast socket: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No suitable network interface");
            return;
        } catch (UnknownHostException e) {
            logger.error("Handler got UnknownHostException getting local IPv4 network interface: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "No suitable network interface");
            return;
        }

        if (ipAddress.isEmpty()) {
            logger.debug("Could not get IP address from config");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP Address not specified");
            return;
        }

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting");

        // start the async connect task
        scheduler.execute(() -> connect());
    }

    @Override
    protected synchronized void connect() {
        logger.debug("Autopatch RS232 Handler Connecting with IP Port: {}.", ipAddress);

        if (deviceIsConnected) {
            return;
        }

        if (openSocket()) {
            // create streams
            try {
                dataInput = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                dataOutput = new OutputStreamWriter(socket.getOutputStream());
            } catch (IOException e) {
                logger.debug("Failed to get streams on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
                closeSocket();
                return;
            }
            logger.info("Got a connection on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
            updateStatus(ThingStatus.ONLINE);
            deviceIsConnected = true;
            super.connect();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid address: " + ipAddress);
        }

    }

    private boolean openSocket() {
        try {
            socket.bind(new InetSocketAddress(hostifAddress, 0));
            socket.connect(new InetSocketAddress(ipAddress, port), SOCKET_CONNECT_TIMEOUT);
        } catch (IOException e) {
            logger.debug("Failed to get socket on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
            return false;
        }
        return true;
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            logger.debug("Failed to close socket on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
        }
    }

    @Override
    public void disconnect() {
        logger.info("Autopatch serial port being closed.");

        if (!deviceIsConnected) {
            return;
        }

        closeSocket();

        deviceIsConnected = false;

        logger.debug("Finished closing and disposing serial port.");

        super.disconnect();
    }

    @Override
    public void dispose() {
        this.disconnect();
        super.dispose();
    }

}
