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

import java.time.*;
import java.util.concurrent.*;
import java.util.regex.*;

import javax.measure.quantity.Temperature;

import org.eclipse.jdt.annotation.*;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.*;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.types.Command;
import org.slf4j.*;

import jssc.*;
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
    private static final int heartbeatInterval = 1; // for testing; change to 2 when done

    private @Nullable quozltempsensorConfiguration config = new quozltempsensorConfiguration();

    private @Nullable SerialPort serialPort;
    private @Nullable String port;
    private @Nullable String tempUnits;

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
        logger.trace("Method: initialize");
        config = getConfigAs(quozltempsensorConfiguration.class);

        port = config.serialPort;
        tempUnits = config.tempUnits;

        if (port == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port must be set");
            return;
        }

        // parse ports and if the port is found, initialize the reader

        boolean found = false;
        // final SerialPort[] comPorts = SerialPort.getCommPorts();
        for (String testport : SerialPortList.getPortNames()) {
            found = port.equals(testport);
        }
        if (!found) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.OFFLINE.CONFIGURATION_ERROR, "Port is not known");
            return;
        }
        connect();
    }

    private void connect() {
        logger.trace("Method: connect");
        try {
            final SerialPort serialPort = new SerialPort(port);
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

    /*
     * @Override
     * public void serialEvent(final @Nullable SerialPortEvent event) {
     * if (event != null) {
     * switch (event.getEventType()) { // probably don't need since we're not looking at any other masks
     * case SerialPort.MASK_RXCHAR:
     * if (readerActive.compareAndSet(false, true)) {
     * reader = scheduler.schedule(() -> receiveAndProcess(new StringBuilder(), true), 0,
     * TimeUnit.MILLISECONDS);
     * }
     * break;
     * default:
     * break;
     * }
     * }
     * }
     */

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
        logger.trace("Method: dispose");
        disconnect();
    }

    /**
     * Read from the serial port and process the data
     *
     * @param sb the string builder to receive the data
     * @param firstAttempt indicates if this is the first read attempt without waiting
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
            // logger.trace("Data received: {}", mat.group());
            String units = mat.group("temp");
            if (units != null && config != null) {
                // Found the initial Temperature Units; update that
                tempUnits = (units.equals("C")) ? "Celsius" : "Fahrenheit";
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
        float temp = Float.parseFloat((mat.find()) ? mat.group() : "0");

        // Check if channel exists otherwise create it
        configureChannel(probe);
        // Update channel
        updateState(DEVICE_NUMBER_CHANNEL + String.valueOf(probe), new QuantityType<Temperature>(Float.valueOf(temp),
                (tempUnits == "Celsius") ? Units.CELSIUS : ImperialUnits.FAHRENHEIT));
        logger.trace("Updated {}{} to {} {}", DEVICE_NUMBER_CHANNEL, probe, temp, tempUnits);
        lastDataReceived = Instant.now();
    }

    @Override
    public void handleRemoval() {
        logger.trace("Method: handleRemoval");
        updateStatus(ThingStatus.REMOVED);
    }

    @Override
    public void thingUpdated(Thing thing) {
        logger.trace("Method: thingUpdated");
    }

    private void configureChannel(int probe) {
        Channel newchannel;
        ChannelTypeUID channelTypeUID;
        ChannelUID channelUID;

        final Channel channel = getThing().getChannel(DEVICE_NUMBER_CHANNEL + String.valueOf(probe));
        if (channel == null) {
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
