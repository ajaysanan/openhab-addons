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
 * The {@link AutopatchZoneConfig} class contains fields mapping thing configuration parameters.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class AutopatchZoneConfig {

    private int number;
    private int level;

    public int getNumber() {
        return number;
    }

    public int getLevel() {
        return level;
    }

    @Override
    public String toString() {
        return "Zone: " + " [" + String.valueOf(number) + "]";
    }

    public boolean sameZoneParameters(AutopatchZoneConfig config) {
        return (config.level == level) && (config.number == number);
    }

}
