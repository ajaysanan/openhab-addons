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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link quozltempsensorConfiguration} class contains fields mapping thing configuration parameters.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class quozltempsensorConfiguration {

    /**
     * Serial port name
     */
    public @Nullable String serialPort;

    /**
     * Temperature units
     */
    public @Nullable String tempUnits;

    /**
     * Temperature units
     */
    public @Nullable String precision;

    @Override
    public String toString() {
        return "SerialBridgeConfiguration [serialPort=" + serialPort + ", tempUnits=" + tempUnits + ", precision="
                + precision + "]";
    }
}
