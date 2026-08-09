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

    // private boolean deviceIsConnected = false;

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

        commonInitialize(configuration.getRefreshInterval(), configuration.getSendDelay(),
                configuration.getDeviceType());
    }

    @Override
    protected boolean validConfiguration() {
        if (isDuplicateBridge(configuration)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "Duplicate bridge.");
            return false;
        }

        if (hostipv4Address == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No host IP address.");
            return false;
        }

        if (configuration.getipAddress().isEmpty()) {
            logger.debug("Could not get IP address from config");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP Address not specified");
            return false;
        }
        return true;
    }

    @Override
    protected void getHostInterface() {
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
        logger.info("Autopatch IP Handler {} Connecting to address: {} with port {}.", System.identityHashCode(this),
                ipAddress, port);

        if (isDisposed) {
            logger.trace("Thing is disposed; ignoring connection attempt");
            return;
        }

        if (isConnected) {
            logger.debug("Device already connected; ignoring repeat connection");
            return;
        }

        if (openSocket()) {
            isConnected = true;
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

            ipPortReader = new IPPortReader();
            ipPortReader.start();

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
            if (!isDisposed) {
                scheduleConnectRetry(reconnectInterval);
            }
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
    public synchronized void disconnect() {
        if (!isConnected) {
            logger.trace("Already disconnected; ignoring redundant disconnect() call");
            return;
        }
        isConnected = false;

        logger.info("Autopatch IP --> IP port disconnecting and being closed.");

        if (ipPortReader != null) {
            ipPortReader.stop();
        }

        closeSocket();

        logger.debug("Finished closing port.");

        super.disconnect();
    }

    @Override
    public synchronized void dispose() {
        logger.trace("Disposing IPBridgeHandler {}", System.identityHashCode(this));
        isDisposed = true;
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
        logger.info("Scheduling connection retry in {} minutes", waitMinutes);
        connectRetryJob = scheduledExecutorService.schedule(this::connect, waitMinutes, TimeUnit.MINUTES);
    }

    @Override
    public void thingUpdated(Thing thing) {
        AutopatchIPBridgeConfig newConfig = getConfigAs(AutopatchIPBridgeConfig.class);
        AutopatchIPBridgeConfig oldConfig = this.configuration;

        this.configuration = newConfig;

        boolean validConfig = validConfiguration();
        boolean needsReconnect = validConfig && !oldConfig.sameConnectionParameters(newConfig);

        if (!validConfig || needsReconnect) {
            dispose();
        }

        this.thing = thing;

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

                    String data = getData();
                    if (!data.isEmpty()) {
                        // System is connected in some way, cancel reconnect task.
                        if (keepAliveReconnectJob != null) {
                            keepAliveReconnectJob.cancel(true);
                        }
                        handleIncomingMessage(data);
                    }

                } catch (IOException e) {
                    logger.trace("IP reader caught IOException: {}", e.toString());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error reading from port");
                    disconnect();
                    if (!isDisposed) {
                        scheduleConnectRetry(reconnectInterval);
                    }
                    break;
                } catch (InterruptedException e) {
                    terminatePortReader = true;
                }
            }
            logger.debug("IP reader STOPPING for {} on {}:{}", thingID(), ipAddress, port);
        }
    }
}
