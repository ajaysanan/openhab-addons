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

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.*;
import static org.openhab.binding.autopatch.internal.command.BCSConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.binding.autopatch.internal.command.BCSDecode;
import org.openhab.binding.autopatch.internal.config.AutopatchBaseBridgeConfig;
import org.openhab.binding.autopatch.internal.config.AutopatchIPBridgeConfig;
import org.openhab.binding.autopatch.internal.config.AutopatchSerialBridgeConfig;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class extends the BaseBridgeHandler to support Autopatch connections.
 *
 * @author Ajay Sanan - Initial contribution
 *
 */
public abstract class AutopatchBaseBridgeHandler extends BaseBridgeHandler {
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 30;
    private static final String TEST_COMMAND = "SO1T";

    private final Logger logger = LoggerFactory.getLogger(AutopatchBaseBridgeHandler.class);

    public boolean isConnected = false;
    public int numInputZones = 18;
    public int numOutputZones = 18;

    protected int reconnectInterval;

    protected ScheduledExecutorService scheduledExecutorService = ThreadPoolManager
            .getScheduledPool("autopatchHandler-" + thingID());

    // keepAliveJob thread is for periodic checks on the connection status
    protected @Nullable ScheduledFuture<?> keepAliveJob;

    // keepAliveReconnectJob is a single fired thread to reconnect if the keepAlive fails
    protected @Nullable ScheduledFuture<?> keepAliveReconnectJob;

    // connectRetryJob thread is for periodic reconnection attempts if the connection fails
    protected @Nullable ScheduledFuture<?> connectRetryJob;

    // messageSenderJob is the continuous thread that sends commands from the sendQueue
    private @Nullable Thread messageSenderThread;

    protected OutputStreamWriter dataOutput;
    protected @Nullable BufferedReader dataInput;

    private BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    protected int sendDelay;

    public AutopatchBaseBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    protected void connect() {
        messageSenderThread = new Thread(this::sendCommandThread, "Autopatch sender");
        messageSenderThread.start();

        @NonNull
        Map<@NonNull String, @NonNull String> props = this.editProperties();
        props.putIfAbsent("Connection Date", LocalDate.now().toString());
        String connects = props.putIfAbsent("Connection Attempts", "1");
        if (connects != null) {
            Integer newconn = Integer.parseInt(connects) + 1;
            props.put("Connection Attempts", newconn.toString());
        }
        this.updateProperties(props);

        logger.debug("Queuing diagnostics command (~scr).");
        sendCommand("~scr!");

        logger.debug("Starting keepAlive job with interval {} minutes", reconnectInterval);
        keepAliveJob = scheduledExecutorService.scheduleWithFixedDelay(this::sendKeepAlive, reconnectInterval,
                reconnectInterval, TimeUnit.MINUTES);
    }

    protected void sendKeepAlive() {
        logger.trace("Scheduling keepalive reconnect job for {} seconds", KEEPALIVE_TIMEOUT_SECONDS);
        // Reconnect if no response is received within specified seconds.
        keepAliveReconnectJob = scheduledExecutorService.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        sendCommand(TEST_COMMAND);
    }

    public void sendCommand(String command) {
        this.sendQueue.add(command);
    }

    /**
     * Write Port Data.
     *
     * @param command
     */
    public void sendCommandThread() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                String command = sendQueue.take();
                logger.debug("Autopatch sending command-->{}<--", command);
                try {
                    if (dataOutput != null) {
                        dataOutput.write(command);
                        dataOutput.flush();
                    } else {
                        logger.debug("Autopatch offline: ignoring command-->{}<--", command);
                        throw new IOException("Output Stream is Closed");
                    }
                } catch (IOException e) {
                    logger.warn("Communication error, will try to reconnect. Error: {}", e.getMessage());
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
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

    public synchronized ArrayList<String> getData() throws IOException, InterruptedException {
        final BufferedReader dataInput = this.dataInput;

        char readchar;
        int readint = 0;
        ArrayList<String> messages = new ArrayList<>();
        StringBuilder messageLine = new StringBuilder();

        // Autopatch does not consistently terminate all responses with cr, lf or crlf so readline doesn't work
        // Read data until crlf or ")" or "X" is received
        // Read multiple lines of data if there are multiple terminating characters found.

        if (dataInput != null) {
            while (dataInput.ready() && (readint = dataInput.read()) != -1) {
                switch (readchar = (char) readint) {
                    case '\r':
                        // logger.trace("Ignoring cr from Autopatch");
                        break;
                    case '\n':
                        if (messageLine.length() > 0) {
                            logger.debug("Received message (lf terminated) from Autopatch (information) -->{}<--",
                                    messageLine.toString());
                            messageLine.setLength(0);
                        } else {
                            // logger.trace("Ignoring lf from Autopatch -->{}<--", readchar);
                        }
                        break;
                    case 'X': // General error
                    case '?': // Message Format error
                    case ')':
                        messageLine.append(readchar);
                        // logger.trace("Appending message ('X' or ')' terminated) from Autopatch -->{}<--",
                        // messageLine.toString());
                        messages.add(messageLine.toString());
                        messageLine.setLength(0);
                        break;
                    default:
                        // logger.trace("Appending raw data from Autopatch char: -->{}<-- Message: -->{}<--", readchar,
                        // messageLine.toString());
                        messageLine.append(readchar);
                        break;
                }
                Thread.sleep(3); // slow it down a little due to the slow baud rate
            }
        }
        return messages;
    }

    protected void handleIncomingMessage(final String line) {
        // Send response to BCSDecode class to decode
        BCSDecode bcs = new BCSDecode(line);
        if (!bcs.error) {
            logger.debug("Received message from Autopatch (valid) -->{}<--", line);
            // Handle changes for each zone, if multiple
            for (int zoneindex = 0; zoneindex < bcs.zones.size(); zoneindex++) {
                AutopatchBaseZoneHandler handler = findHandler(bcs.zonetype, bcs.zones.get(zoneindex), bcs.level);
                if (handler != null) {
                    handler.handleStateChange(bcs, zoneindex);
                }
            }
        } else {
            logger.debug("Received message from Autopatch (ignore) -->{}<--", line);
        }
    }

    @Nullable
    private AutopatchBaseZoneHandler findHandler(ZoneType zonetype, Integer zone, int level) {
        return getThing().getThings().stream().filter(Thing::isEnabled).map(Thing::getHandler)
                .filter(AutopatchBaseZoneHandler.class::isInstance).map(AutopatchBaseZoneHandler.class::cast)
                .filter(handler -> zonetype.equals(handler.zoneType)
                        && (zone.equals(handler.zoneNumber) && (handler.zoneLevel.equals(level))))
                .findFirst().orElse(null);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Handling command {} for channel {}", command, channelUID);

        Channel channel = getThing().getChannel(channelUID.getId());
        String channelId = channelUID.getId();

        if (channel == null) {
            logger.warn("Received invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }
        if (isLinked(channelUID)) {
            switch (channelId) {
                case CHANNEL_COMMAND:
                    if (command.toString().equals("REBOOT")) {
                        sendCommand("~app!");
                    }
                    break;
                case CHANNEL_CREATEGLOBALPRESET:
                    sendCommand(EXECUTE_GLOBAL_PRESET + command.toString() + EXECUTE);
                    break;
                case CHANNEL_EXECUTEGLOBALPRESET:
                    sendCommand(DEFINE_GLOBAL_PRESET + command.toString() + EXECUTE);
                    break;
            }
        }

        if (CHANNEL_COMMAND.equals(channelUID.getId()) && command.toString().equals("REBOOT")) {
            sendCommand("~app!");
        }
    }

    protected void disconnect() {
        try {
            logger.trace("Disconnecting from Autopatch device.");

            if (dataInput != null) {
                dataInput.close();
            }

            if (dataOutput != null) {
                dataOutput.close();
            }

            if (messageSenderThread != null) {
                messageSenderThread.interrupt();
            }

            if (this.keepAliveJob != null) {
                this.keepAliveJob.cancel(true);
            }

            if (connectRetryJob != null) {
                connectRetryJob.cancel(true);
            }

            if (this.keepAliveReconnectJob != null) {
                // This method can be called from the keepAliveReconnect thread. Make sure
                // we don't interrupt ourselves, as that may prevent the reconnection attempt.
                this.keepAliveReconnectJob.cancel(false);
            }

        } catch (IOException e) {
            logger.debug("Error closing reader/writer/port: {}", e.getMessage(), e);
        }

        dataInput = null;
        dataOutput = null;

    }

    @Override
    public void dispose() {
        disconnect();
        scheduledExecutorService.shutdown();

        try {
            if (!scheduledExecutorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                scheduledExecutorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutorService.shutdownNow();
        }

        super.dispose();
    }

    protected AutopatchBaseBridgeConfig getBridgeConfig() {
        ThingTypeUID thingTypeUID = getThing().getThingTypeUID();
        if (THING_TYPE_IPBRIDGE.equals(thingTypeUID)) {
            return getConfigAs(AutopatchIPBridgeConfig.class);
        } else if (THING_TYPE_SERIALBRIDGE.equals(thingTypeUID)) {
            return getConfigAs(AutopatchSerialBridgeConfig.class);
        } else {
            throw new UnsupportedOperationException("Unsupported bridge configuration");
        }
    }

    protected String thingID() {
        // Return segments 2 & 3 only
        String s = thing.getUID().getAsString();
        return s.substring(s.indexOf(':') + 1);
    }

    protected abstract void reconnect();

}
