package org.cubexmc.metro.bedrock

import java.util.UUID
import org.bukkit.entity.Player

/**
 * Optional Bedrock-player detection through Geyser/Floodgate without a hard API dependency.
 */
object BedrockDetector {
    @JvmStatic
    fun isBedrockPlayer(player: Player?): Boolean {
        if (player == null) {
            return false
        }
        val uuid = player.getUniqueId() ?: return false
        return isGeyserPlayer(uuid) || isFloodgatePlayer(uuid)
    }

    private fun isGeyserPlayer(uuid: UUID): Boolean {
        try {
            val apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi")
            val apiMethod = apiClass.getMethod("api")
            val api = apiMethod.invoke(null) ?: return false
            val isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID::class.java)
            return java.lang.Boolean.TRUE == isBedrockPlayer.invoke(api, uuid)
        } catch (ignored: ReflectiveOperationException) {
            return false
        } catch (ignored: LinkageError) {
            return false
        }
    }

    private fun isFloodgatePlayer(uuid: UUID): Boolean {
        try {
            val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstance = apiClass.getMethod("getInstance")
            val api = getInstance.invoke(null) ?: return false
            val isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID::class.java)
            return java.lang.Boolean.TRUE == isFloodgatePlayer.invoke(api, uuid)
        } catch (ignored: ReflectiveOperationException) {
            return false
        } catch (ignored: LinkageError) {
            return false
        }
    }
}
