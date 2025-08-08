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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link SerialBridgeConfig} class contains fields mapping thing configuration parameters.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class SerialBridgeConfig {

    /**
     * Serial port name
     */
    private @Nullable String serialPort;
    private @Nullable String deviceType;
    private @Nullable Integer refreshInterval;
    private @Nullable Integer pollInterval;

    private static final int DEFAULT_RECONNECT_MINUTES = 60;
    private static final int DEFAULT_POLL_SECONDS = 5;

    public String getSerialPort() {
        return (serialPort == null ? "" : serialPort);
    }

    public String getDeviceType() {
        return (deviceType == null ? "PrecisDSP1818" : deviceType);
    }

    public int getRefreshInterval() {
        return (refreshInterval == null ? DEFAULT_RECONNECT_MINUTES : refreshInterval);
    }

    public int getPollInterval() {
        return (pollInterval == null ? DEFAULT_POLL_SECONDS : pollInterval);
    }

    @Override
    public String toString() {
        return "SerialConfiguration [serialPort=" + serialPort + "]";
    }

    public boolean sameConnectionParameters(SerialBridgeConfig config) {
        return (serialPort == config.serialPort) && (deviceType == config.deviceType)
                && (refreshInterval == config.refreshInterval);
    }
}
