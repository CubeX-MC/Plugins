package org.cubexmc.metro.bedrock;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.entity.Player;

/**
 * Optional Bedrock-player detection through Geyser/Floodgate without a hard API dependency.
 */
final class BedrockDetector {

    private BedrockDetector() {
    }

    static boolean isBedrockPlayer(Player player) {
        if (player == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        return isGeyserPlayer(uuid) || isFloodgatePlayer(uuid);
    }

    private static boolean isGeyserPlayer(UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            Method apiMethod = apiClass.getMethod("api");
            Object api = apiMethod.invoke(null);
            if (api == null) {
                return false;
            }
            Method isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID.class);
            return Boolean.TRUE.equals(isBedrockPlayer.invoke(api, uuid));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean isFloodgatePlayer(UUID uuid) {
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Method getInstance = apiClass.getMethod("getInstance");
            Object api = getInstance.invoke(null);
            if (api == null) {
                return false;
            }
            Method isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            return Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, uuid));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return false;
        }
    }
}
