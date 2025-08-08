package org.openhab.binding.autopatch.internal.handler;

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.CHANNEL_COMMAND;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.AutopatchBindingConstants.IOType;
import org.openhab.binding.autopatch.internal.command.BCSCommand;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AutopatchBaseBridgeHandler extends BaseBridgeHandler {
    private final Logger logger = LoggerFactory.getLogger(SerialBridgeHandler.class);

    public boolean isConnected = false;

    protected int reconnectInterval;
    protected int pollInterval;
    protected @Nullable ScheduledFuture<?> reader;
    protected @Nullable ScheduledFuture<?> connector;

    protected OutputStreamWriter dataOutput;
    protected @Nullable BufferedReader dataInput;

    /*
     * // new variables for IP version
     * private BufferedInputStream serialIn;
     * private DataOutputStream serialOut;
     */

    public AutopatchBaseBridgeHandler(Bridge bridge) {
        super(bridge);
        // TODO Auto-generated constructor stub
    }

    protected void connect() {
        logger.debug("Sending diagnostics command.");
        sendCommand("~scr!");

        logger.debug("Starting keepAlive job with interval {} minutes", reconnectInterval);
        connector = scheduler.scheduleWithFixedDelay(this::sendKeepAlive, reconnectInterval, reconnectInterval,
                TimeUnit.MINUTES);

        logger.debug("Starting data poll job with interval {} seconds", pollInterval);
        reader = scheduler.scheduleWithFixedDelay(this::checkData, pollInterval, pollInterval, TimeUnit.SECONDS);
    }

    protected void sendKeepAlive() {
        // NEEDS TO BE FINISHED
        logger.trace("Checking for active connection");
    }

    /**
     * Write Port Data.
     *
     * @param command
     */
    public void sendCommand(String command) {
        try {
            logger.debug("Autopatch sending command-->{}<--", command);
            if (dataOutput != null) {
                dataOutput.write(command);
                dataOutput.flush();
            } else {
                logger.debug("Autopatch offline: ignoring command-->{}<--", command);
                throw new IOException("Output Stream is Closed");
            }
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Communication error");
        }
    }

    protected void checkData() {
        logger.trace("Poll: checking for data from port.");
        try {

            for (String data : getData()) {
                if (data != "") {
                    handleIncomingMessage(data);
                }
            }

        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Error reading from port");
        }

    }

    public synchronized ArrayList<String> getData() throws IOException {
        final BufferedReader dataInput = this.dataInput;

        char readchar;
        int readint = 0;
        ArrayList<String> messages = new ArrayList<>();
        StringBuilder messageLine = new StringBuilder();

        // Autopatch does not terminate responses with cr, lf or crlf so readline doesn't work
        // Read data until crlf or ")" or "X" is received
        // Read multiple lines of data if there are multiple terminating characters found.

        if (dataInput != null) {
            while (dataInput.ready() && (readint = dataInput.read()) != -1) {
                switch (readchar = (char) readint) {
                    case '\r':
                        break;
                    case '\n':
                        if (messageLine.length() > 0) {
                            logger.debug("Received information type message from Autopatch-->{}<--",
                                    messageLine.toString());
                            messageLine.setLength(0);
                        }
                        break;
                    case 'X':
                    case ')':
                        messageLine.append(readchar);
                        messages.add(messageLine.toString());
                        messageLine.setLength(0);
                        break;
                    default:
                        messageLine.append(readchar);
                        break;
                }
            }
        }
        return messages;
    }

    private void handleIncomingMessage(final String line) {
        // global commands (presets and reboot) don't send incoming messages
        // Send response to BCSCommand class to decode

        BCSCommand bcs = new BCSCommand();
        if (bcs.decodeCommand(line)) {
            ZoneHandler handler = findHandler(bcs.iotype, bcs.zone);
            if (handler != null) {
                handler.handleStateChange(bcs);
            }
            // handle the equalizer type separately
            /*
             * // Need to iterate all the zones in the bcs decode
             * Iterator<Map.Entry<Integer, Integer>> it = bcs.zonevalues.entrySet().iterator();
             * while (it.hasNext()) {
             * Map.Entry<Integer, Integer> zone = it.next();
             * ZoneHandler handler = findHandler(bcs.iotype, zone.getKey());
             * if (handler != null) {
             * handler.handleLevelChange(bcs, zone.getValue());
             * }
             * it.remove();
             * }
             */
            logger.debug("Received and processed status message from Autopatch-->{}<--", line);
        } else {
            logger.debug("Received and ignored message from Autopatch-->{}<--", line);
        }
    }

    @Nullable
    private ZoneHandler findHandler(IOType iotype, int zone) {
        for (Thing thing : getThing().getThings()) {
            // find the inputzone or outputzone handler
            if (thing.getHandler() instanceof ZoneHandler) {
                ZoneHandler handler = (ZoneHandler) thing.getHandler();
                if (handler != null) {
                    if (iotype.equals(handler.zoneType) && zone == handler.zoneNumber) {
                        return handler;
                    }
                }

            }
        }
        return null;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (CHANNEL_COMMAND.equals(channelUID.getId()) && command.toString().equals("RESET")) {
            sendCommand("~app!");
        }
    }

    protected void disconnect() {
        try {

            if (dataInput != null) {
                dataInput.close();
            }

            if (dataOutput != null) {
                dataOutput.close();
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
        super.dispose();
    }

}
