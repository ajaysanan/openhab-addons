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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.command.BCSConstants.CommandType;
import org.openhab.binding.autopatch.internal.command.BCSConstants.ZoneType;
import org.openhab.binding.autopatch.internal.command.BCSDecode;
import org.openhab.binding.autopatch.internal.config.AutopatchBaseBridgeConfig;
import org.openhab.binding.autopatch.internal.config.AutopatchIPBridgeConfig;
import org.openhab.binding.autopatch.internal.config.AutopatchSerialBridgeConfig;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
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
    private static final Pattern ZONE_PATTERN = Pattern.compile("(\\d+)x(\\d+)$");
    private static final Pattern VM_DIM_PATTERN = Pattern.compile("\\[(\\d+)x(\\d+)x\\d+\\]");
    private static final Pattern BUILD_PATTERN = Pattern.compile("Built on\\s*(.+)");
    private static final Pattern HOST_PATTERN = Pattern.compile("Host software:\\s*(\\S+)");
    private static final Pattern HW_PATTERN = Pattern.compile("Hardware driver:\\s*(.+)");

    private final Logger logger = LoggerFactory.getLogger(AutopatchBaseBridgeHandler.class);

    public boolean isConnected = false;
    public int numInputZones = 0;
    public int numOutputZones = 0;
    public boolean dspCapable = false;

    protected int reconnectInterval;
    protected String deviceType;
    protected int sendDelay;

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
    protected @Nullable volatile BufferedReader dataInput;

    private BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    protected volatile boolean isDisposed = false;

    public AutopatchBaseBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    protected void commonInitialize(int refreshInterval, int sendDelay, String deviceType) {
        isDisposed = false;

        this.reconnectInterval = refreshInterval;
        this.sendDelay = sendDelay;
        this.deviceType = deviceType;

        if (!"autodetect".equals(deviceType)) {
            Matcher zoneMatcher = ZONE_PATTERN.matcher(deviceType);

            if (zoneMatcher.find()) {
                this.numInputZones = Integer.parseInt(zoneMatcher.group(1));
                this.numOutputZones = Integer.parseInt(zoneMatcher.group(2));

                Map<@NonNull String, @NonNull String> props = editProperties();
                props.put("Signal Router", deviceType);
                props.put("Number of Input Zones", zoneMatcher.group(1));
                props.put("Number of Output Zones", zoneMatcher.group(2));
                updateProperties(props);
                this.dspCapable = DSP_CAPABLE_ROUTERS.contains(deviceType);
                notifyZonesOfConfiguration();
            } else {
                logger.warn("deviceType '{}' does not end in IxO - cannot determine zone counts", deviceType);
            }
        }

        if (validConfiguration()) {
            getHostInterface();
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Connecting");

            logger.info("Starting the async connect task");
            scheduler.execute(this::connect);
        }
    }

    protected void connect() {
        messageSenderThread = new Thread(this::sendCommandThread, "Autopatch sender");
        messageSenderThread.start();

        Map<@NonNull String, @NonNull String> props = this.editProperties();
        props.putIfAbsent("Signal Router", "");
        props.putIfAbsent("Connection Date", LocalDate.now().toString());
        String connects = props.putIfAbsent("Connection Attempts", "1");
        if (connects != null) {
            Integer newconn = Integer.parseInt(connects) + 1;
            props.put("Connection Attempts", newconn.toString());
        }
        this.updateProperties(props);

        logger.debug("Sending test query and Queuing diagnostics command ({}).", TEST_COMMAND);
        sendCommand(TEST_COMMAND);

    }

    protected void sendKeepAlive() {
        if (!isDisposed) {
            logger.trace("Scheduling keepalive reconnect job for {} seconds ({})", KEEPALIVE_TIMEOUT_SECONDS,
                    System.identityHashCode(this));
            // Reconnect if no response is received within specified seconds.
            keepAliveReconnectJob = scheduledExecutorService.schedule(this::reconnect, KEEPALIVE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            sendCommand(TEST_COMMAND);
        }
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

    public String getData() throws IOException, InterruptedException {
        logger.trace("IP reader waiting for available data");
        final BufferedReader dataInput = this.dataInput;

        char readchar;
        int readint = 0;
        boolean noMessage = true;
        String message = new String();
        StringBuilder messageLine = new StringBuilder();

        // Autopatch does not consistently terminate all responses with cr, lf or crlf so readline doesn't work
        // Read data until crlf or ")" or "X" is received
        // Read multiple lines of data if there are multiple terminating characters found.

        if (dataInput != null) {
            while (noMessage) {
                readint = dataInput.read();
                if (readint == -1) {
                    logger.debug("IP reader got unexpected end of input stream");
                    throw new IOException("Unexpected end of stream");
                }
                switch (readchar = (char) readint) {
                    case '\r':
                        break;
                    case '\n':
                        if (messageLine.length() > 0) {
                            logger.debug("Received message (lf terminated) from Autopatch (information) -->{}<--",
                                    messageLine.toString());
                            processInformationMessage(messageLine.toString());
                            messageLine.setLength(0);
                        }
                        break;
                    case 'X': // General error
                    case '?': // Message Format error
                    case ')':
                        messageLine.append(readchar);
                        message = messageLine.toString();
                        noMessage = false;
                        break;
                    default:
                        messageLine.append(readchar);
                        break;
                }
                Thread.sleep(5); // slow it down a little due to the slow baud rate
            }
        }
        return message;
    }

    protected void processInformationMessage(String line) {
        Map<@NonNull String, @NonNull String> props = editProperties();
        boolean autodetect = "autodetect".equals(this.deviceType);
        boolean changed = false;

        if (autodetect) {
            int sigRouterIdx = line.toLowerCase().indexOf("signal router");
            if (sigRouterIdx >= 0) {
                String signalRouter = line.substring(0, sigRouterIdx).trim();
                props.put("Signal Router", signalRouter);
                this.dspCapable = signalRouter.toUpperCase().contains("DSP");
                changed = true;
            }

            Matcher vmDimMatcher = VM_DIM_PATTERN.matcher(line);
            if (vmDimMatcher.find()) {
                this.numInputZones = Integer.parseInt(vmDimMatcher.group(1));
                this.numOutputZones = Integer.parseInt(vmDimMatcher.group(2));
                props.put("Number of Input Zones", vmDimMatcher.group(1));
                props.put("Number of Output Zones", vmDimMatcher.group(2));
                notifyZonesOfConfiguration();
                changed = true;
            }
        }

        Matcher buildMatcher = BUILD_PATTERN.matcher(line);
        if (buildMatcher.find()) {
            props.put("Build Date", buildMatcher.group(1).trim());
            changed = true;
        }

        Matcher hostMatcher = HOST_PATTERN.matcher(line);
        if (hostMatcher.find()) {
            props.put("Software Version", hostMatcher.group(1).trim());
            changed = true;
        }

        Matcher hwMatcher = HW_PATTERN.matcher(line);
        if (hwMatcher.find()) {
            props.put("Hardware Version", hwMatcher.group(1).trim());
            changed = true;
        }

        if (changed) {
            updateProperties(props);
        }
    }

    protected void handleIncomingMessage(final String line) {
        // Send response to BCSDecode class to decode
        BCSDecode bcs = new BCSDecode(line);
        if (!bcs.error) {
            logger.debug("Received message from Autopatch (valid) -->{}<--", line);
            if (getThing().getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
                logger.debug("Queuing diagnostics command (~scr).");
                sendCommand("~scr!");

                if (!isDisposed && keepAliveJob == null) {
                    logger.debug("Starting keepAlive job with interval {} minutes", reconnectInterval);
                    keepAliveJob = scheduledExecutorService.scheduleWithFixedDelay(this::sendKeepAlive,
                            reconnectInterval, reconnectInterval, TimeUnit.MINUTES);
                }
            }
            // Handle changes for each zone, if multiple
            for (int zoneindex = 0; zoneindex < bcs.getNumZones(); zoneindex++) {
                AutopatchBaseZoneHandler handler = findHandler(bcs.zonetype, bcs.getZone(zoneindex), bcs.level);
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

    protected void notifyZonesOfConfiguration() {
        getThing().getThings().stream().map(Thing::getHandler).filter(AutopatchBaseZoneHandler.class::isInstance)
                .map(AutopatchBaseZoneHandler.class::cast).forEach(AutopatchBaseZoneHandler::finalizeZoneStatus);
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
    }

    public synchronized void outputzoneStateChange(CommandType command, int zone) {
        for (Thing thing : getThing().getThings()) {
            ThingHandler handler = thing.getHandler();
            if (handler instanceof AutopatchOutputZoneGroupHandler groupHandler
                    && groupHandler.getZonenumbers().contains(zone)) {
                String label = groupHandler.getThing().getLabel();
                logger.trace("Updating OutputGroupZone {} for change in {} in zone {}",
                        label != null ? label : groupHandler.getThing().getUID(), command, zone);

                List<@NonNull AutopatchOutputZoneHandler> memberZones = getZoneHandlersFor(
                        groupHandler.getZonenumbers());

                if (command.equals(CommandType.MUTEZONE) || command.equals(CommandType.UNMUTEZONE)) {
                    groupHandler.updateChannelState(CHANNEL_MUTE, computeMuteAgreement(memberZones));
                } else if (command.equals(CommandType.VOLUME)) {
                    groupHandler.updateChannelState(CHANNEL_MUTE, computeMuteAgreement(memberZones));
                    groupHandler.updateChannelState(CHANNEL_VOLUME, computeVolumeAgreement(memberZones));
                } else if (command.equals(CommandType.OUTPUTSWITCH)) {
                    groupHandler.updateChannelState(CHANNEL_CONNECTEDINPUT,
                            computeConnectedInputAgreement(memberZones));
                }
            }
        }
    }

    private List<@NonNull AutopatchOutputZoneHandler> getZoneHandlersFor(List<Integer> zoneNumbers) {
        return getThing().getThings().stream().map(Thing::getHandler)
                .filter(AutopatchOutputZoneHandler.class::isInstance).map(AutopatchOutputZoneHandler.class::cast)
                .filter(zh -> zoneNumbers.contains(zh.zoneNumber))
                .filter(zh -> zh.getThing().getStatus() == ThingStatus.ONLINE).toList();
    }

    private String computeMuteAgreement(List<@NonNull AutopatchOutputZoneHandler> zones) {
        if (zones.isEmpty()) {
            return "UNDEF";
        }
        boolean allMuted = zones.stream().allMatch(zh -> zh.getMuteState() == OnOffType.ON);
        boolean allUnmuted = zones.stream().allMatch(zh -> zh.getMuteState() == OnOffType.OFF);
        if (allMuted) {
            return "ON";
        }
        if (allUnmuted) {
            return "OFF";
        }
        return "MIXED";
    }

    private String computeVolumeAgreement(List<@NonNull AutopatchOutputZoneHandler> zones) {
        List<@NonNull PercentType> volumes = zones.stream().map(AutopatchOutputZoneHandler::getVolumeState)
                .filter(PercentType.class::isInstance).map(PercentType.class::cast).toList();

        if (volumes.isEmpty()) {
            return "UNDEF";
        }

        boolean allEqual = volumes.stream().allMatch(v -> v.equals(volumes.get(0)));
        if (allEqual) {
            return volumes.get(0).toString();
        }

        int min = volumes.stream().mapToInt(PercentType::intValue).min().orElseThrow();
        int max = volumes.stream().mapToInt(PercentType::intValue).max().orElseThrow();
        return min + "<--MIXED-->" + max;
    }

    private String computeConnectedInputAgreement(List<@NonNull AutopatchOutputZoneHandler> zones) {
        if (zones.isEmpty()) {
            return "UNDEF";
        }
        State first = zones.get(0).getConnectedInputState();
        boolean allEqual = zones.stream().allMatch(zh -> zh.getConnectedInputState().equals(first));
        return allEqual ? first.toString() : "MIXED";
    }

    protected void disconnect() {
        logger.debug("Disconnecting from Autopatch device.");

        closeStream(dataInput);
        closeStream(dataOutput);
        dataInput = null;
        dataOutput = null;

        if (messageSenderThread != null) {
            messageSenderThread.interrupt();
        }

        if (this.keepAliveJob != null) {
            this.keepAliveJob.cancel(true);
            this.keepAliveJob = null;
        }

        if (connectRetryJob != null) {
            connectRetryJob.cancel(true);
            connectRetryJob = null;
        }

        if (this.keepAliveReconnectJob != null) {
            // This method can be called from the keepAliveReconnect thread. Make sure
            // we don't interrupt ourselves, as that may prevent the reconnection attempt.
            this.keepAliveReconnectJob.cancel(false);
            this.keepAliveReconnectJob = null;
        }
    }

    private void closeStream(AutoCloseable stream) {
        try {
            if (stream != null) {
                stream.close();
            }

        } catch (Exception e) {
            logger.debug("Error closing reader/writer/port: {}", e.getMessage(), e);
        }

    }

    @Override
    public void dispose() {
        disconnect();

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

    protected synchronized void reconnect() {
        if (!isDisposed) {
            logger.info("Keepalive timeout, attempting to reconnect to the bridge");

            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DUTY_CYCLE);

            disconnect();
            connect();
        }

    }

    protected abstract boolean validConfiguration();

    protected void getHostInterface() {
    }

}
