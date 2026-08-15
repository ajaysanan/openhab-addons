/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.lutron.internal.hw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-model button and LED layout data for legacy HomeWorks wired seeTouch keypads, keyed by the
 * model identifier used in the {@code hwkeypad} thing-type's {@code model} configuration parameter.
 *
 * @author Ajay Sanan - Initial contribution
 */
public class HwKeypadModelData {

    public static final int RAISE_BUTTON = 23;
    public static final int LOWER_BUTTON = 24;

    /**
     * Button/LED layout for a single keypad model.
     *
     * @param ledButtons button numbers that have an associated LED
     * @param nonLedButtons button numbers that do not have an associated LED
     * @param hasRaiseLower whether this model has a raise/lower rocker (buttons 23/24, no LED)
     */
    public record KeypadLayout(int[] ledButtons, int[] nonLedButtons, boolean hasRaiseLower) {

        public List<Integer> getButtonNumbers() {
            List<Integer> buttons = new ArrayList<>();
            for (int b : ledButtons) {
                buttons.add(b);
            }
            for (int b : nonLedButtons) {
                buttons.add(b);
            }
            if (hasRaiseLower) {
                buttons.add(RAISE_BUTTON);
                buttons.add(LOWER_BUTTON);
            }
            Collections.sort(buttons);
            return buttons;
        }

        public List<Integer> getLedButtonNumbers() {
            List<Integer> leds = new ArrayList<>();
            for (int b : ledButtons) {
                leds.add(b);
            }
            return leds;
        }
    }

    private static final Map<String, KeypadLayout> MODEL_DATA = new LinkedHashMap<>();
    static {
        MODEL_DATA.put("1B", new KeypadLayout(new int[] { 4 }, new int[] {}, false));
        MODEL_DATA.put("2B", new KeypadLayout(new int[] { 3, 5 }, new int[] {}, false));
        MODEL_DATA.put("3B", new KeypadLayout(new int[] { 2, 4, 6 }, new int[] {}, false));
        MODEL_DATA.put("4B", new KeypadLayout(new int[] { 1, 3, 5, 7 }, new int[] {}, false));
        MODEL_DATA.put("5B", new KeypadLayout(new int[] { 2, 3, 4, 5, 6 }, new int[] {}, false));
        MODEL_DATA.put("6B", new KeypadLayout(new int[] { 1, 2, 3, 4, 5, 6 }, new int[] {}, false));
        MODEL_DATA.put("7B", new KeypadLayout(new int[] { 1, 2, 3, 4, 5, 6, 7 }, new int[] {}, false));
        MODEL_DATA.put("3BRL", new KeypadLayout(new int[] { 1, 3, 5 }, new int[] {}, true));
        MODEL_DATA.put("4FS", new KeypadLayout(new int[] { 1, 2, 3, 4 }, new int[] { 6, 7 }, false));
        MODEL_DATA.put("4S", new KeypadLayout(new int[] { 1, 2, 3, 4 }, new int[] { 6 }, true));
        MODEL_DATA.put("4SIR", new KeypadLayout(new int[] { 1, 2, 3, 4 }, new int[] {}, true));
        MODEL_DATA.put("5FS", new KeypadLayout(new int[] { 1, 2, 3, 4, 5, 7 }, new int[] {}, false));
        MODEL_DATA.put("5BRL", new KeypadLayout(new int[] { 1, 2, 3, 4, 5 }, new int[] {}, true));
        MODEL_DATA.put("6BRL", new KeypadLayout(new int[] { 1, 2, 3, 4, 5, 6 }, new int[] {}, true));
        MODEL_DATA.put(HwKeypadConfig.DEFAULT_MODEL,
                new KeypadLayout(new int[] { 1, 2, 3, 4, 5, 6, 7 }, new int[] {}, true)); // Generic: maximal fallback
    }

    public static KeypadLayout getLayout(String model) {
        KeypadLayout layout = MODEL_DATA.get(model);
        return layout != null ? layout : MODEL_DATA.get(HwKeypadConfig.DEFAULT_MODEL);
    }
}