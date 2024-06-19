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

import static org.openhab.binding.synaccess.internal.SynaccessBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SynaccessDiscoveryService} handles creation of the pdu identified by the bridge handler.
 * Requests from the framework to startScan() are ignored, since no active scanning is possible.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
public class SynaccessDiscoveryService extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(SynaccessDiscoveryService.class);

    private IPBridgeHandler bridgeHandler;

    public SynaccessDiscoveryService(IPBridgeHandler bridgeHandler) throws IllegalArgumentException {
        super(DISCOVERABLE_THING_TYPES_UIDS, 0, false);
        this.bridgeHandler = bridgeHandler;
    }

    @Override
    protected void startScan() {
        // Ignore start scan requests; there is only one PDU per bridge
    }

    public void notifyDiscoveredPDU(String address) {
        ThingUID bridgeUID = bridgeHandler.getThing().getUID();
        ThingUID uid = new ThingUID(THING_TYPE_PDU, bridgeUID, "pdu");

        DiscoveryResult result = DiscoveryResultBuilder.create(uid).withBridge(bridgeUID)
                .withProperty("PARAMETER_ADDRESS", address).build();
        thingDiscovered(result);
        logger.debug("Discovered PDU {}", uid);
    }

}