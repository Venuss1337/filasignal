package dev.wiktorfila.filasignal.fabric.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Links from screens to the FilaSignal documentation. Links always go through Minecraft's confirmation screen,
 * which lets the player open or copy the address; nothing opens a browser by itself.
 */
final class HelpLinks {
    static final String SITE = "https://venuss1337.github.io/filasignal/";
    static final String EDITOR = SITE + "minecraft/guides/editor/";
    static final String FIRST_PROJECT = SITE + "minecraft/getting-started/first-automation/";

    // Device kinds whose page name differs from the kind name.
    private static final Map<String, String> PAGES = Map.of(
            "REPEATER", "network-repeater", "INTERFACE", "network-interface", "DISPLAY", "display-module",
            "ALARM", "alarm-module", "COMPUTER", "computer");

    private HelpLinks() {}

    /** The hardware reference page for a device kind such as {@code ENTITY_DETECTOR}. */
    static String device(String kind) {
        String page = PAGES.getOrDefault(kind, kind.toLowerCase(Locale.ROOT).replace('_', '-'));
        return SITE + "minecraft/hardware/" + page + "/";
    }

    /** Asks the player to confirm before opening or copying the link, then returns to the current screen. */
    static void open(String url) {
        var minecraft = Minecraft.getInstance();
        ConfirmLinkScreen.confirmLinkNow(minecraft.gui.screen(), URI.create(url));
    }
}
