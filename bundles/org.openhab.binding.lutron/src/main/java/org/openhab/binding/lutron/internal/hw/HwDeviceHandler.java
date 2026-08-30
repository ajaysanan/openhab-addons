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

import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.types.Command;

/**
 * Base handler for HomeWorks device things (dimmers, keypads) that share an address and forward commands to a
 * {@link HwBridgeHandler}.
 *
 * @author Ajay Sanan - Initial contribution
 */
public abstract class HwDeviceHandler extends BaseThingHandler {

    private String address;

    public HwDeviceHandler(Thing thing) {
        super(thing);
    }

    public void setAddress(String address) {
        this.address = normalizeAddress(address);
    }

    public static String normalizeAddress(String address) {
        if (address == null) {
            return null;
        }
        String stripped = address.replaceAll("[\\[\\]\\s]", "");
        String[] parts = stripped.split(":");
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                normalized.append(':');
            }
            try {
                normalized.append(Integer.parseInt(parts[i]));
            } catch (NumberFormatException e) {
                normalized.append(parts[i]); // leave anything non-numeric untouched
            }
        }
        return normalized.toString();
    }

    public String getAddress() {
        return address;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // Concrete device handlers (HwDimmerHandler, HwKeypadHandler) override this.
    }

    public void sendToBridge(String command) {
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No bridge configured");
            return;
        }
        if (bridge.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }

        BridgeHandler handler = bridge.getHandler();
        if (handler instanceof HwBridgeHandler hwBridgeHandler) {
            hwBridgeHandler.sendCommand(command);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_MISSING_ERROR, "No bridge associated");
        }
    }
}