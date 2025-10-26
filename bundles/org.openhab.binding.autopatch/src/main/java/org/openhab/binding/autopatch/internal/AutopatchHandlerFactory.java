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
package org.openhab.binding.autopatch.internal;

import static org.openhab.binding.autopatch.internal.AutopatchBindingConstants.*;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.handler.AutopatchIPBridgeHandler;
import org.openhab.binding.autopatch.internal.handler.AutopatchInputZoneHandler;
import org.openhab.binding.autopatch.internal.handler.AutopatchOutputZoneGroupHandler;
import org.openhab.binding.autopatch.internal.handler.AutopatchOutputZoneHandler;
import org.openhab.binding.autopatch.internal.handler.AutopatchSerialBridgeHandler;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingRegistry;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.BridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AutopatchHandlerFactory} is responsible for creating things and thing
 * handlers.
 *
 * @author Ajay Sanan - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.autopatch", service = ThingHandlerFactory.class)
public class AutopatchHandlerFactory extends BaseThingHandlerFactory {

    private final Logger logger = LoggerFactory.getLogger(AutopatchHandlerFactory.class);

    private final NetworkAddressService networkAddressService;
    private final SerialPortManager serialPortManager;
    private final ThingRegistry thingRegistry;
    private @Nullable BridgeHandler bridgehandler = null;

    @Activate
    public AutopatchHandlerFactory(final @Reference SerialPortManager serialPortManager,
            final @Reference NetworkAddressService networkAddressService,
            final @Reference ThingRegistry thingRegistry) {
        this.serialPortManager = serialPortManager;
        this.thingRegistry = thingRegistry;
        this.networkAddressService = networkAddressService;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (SUPPORTED_BRIDGE_TYPES_UIDS.contains(thingTypeUID)) {
            if (THING_TYPE_SERIALBRIDGE.equals(thingTypeUID)) {
                bridgehandler = new AutopatchSerialBridgeHandler((Bridge) thing, serialPortManager, thingRegistry);
            } else if (THING_TYPE_IPBRIDGE.equals(thingTypeUID)) {
                bridgehandler = new AutopatchIPBridgeHandler((Bridge) thing,
                        networkAddressService.getPrimaryIpv4HostAddress(), thingRegistry);
            }
            logger.debug("AutopatchHandlerFactory created BridgeHandler for {}", thingTypeUID.getAsString());
            return bridgehandler;
        } else if (SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            // Warn if doesn't have a registered Bridge
            if (thing.getBridgeUID() == null) {
                logger.warn("Thing: {}. No bridge specified.", thing.getLabel());
            }
            ThingHandler handler = null;
            if (THING_TYPE_INPUTZONE.equals(thingTypeUID)) {
                handler = new AutopatchInputZoneHandler(thing);
            } else if (THING_TYPE_OUTPUTZONE.equals(thingTypeUID)) {
                handler = new AutopatchOutputZoneHandler(thing);
            } else if (THING_TYPE_OUTPUTZONEGROUP.equals(thingTypeUID)) {
                handler = new AutopatchOutputZoneGroupHandler(thing);
            }
            logger.debug("AutopatchHandlerFactory created ThingHandler for {}", thing.getUID().getAsString());
            return handler;

        } else {
            logger.warn("Unsupported Thing-Type: {}", thingTypeUID.getAsString());
        }

        return null;
    }

    @Override
    protected void removeHandler(ThingHandler thingHandler) {
        if (thingHandler.equals(bridgehandler)) {
            bridgehandler = null;
        }
        super.removeHandler(thingHandler);
    }
}
