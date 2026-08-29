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

import static org.openhab.binding.synaccess.internal.SynaccessBindingConstants.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler responsible for communicating with a Synaccess PDU over its HTTP cmd.cgi interface
 * (netBooter B/DU series command set: $A3/$A4/$A5/$A7).
 *
 * This is an alternative to {@link IPBridgeHandler} (which uses telnet). There is always exactly
 * one PDU per connection, so this single handler owns both the communication and the
 * switch/command channels for the device.
 *
 * HTTP requests are stateless and self-timing-out, so there is no persistent connection,
 * reconnect state machine, or background sender thread to manage - each poll or command is an
 * independent request with its own timeout.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class HttpBridgeHandler extends BaseThingHandler {

    private static final int DEFAULT_HTTP_PORT = 80;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final Logger logger = LoggerFactory.getLogger(HttpBridgeHandler.class);

    private HttpBridgeConfig config = new HttpBridgeConfig();

    private int pollIntervalMinutes;

    private String baseUrl = "";
    private String authHeader = "";

    private HttpClient httpClient = HttpClient.newHttpClient();

    private @Nullable ScheduledFuture<?> pollJob;

    private long lastChannelUpdateTime = 0;

    public HttpBridgeHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        this.config = getConfigAs(HttpBridgeConfig.class);
        if (!validConfiguration(this.config)) {
            return;
        }

        pollIntervalMinutes = (config.pollInterval > 0) ? config.pollInterval : DEFAULT_HEARTBEAT_MINUTES;

        int httpPort = (config.port > 0) ? config.port : DEFAULT_HTTP_PORT;
        this.baseUrl = "http://" + config.ipAddress + ":" + httpPort + "/cmd.cgi";

        String user = !config.user.isEmpty() ? config.user : DEFAULT_USER;
        String password = !config.password.isEmpty() ? config.password : DEFAULT_PASSWORD;
        this.authHeader = "Basic "
                + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for first poll");

        // Poll immediately, then on a fixed schedule. Each poll is a self-contained HTTP
        // request; a failure just leaves the thing OFFLINE until the next successful poll.
        pollJob = scheduler.scheduleWithFixedDelay(this::poll, 0, pollIntervalMinutes, TimeUnit.MINUTES);
    }

    private boolean validConfiguration(HttpBridgeConfig config) {
        if (config.ipAddress.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "IP address not specified");
            return false;
        }
        return true;
    }

    /**
     * Issues an HTTP GET for the given Synaccess command (e.g. "$A5", "$A3 1 0") and returns the
     * response body. Throws IOException on any transport or non-2xx failure.
     */
    private String sendGetCommand(String command) throws IOException, InterruptedException {
        logger.trace("{} sending command {}", thing.getUID(), command);

        String query = URLEncoder.encode(command, StandardCharsets.UTF_8).replace("+", "%20");
        URI uri = URI.create(baseUrl + "?" + query);

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Authorization", authHeader)
                .GET().build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Unexpected HTTP status " + response.statusCode());
        }

        String body = response.body().trim();
        logger.trace("{} received response: -->{}<--", thing.getUID(), body);
        return body;
    }

    /**
     * Periodic poll: fetches outlet/current/temp status via $A5, updates channels, and reflects
     * connectivity in the thing status. Also used as the RefreshType handler.
     */
    private void poll() {
        try {
            String body = sendGetCommand("$A5");
            handleStatusResponse(body);
            recordSuccess();

            if (thing.getStatus() != ThingStatus.ONLINE) {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (IOException e) {
            logger.debug("{} poll failed: {}", thing.getUID(), e.getMessage());
            recordFailure();
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordSuccess() {
        updateState(CHANNEL_LAST_SUCCESS, new DateTimeType(ZonedDateTime.now(ZoneId.systemDefault())));
    }

    private void recordFailure() {
        updateState(CHANNEL_LAST_FAILURE, new DateTimeType(ZonedDateTime.now(ZoneId.systemDefault())));
    }

    /**
     * Parses an $A5 response. Observed device behavior (NP-02B, FW114) prefixes the payload with
     * the command's own return code - e.g. "$A0,00" rather than the bare "xxxx,cccc,tt" shown in
     * Synaccess's documented example - so that prefix is stripped (and treated as a hard failure
     * if it's $AF) before parsing the outlet-state digit string. The digit string is always the
     * first remaining comma-separated field, rightmost digit = outlet 1.
     */
    private void handleStatusResponse(String body) throws IOException {
        String[] parts = body.split(",");
        if (parts.length == 0) {
            throw new IOException("Empty $A5 response");
        }

        int dataIndex = 0;
        if (parts[0].startsWith("$A")) {
            if (!"$A0".equals(parts[0])) {
                throw new IOException("$A5 command failed: " + parts[0]);
            }
            dataIndex = 1;
        }

        if (dataIndex >= parts.length || parts[dataIndex].isEmpty()) {
            throw new IOException("Unexpected $A5 response: " + body);
        }

        String outletStates = parts[dataIndex].trim();
        configureChannels(outletStates.length());

        for (int i = 1; i <= outletStates.length(); i++) {
            // Rightmost digit is outlet 1.
            char stateChar = outletStates.charAt(outletStates.length() - i);
            handlePortUpdate(i, String.valueOf(stateChar));
        }
    }

    @Override
    public void handleConfigurationUpdate(Map<String, Object> configurationParameters) {
        if (!isModifyingCurrentConfig(configurationParameters)) {
            return;
        }
        validateConfigurationParameters(configurationParameters);

        Configuration configuration = editConfiguration();
        for (Entry<String, Object> configurationParameter : configurationParameters.entrySet()) {
            configuration.put(configurationParameter.getKey(), configurationParameter.getValue());
        }

        HttpBridgeConfig newConfig = configuration.as(HttpBridgeConfig.class);
        boolean validConfig = validConfiguration(newConfig);
        boolean needsReinit = validConfig && !this.config.sameConnectionParameters(newConfig);

        if (isInitialized()) {
            if (!validConfig || needsReinit) {
                dispose();
                updateConfiguration(configuration);
                initialize();
            } else {
                updateConfiguration(configuration);
                this.config = newConfig;
                applyTimingConfig(newConfig);
            }
        } else {
            // Mirrors BaseThingHandler's default behavior for the not-yet-initialized case.
            updateConfiguration(configuration);
            ThingHandlerCallback callback = getCallback();
            if (callback != null) {
                callback.configurationUpdated(getThing());
            } else {
                logger.warn("Handler {} tried updating its configuration although the handler was already disposed.",
                        getClass().getSimpleName());
            }
        }
    }

    /**
     * Applies pollInterval/delay changes in place, rescheduling the poll job only if the
     * interval actually changed. The connection itself (baseUrl, authHeader, httpClient) is
     * untouched since none of that depends on these parameters.
     */
    private void applyTimingConfig(HttpBridgeConfig newConfig) {
        int newPollInterval = (newConfig.pollInterval > 0) ? newConfig.pollInterval : DEFAULT_HEARTBEAT_MINUTES;

        if (newPollInterval != pollIntervalMinutes) {
            pollIntervalMinutes = newPollInterval;
            ScheduledFuture<?> job = pollJob;
            if (job != null) {
                job.cancel(false);
            }
            pollJob = scheduler.scheduleWithFixedDelay(this::poll, pollIntervalMinutes, pollIntervalMinutes,
                    TimeUnit.MINUTES);
        }
    }

    @Override
    public synchronized void dispose() {
        logger.trace("Disposing {}", thing.getUID());
        ScheduledFuture<?> job = pollJob;
        if (job != null) {
            job.cancel(true);
            pollJob = null;
        }
        super.dispose();
    }

    // --- Port switch channels / commands ---

    private void configureChannels(int numberChannels) {
        List<Channel> existingChannels = getThing().getChannels();

        if (existingChannels.isEmpty()) {
            logger.debug("Configuring {} channels for PDU {}", numberChannels, thing.getUID());
            ThingBuilder thingBuilder = editThing();
            List<Channel> channelList = new ArrayList<>();

            // port names are 1 based (portstatus1, portstatus2, etc)
            for (int i = 1; i <= numberChannels; i++) {
                ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, "switchState");
                ChannelUID channelUID = new ChannelUID(getThing().getUID(), CHANNEL_PORTSTATUS + Integer.toString(i));
                Channel channel = ChannelBuilder.create(channelUID, "Switch").withType(channelTypeUID)
                        .withLabel("Power Port " + Integer.toString(i)).build();
                channelList.add(channel);
            }

            ChannelTypeUID allPortsTypeUID = new ChannelTypeUID(BINDING_ID, "commandChannel");
            ChannelUID allPortsUID = new ChannelUID(getThing().getUID(), CHANNEL_ALLPORTS);
            Channel allPortsChannel = ChannelBuilder.create(allPortsUID, "String").withType(allPortsTypeUID)
                    .withLabel("All Power Ports").build();
            channelList.add(allPortsChannel);

            ChannelTypeUID lastRequestTypeUID = new ChannelTypeUID(BINDING_ID, "lastRequestDateTime");

            ChannelUID lastSuccessUID = new ChannelUID(getThing().getUID(), CHANNEL_LAST_SUCCESS);
            Channel lastSuccessChannel = ChannelBuilder.create(lastSuccessUID, "DateTime").withType(lastRequestTypeUID)
                    .withLabel("Last Success").build();
            channelList.add(lastSuccessChannel);

            ChannelUID lastFailureUID = new ChannelUID(getThing().getUID(), CHANNEL_LAST_FAILURE);
            Channel lastFailureChannel = ChannelBuilder.create(lastFailureUID, "DateTime").withType(lastRequestTypeUID)
                    .withLabel("Last Failure").build();
            channelList.add(lastFailureChannel);

            thingBuilder.withChannels(channelList);
            updateThing(thingBuilder.build());
        }
    }

    private void handlePortUpdate(int port, String status) {
        // Parameter is the port status (0 or 1) for the given port (1 based)
        BigDecimal state = new BigDecimal(status);
        OnOffType onOff = state.compareTo(BigDecimal.ZERO) == 0 ? OnOffType.OFF : OnOffType.ON;
        logger.debug("{}: updating {}{} to {}", thing.getUID(), CHANNEL_PORTSTATUS, port, onOff);
        updateState(CHANNEL_PORTSTATUS + port, onOff);
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        // Refresh state when new item is linked. Suppress multiple requests sent within 3 seconds
        if ((Instant.now().toEpochMilli() - lastChannelUpdateTime > 3000)
                && channelUID.getId().contains(CHANNEL_PORTSTATUS)) {
            lastChannelUpdateTime = Instant.now().toEpochMilli();
            scheduler.submit(this::poll);
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();
        Channel channel = getThing().getChannel(id);

        if (channel == null) {
            logger.warn("Command received on invalid channel {} for device {}", channelUID, getThing().getUID());
            return;
        }

        // For portstatus commands handle OnOffType and RefreshType
        if (id.startsWith(CHANNEL_PORTSTATUS)) {
            if (command instanceof OnOffType) {
                int outletNumber = Integer.parseInt(id.substring(id.length() - 1));
                String state = command.equals(OnOffType.ON) ? "1" : "0";
                sendAndPoll("$A3 " + outletNumber + " " + state);
            } else if (command instanceof RefreshType) {
                scheduler.submit(this::poll);
            } else {
                logger.warn("Invalid command type {} received for channel {} device {}", command, channelUID,
                        getThing().getUID());
            }
            return;
        }
        // For allports command handle stringtype only; write only channel
        if (id.equals(CHANNEL_ALLPORTS) && (command instanceof StringType)) {
            if (command.toString().equals("ALL_ON")) {
                sendAndPoll("$A7 1");
            } else if (command.toString().equals("ALL_OFF")) {
                sendAndPoll("$A7 0");
            }
        }
    }

    /**
     * Sends a control command (fire-and-forget from openHAB's perspective) and immediately
     * follows up with a status poll so channel state reflects the change. Runs off the calling
     * thread since handleCommand() must return quickly.
     * <p>
     * $A3/$A7 respond with a bare "$A0" (success) or "$AF" (rejected) - no comma-suffixed
     * payload like $A5 has. A rejected command is a device-level response, not a connectivity
     * problem (the device clearly answered), so it's logged but doesn't affect thing status or
     * the lastFailure channel - those are reserved for actual communication failures.
     */
    private void sendAndPoll(String command) {
        scheduler.submit(() -> {
            String response;
            try {
                response = sendGetCommand(command);
            } catch (IOException e) {
                logger.warn("{}: command '{}' failed: {}", thing.getUID(), command, e.getMessage());
                recordFailure();
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!"$A0".equals(response)) {
                logger.warn("{}: command '{}' was rejected by the device: {}", thing.getUID(), command, response);
            }

            if (COMMAND_SEND_DELAY_MILLIS > 0) {
                try {
                    Thread.sleep(COMMAND_SEND_DELAY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            poll();
        });
    }
}