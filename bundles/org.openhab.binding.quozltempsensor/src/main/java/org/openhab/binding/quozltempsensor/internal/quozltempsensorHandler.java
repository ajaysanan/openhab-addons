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
package org.openhab.binding.quozltempsensor.internal;

import static org.openhab.binding.quozltempsensor.internal.quozltempsensorBindingConstants.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortList;
import tech.units.indriya.unit.Units;

/**
 * The {@link quozltempsensorHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class quozltempsensorHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(quozltempsensorHandler.class);

    private static final String regexFind = "(ts\\s(?<temp>C|F))|(\\d\\s\\d+.\\d\\d)+";
    private static final String regexGroup = "(\\d\\s)|(\\d+.\\d+)";
    private static final int waitTimeMS = 5000;
    private static final int heartbeatInterval = 2;

    private quozltempsensorConfiguration config = new quozltempsensorConfiguration();

    private @Nullable SerialPort serialPort;

    private Instant lastDataReceived = Instant.now();

    private @Nullable ScheduledFuture<?> reader;

    public quozltempsensorHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // this binding has no commands; read only from the port
    }

    @Override
    public void initialize() {
        config = getConfigAs(quozltempsensorConfiguration.class);

        final String port = config.serialPort;
        if (port == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port must be set");
            return;
        }

        // parse ports and if the port is found, initialize the reader
        boolean found = false;
        for (String testport : SerialPortList.getPortNames()) {
            found = port.equals(testport);
        }
        if (!found) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known");
            return;
        }
        // start the async connect task
        scheduler.submit(this::connect);
    }

    private void connect() {
        try {
            final SerialPort serialPort = new SerialPort(config.serialPort);
            this.serialPort = serialPort;

            // Port must be open first to set the parameters
            serialPort.openPort();
            serialPort.setParams(SerialPort.BAUDRATE_2400, SerialPort.DATABITS_8, SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);

            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, "Port open and device ONLINE");
        } catch (final SerialPortException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getExceptionType());
            return;
        } catch (final Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.COMMUNICATION_ERROR, e.getMessage());
            return;
        }
        logger.debug("Starting initial data read job with {} millisecond delay and {} millisecond repeat", waitTimeMS,
                waitTimeMS);
        reader = scheduler.scheduleWithFixedDelay(() -> receiveAndProcess(new StringBuilder()), waitTimeMS, waitTimeMS,
                TimeUnit.MILLISECONDS);
    }

    private void disconnect() {
        final SerialPort serialPort = this.serialPort;
        if (serialPort != null) {
            try {
                if (serialPort.isOpened()) {
                    serialPort.closePort();
                }
            } catch (SerialPortException e) {
                logger.debug("Error closing serial port: {}", e.getMessage(), e);
            }
            this.serialPort = null;
        }

        final ScheduledFuture<?> reader = this.reader;
        if (reader != null) {
            reader.cancel(false);
            this.reader = null;
        }

    }

    @Override
    public void dispose() {
        disconnect();
    }

    /**
     * Read from the serial port and process the data
     *
     * @param sb the string builder to receive the data
     */
    private void receiveAndProcess(final StringBuilder sb) {
        final SerialPort serialPort = this.serialPort;
        if (serialPort == null) {
            return;
        }

        try {
            final int byteCount = serialPort.getInputBufferBytesCount();
            if (byteCount == 0) {
                if (Duration.between(lastDataReceived, Instant.now()).toMinutes() > heartbeatInterval) {
                    logger.debug("Device has not responded in at least the last {} minute(s). Trying to reconnect.",
                            heartbeatInterval);
                    disconnect();
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.NONE, "Communications Lost.");
                    connect();
                }
                return;
            } else {
                sb.append(serialPort.readString());
                // Now have the data; process it
                parseUpdate(sb.toString());
            }

        } catch (final Exception e) {
            logger.debug("Error reading from serial port: {}", e.getMessage(), e);
        }

    }

    private void parseUpdate(final String result) {
        Pattern pat = Pattern.compile(regexFind);
        Matcher mat = pat.matcher(result);
        while (mat.find()) {
            String units = mat.group("temp");
            if (units != null) {
                // Found the initial Temperature Units; update that
                final String tempUnits = (units.equals("C")) ? "Celsius" : "Fahrenheit";
                Configuration configuration = editConfiguration();
                configuration.put("tempUnits", tempUnits);
                updateConfiguration(configuration);
                logger.debug("Temperature units forced to {}", tempUnits);
            } else {
                // Temperature channel
                handleUpdate(mat.group());
            }
        }
    }

    private void handleUpdate(String data) {
        // Calculate values from sensor data
        Pattern pat = Pattern.compile(regexGroup);
        Matcher mat = pat.matcher(data);

        int probe = Integer.parseInt((mat.find()) ? mat.group().trim() : "0");
        final BigDecimal temp = new BigDecimal(mat.find() ? mat.group() : "0")
                .setScale(Integer.parseInt(config.precision == null ? "2" : config.precision), RoundingMode.HALF_UP);

        if (probe > 0 && probe < 5) {
            // Check if channel exists otherwise create it
            configureChannel(probe);
            // Update channel
            final String tempUnits = config.tempUnits;
            if (tempUnits != null) {
                logger.debug("Updating probe{} with data {} in units {}", probe, temp,
                        tempUnits.equals("Celsius") ? Units.CELSIUS : ImperialUnits.FAHRENHEIT);
                updateState(DEVICE_NUMBER_CHANNEL + String.valueOf(probe), new QuantityType<Temperature>(temp,
                        tempUnits.equals("Celsius") ? Units.CELSIUS : ImperialUnits.FAHRENHEIT));
                lastDataReceived = Instant.now();
            }

        }
    }

    private void configureChannel(int probe) {
        final Channel channel = getThing().getChannel(DEVICE_NUMBER_CHANNEL + String.valueOf(probe));
        if (channel == null) {
            Channel newchannel;
            ChannelTypeUID channelTypeUID;
            ChannelUID channelUID;

            logger.debug("Configuring {}{} channel for sensor", DEVICE_NUMBER_CHANNEL, probe);
            ThingBuilder thingBuilder = editThing();
            channelTypeUID = new ChannelTypeUID(BINDING_ID, "tempChannel");
            channelUID = new ChannelUID(getThing().getUID(), DEVICE_NUMBER_CHANNEL + String.valueOf(probe));
            newchannel = ChannelBuilder.create(channelUID, "Number:Temperature").withType(channelTypeUID)
                    .withLabel("Temperature Probe " + String.valueOf(probe)).build();
            thingBuilder.withChannel(newchannel);
            updateThing(thingBuilder.build());
        }
    }
}
