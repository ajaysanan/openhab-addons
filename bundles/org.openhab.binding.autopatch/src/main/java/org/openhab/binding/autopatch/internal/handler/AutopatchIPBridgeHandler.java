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
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.config.AutopatchIPBridgeConfig;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AutopatchIPBridgeHandler} is responsible for communicating with the Autopatch
 * device through it's only serial port using fixed connection parameters of 9600/N/8/1
 *
 * Event driven serial reads are not possible due to an inability to close then re-open
 * the port.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class AutopatchIPBridgeHandler extends AutopatchBaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(AutopatchIPBridgeHandler.class);

    private AutopatchIPBridgeConfig configuration = new AutopatchIPBridgeConfig();

    private @Nullable InetAddress hostifAddress;
    private @Nullable String hostipv4Address;

    private static final int SOCKET_CONNECT_TIMEOUT = 1500;
    private Socket socket = new Socket();
    private String ipAddress = "";
    private int port = 4999;
    private ThingRegistry thingRegistry;

    private @Nullable IPPortReader ipPortReader;

    private boolean deviceIsConnected = false;

    public AutopatchIPBridgeHandler(Bridge bridge, @Nullable String hostipv4Address, ThingRegistry thingRegistry) {
        super(bridge);
        this.thingRegistry = thingRegistry;
        this.hostipv4Address = hostipv4Address;
    }

    @Override
    public void initialize() {
        logger.debug("Initializing the Autopatch IP port handler");

        this.configuration = getConfigAs(AutopatchIPBridgeConfig.class);

        ipAddress = configuration.getipAddress();
        port = configuration.getport();
        reconnectInterval = configuration.getRefreshInterval();
        sendDelay = configuration.getSendDelay();

        if (validConfiguration(configuration)) {
            getHostInterface();
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting");

            // start the async connect task
            scheduler.execute(() -> connect());
        }
    }

    private boolean validConfiguration(AutopatchIPBridgeConfig config) {
        if (isDuplicateBridge(config)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Duplicate bridge.");
            return false;
        }

        if (hostipv4Address == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No host IP address.");
            return false;
        }

        if (ipAddress.isEmpty()) {
            logger.debug("Could not get IP address from config");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP Address not specified");
            return false;
        }
        return true;
    }

    private void getHostInterface() {
        try {
            hostifAddress = InetAddress.getByName(hostipv4Address);
            NetworkInterface netIF = NetworkInterface.getByInetAddress(hostifAddress);
            String hostIF = "unknown";
            if (hostifAddress != null) {
                hostIF = hostifAddress.getHostAddress();
            }
            if (hostIF != null) {
                logger.debug("Handler using address {} on network interface {}", hostIF,
                        netIF != null ? netIF.getName() : "UNKNOWN");
            }
        } catch (SocketException e) {
            logger.error("Handler got Socket exception creating multicast socket: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No suitable network interface");
            return;
        } catch (UnknownHostException e) {
            logger.error("Handler got UnknownHostException getting local IPv4 network interface: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "No suitable network interface");
            return;
        }
    }

    @Override
    protected synchronized void connect() {
        logger.info("Autopatch IP Handler Connecting to address: {} with port {}.", ipAddress, port);

        if (deviceIsConnected) {
            logger.debug("Device already connected; ignoring repeat connection");
            return;
        }

        if (openSocket()) {
            // create streams
            try {
                dataInput = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
                dataOutput = new OutputStreamWriter(socket.getOutputStream());
            } catch (IOException e) {
                logger.error("Failed to get streams on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
                disconnect();
                scheduleConnectRetry(reconnectInterval); // Possibly a temporary problem. Try again later.
                return;
            }
            logger.info("Got a connection on port {} for thing {} at {}", port, thing.getUID(), ipAddress);
            updateStatus(ThingStatus.ONLINE);

            ipPortReader = new IPPortReader();
            ipPortReader.start();

            deviceIsConnected = true;
            super.connect();
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Invalid address: " + ipAddress);
        }

    }

    private boolean openSocket() {
        try {
            if (socket.isClosed()) {
                socket = new Socket();
            }
            socket.bind(new InetSocketAddress(hostifAddress, 0));
            socket.connect(new InetSocketAddress(ipAddress, port), SOCKET_CONNECT_TIMEOUT);
        } catch (IOException e) {
            logger.error("Failed to get socket on port {} for thing {} at {}: {}", port, thing.getUID(), ipAddress);
            disconnect();
            scheduleConnectRetry(reconnectInterval); // Possibly a temporary problem. Try again later.
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
        logger.info("Autopatch IP --> serial port disconnecting and being closed.");

        if (!deviceIsConnected) {
            return;
        }

        if (ipPortReader != null) {
            ipPortReader.stop();
        }

        closeSocket();

        deviceIsConnected = false;

        logger.debug("Finished closing port.");

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

    private boolean isDuplicateBridge(AutopatchIPBridgeConfig config) {
        return thingRegistry.getAll().stream().map(Thing::getHandler).filter(AutopatchIPBridgeHandler.class::isInstance)
                .map(AutopatchIPBridgeHandler.class::cast).filter(handler -> !this.equals(handler))
                .map(AutopatchIPBridgeHandler::getBridgeConfig).map(AutopatchIPBridgeConfig.class::cast)
                .anyMatch(conf -> config.sameConnectionParameters(conf));
    }

    private void scheduleConnectRetry(long waitMinutes) {
        if (connectRetryJob != null) {
            connectRetryJob.cancel(true);
        }
        logger.debug("Scheduling connection retry in {} minutes", waitMinutes);
        connectRetryJob = scheduledExecutorService.schedule(this::connect, waitMinutes, TimeUnit.MINUTES);
    }

    @Override
    public void thingUpdated(Thing thing) {
        AutopatchIPBridgeConfig newConfig = getConfigAs(AutopatchIPBridgeConfig.class);
        boolean validConfig = validConfiguration(newConfig);
        boolean needsReconnect = validConfig && !this.configuration.sameConnectionParameters(newConfig);

        if (!validConfig || needsReconnect) {
            dispose();
        }

        this.thing = thing;
        this.configuration = newConfig;

        if (needsReconnect) {
            initialize();
        }
    }

    private class IPPortReader {
        /*
         * The {@link SerialReader} class reads data from the serial connection. When data is
         * received, the receive channel is updated with the data. Data is read up to the
         * end-of-message delimiter defined in the Thing configuration.
         *
         * @author Mark Hilbush - Initial contribution
         */

        private Logger logger = LoggerFactory.getLogger(IPPortReader.class);

        private @Nullable Thread portReaderJob;
        private boolean terminatePortReader;

        IPPortReader() {
            portReaderJob = null;
            terminatePortReader = false;
        }

        public void start() {
            portReaderJob = new Thread(this::ipPortReader, "Autopatch IP Port Reader");
            portReaderJob.start();
        }

        public void stop() {
            if (portReaderJob != null) {
                portReaderJob.interrupt();
                portReaderJob = null;
                terminatePortReader = true;
            }
        }

        private void ipPortReader() {
            logger.info("IP Port reader RUNNING for {}", thingID());

            while (!terminatePortReader) {
                try {

                    for (String data : getData()) {
                        if (data != "") {
                            // System is connected in some way, cancel reconnect task.
                            if (keepAliveReconnectJob != null) {
                                keepAliveReconnectJob.cancel(true);
                            }
                            handleIncomingMessage(data);
                        }
                    }

                } catch (IOException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error reading from port");
                    scheduleConnectRetry(reconnectInterval);
                    disconnect();
                    break;
                } catch (InterruptedException e) {
                    terminatePortReader = true;
                }
            }
            logger.debug("Serial reader STOPPING for {} on {}:{}", thingID(), ipAddress, port);
        }
    }
}
