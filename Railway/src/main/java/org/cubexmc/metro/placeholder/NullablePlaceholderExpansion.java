package org.cubexmc.metro.placeholder;

import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.Nullable;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

/**
 * Widens PlaceholderAPI's annotated params contract so the Kotlin implementation
 * can preserve Railway's existing null-tolerant request handling.
 */
public abstract class NullablePlaceholderExpansion extends PlaceholderExpansion {

    @Override
    public abstract String onRequest(OfflinePlayer player, @Nullable String params);
}
