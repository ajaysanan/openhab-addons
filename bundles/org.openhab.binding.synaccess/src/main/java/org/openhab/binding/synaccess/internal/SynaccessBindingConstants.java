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
package org.openhab.binding.synaccess.internal;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * The {@link SynaccessBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class SynaccessBindingConstants {

    public static final String BINDING_ID = "synaccess";

    // List of all Bridge Type UIDs
    public static final ThingTypeUID THING_TYPE_IPBRIDGE = new ThingTypeUID(BINDING_ID, "ipbridge");
    public static final ThingTypeUID THING_TYPE_SERIALBRIDGE = new ThingTypeUID(BINDING_ID, "serialbridge");

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_PDU = new ThingTypeUID(BINDING_ID, "pdu");

    // List of all Channel ids
    public static final String CHANNEL_PORTSTATUS = "portstatus";
    public static final String CHANNEL_ALLPORTS = "allports";

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = new HashSet<>();
    public static final Set<ThingTypeUID> DISCOVERABLE_THING_TYPES_UIDS = new HashSet<>();

    static {
        SUPPORTED_THING_TYPES_UIDS.add(THING_TYPE_IPBRIDGE);
        SUPPORTED_THING_TYPES_UIDS.add(THING_TYPE_SERIALBRIDGE);
        SUPPORTED_THING_TYPES_UIDS.add(THING_TYPE_PDU);
        DISCOVERABLE_THING_TYPES_UIDS.add(THING_TYPE_PDU);
    }

    // Bridge config properties (used by discovery service)
    public static final String HOST = "ipAddress";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String SERIAL_NUMBER = "serialNumber";
}
