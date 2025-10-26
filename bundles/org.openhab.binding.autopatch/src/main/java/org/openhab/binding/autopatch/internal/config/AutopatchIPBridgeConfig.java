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
package org.openhab.binding.autopatch.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * The {@link AutopatchIPBridgeConfig} class contains fields mapping thing configuration parameters.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class AutopatchIPBridgeConfig extends AutopatchBaseBridgeConfig {
    /**
     * Network port address
     */
    private String ipAddress = "";
    private int port = 4999;

    public String getipAddress() {
        return ipAddress;
    }

    public int getport() {
        return (port == 0 ? 4999 : port);
    }

    @Override
    public String toString() {
        return "Network Configuration [ipAddress=" + ipAddress + "; port=]" + port;
    }

    public boolean sameConnectionParameters(AutopatchIPBridgeConfig config) {
        return (ipAddress.equals(config.ipAddress) && (port == config.port));
    }
}
