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

import static org.openhab.binding.synaccess.internal.SynaccessBindingConstants.*;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SynaccessHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.synaccess", service = ThingHandlerFactory.class)
public class SynaccessHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(SynaccessHandlerFactory.class);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (thingTypeUID.equals(THING_TYPE_IPBRIDGE)) {
            IPBridgeHandler bridgeHandler = new IPBridgeHandler((Bridge) thing);
            registerDiscoveryService(bridgeHandler);
            return bridgeHandler;
        } else if (thingTypeUID.equals(THING_TYPE_PDU)) {
            return new PDUHandler(thing);
        }
        return null;
    }

    private final Map<ThingUID, @Nullable ServiceRegistration<?>> discoveryServiceRegMap = new HashMap<>();
    // Marked as Nullable only to fix incorrect redundant null check complaints from null annotations

    @Override
    protected synchronized void removeHandler(ThingHandler thingHandler) {
        if (thingHandler instanceof IPBridgeHandler) {
            ServiceRegistration<?> serviceReg = discoveryServiceRegMap.remove(thingHandler.getThing().getUID());
            if (serviceReg != null) {
                logger.debug("Unregistering discovery service.");
                serviceReg.unregister();
            }
        }
    }

    /**
     * Register a discovery service for a bridge handler.
     *
     * @param bridgeHandler bridge handler for which to register the discovery service
     */
    private synchronized void registerDiscoveryService(IPBridgeHandler bridgeHandler) {
        logger.debug("Registering discovery service.");
        SynaccessDiscoveryService discoveryService = new SynaccessDiscoveryService(bridgeHandler);
        bridgeHandler.setDiscoveryService(discoveryService);
        discoveryServiceRegMap.put(bridgeHandler.getThing().getUID(),
                bundleContext.registerService(DiscoveryService.class.getName(), discoveryService, null));
    }
}
