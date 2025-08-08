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

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.autopatch.internal.handler.IPBridgeHandler;
import org.openhab.binding.autopatch.internal.handler.InputzoneHandler;
import org.openhab.binding.autopatch.internal.handler.OutputzoneHandler;
import org.openhab.binding.autopatch.internal.handler.SerialBridgeHandler;
import org.openhab.core.io.transport.serial.SerialPortManager;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
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

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Collections.unmodifiableSet(
            Stream.of(THING_TYPE_IPBRIDGE, THING_TYPE_SERIALBRIDGE, THING_TYPE_INPUTZONE, THING_TYPE_OUTPUTZONE)
                    .collect(Collectors.toSet()));

    private final NetworkAddressService networkAddressService;

    private final SerialPortManager serialPortManager;
    private @Nullable BridgeHandler bridgehandler = null;
    private @Nullable ThingUID bridgeUID;

    @Activate
    public AutopatchHandlerFactory(final @Reference SerialPortManager serialPortManager,
            final @Reference NetworkAddressService networkAddressService) {
        this.serialPortManager = serialPortManager;
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
            // This binding only supports one bridge. If the user tries to add a second bridge register an error and
            // ignore
            if (bridgehandler != null) {
                logger.warn("The Autopatch binding only supports one bridge. This bridge {} will be ignored.",
                        thing.getUID().getAsString());
                return null;
            }
            if (THING_TYPE_SERIALBRIDGE.equals(thingTypeUID)) {
                bridgehandler = new SerialBridgeHandler((Bridge) thing, serialPortManager);
            } else if (THING_TYPE_IPBRIDGE.equals(thingTypeUID)) {
                String hostaddress = networkAddressService.getPrimaryIpv4HostAddress();
                if (hostaddress == null) {
                    return null;
                } else {
                    bridgehandler = new IPBridgeHandler((Bridge) thing, hostaddress);
                }
            }
            bridgeUID = thing.getUID();
            logger.debug("AutopatchHandlerFactory created BridgeHandler for {}", thingTypeUID.getAsString());
            return bridgehandler;
        } else if (SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID)) {
            // Make sure this thing belongs to the registered Bridge
            if (bridgeUID != null && !bridgeUID.equals(thing.getBridgeUID())) {
                logger.warn("Thing: {} is being ignored because it does not belong to the registered bridge.",
                        thing.getLabel());
                return null;
            }
            ThingHandler handler;
            if (THING_TYPE_INPUTZONE.equals(thingTypeUID)) {
                handler = new InputzoneHandler(thing);
            } else if (THING_TYPE_OUTPUTZONE.equals(thingTypeUID)) {
                handler = new OutputzoneHandler(thing);
                logger.debug("AutopatchHandlerFactory created ThingHandler for {}", thing.getUID().getAsString());
                return handler;
            }

        } else {
            logger.warn("Unsupported Thing-Type: {}", thingTypeUID.getAsString());
        }

        return null;
    }
}
