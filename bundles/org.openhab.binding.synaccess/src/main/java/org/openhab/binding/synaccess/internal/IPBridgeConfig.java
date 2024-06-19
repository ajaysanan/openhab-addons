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

/**
 * Configuration settings for an {@link org.openhab.binding.synaccess.handler.IPBridgeHandler}.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class IPBridgeConfig {
    public String ipAddress;
    public int port;
    public String user;
    public String password;
    public int reconnect;
    public int heartbeat;
    public int delay = 0;

    public boolean sameConnectionParameters(IPBridgeConfig config) {
        return ipAddress.equals(config.ipAddress) && user.equals(config.user) && password.equals(config.password)
                && (port == config.port) && (reconnect == config.reconnect) && (heartbeat == config.heartbeat)
                && (delay == config.delay);
    }

}
