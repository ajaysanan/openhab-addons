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
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link quozltempsensorBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class quozltempsensorBindingConstants {

    public static final String BINDING_ID = "quozltempsensor";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_DEVICE = new ThingTypeUID(BINDING_ID, "tempsensor");

    // List of all Channel ids
    public static final String DEVICE_NUMBER_CHANNEL = "probe";
}
