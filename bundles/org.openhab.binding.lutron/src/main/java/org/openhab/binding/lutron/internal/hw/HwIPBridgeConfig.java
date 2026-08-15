/**
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

import java.util.Objects;

/**
 * Configuration settings for an {@link org.openhab.binding.lutron.internal.hw.HwIPBridgeHandler}.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class HwIPBridgeConfig {
    public static final Integer DEFAULT_PORT = 23;

    private String ipAddress;
    private Integer port = DEFAULT_PORT;
    private String user;
    private String password;
    private Integer reconnect;
    private Integer heartbeat;
    private Integer delay = 0;
    private Boolean updateTime = true;

    public String getIpAddress() {
        return this.ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Integer getPort() {
        return this.port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getUser() {
        return this.user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getReconnect() {
        return this.reconnect;
    }

    public void setReconnect(Integer reconnect) {
        this.reconnect = reconnect;
    }

    public Integer getHeartbeat() {
        return this.heartbeat;
    }

    public void setHeartbeat(Integer heartbeat) {
        this.heartbeat = heartbeat;
    }

    public Integer getDelay() {
        return this.delay;
    }

    public void setDelay(Integer delay) {
        this.delay = delay;
    }

    public Boolean getUpdateTime() {
        return this.updateTime;
    }

    public void setUpdateTime(Boolean updateTime) {
        this.updateTime = updateTime;
    }

    public boolean sameConnectionParameters(HwIPBridgeConfig config) {
        return Objects.equals(ipAddress, config.ipAddress) && Objects.equals(user, config.user)
                && Objects.equals(password, config.password) && Objects.equals(port, config.port)
                && Objects.equals(reconnect, config.reconnect) && Objects.equals(heartbeat, config.heartbeat)
                && Objects.equals(delay, config.delay);
    }
}