package com.ascvnd.maceroulette;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small helper for turning legacy/hex colour strings into Adventure components.
 * Supports both legacy codes (&a, &l, ...) and hex codes in the form &#RRGGBB.
 */
public final class Txt {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private Txt() {
    }

    /** Parse a string with &-codes and &#RRGGBB hex codes into a component. */
    public static Component c(String s) {
        // Disable the default italic that some clients render so titles look clean.
        return LEGACY.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }
}
