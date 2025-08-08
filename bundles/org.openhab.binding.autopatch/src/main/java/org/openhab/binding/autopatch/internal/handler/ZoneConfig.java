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
 * The {@link ZoneConfig} class contains fields mapping thing configuration parameters.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class ZoneConfig {

    private @Nullable String label;
    private @Nullable Integer number;
    private @Nullable Integer level;

    public String getLabel() {
        return (label == null ? "" : label);
    }

    public int getNumber() {
        return (number == null ? 1 : number);
    }

    public int getLevel() {
        return (level == null ? 0 : level);
    }

    @Override
    public String toString() {
        return "Input Zone: " + label + " [" + String.valueOf(getNumber()) + "]";
    }

}
