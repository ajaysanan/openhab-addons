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

    public static final String DEFAULT_USER = "admin";
    public static final String DEFAULT_PASSWORD = "admin";
    public static final int DEFAULT_RECONNECT_MINUTES = 5;
    public static final int DEFAULT_HEARTBEAT_MINUTES = 5;
    public static final long KEEPALIVE_TIMEOUT_SECONDS = 30;

    // List of all Thing Type UIDs. There is one Thing per physical PDU - connection and
    // switch/command channels are both owned by IPBridgeHandler.
    public static final ThingTypeUID THING_TYPE_IPBRIDGE = new ThingTypeUID(BINDING_ID, "ipbridge");

    // List of all Channel ids
    public static final String CHANNEL_PORTSTATUS = "portstatus";
    public static final String CHANNEL_ALLPORTS = "allports";

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = new HashSet<>();

    static {
        SUPPORTED_THING_TYPES_UIDS.add(THING_TYPE_IPBRIDGE);
    }
}
