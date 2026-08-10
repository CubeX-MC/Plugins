package org.cubexmc.metro.util

import org.bukkit.entity.Player
import java.util.UUID

/**
 * Optional Bedrock-player detection through Geyser/Floodgate without a hard API dependency.
 */
object BedrockPlayerUtil {

    @JvmStatic
    fun isBedrockPlayer(player: Player?): Boolean {
        if (player == null) {
            return false
        }
        // 测试里的 mock Player 会返回 null uuid，保持 Java 版本的宽容语义
        val uuid: UUID? = player.uniqueId
        return isGeyserPlayer(uuid) || isFloodgatePlayer(uuid)
    }

    private fun isGeyserPlayer(uuid: UUID?): Boolean =
        try {
            val apiClass = Class.forName("org.geysermc.geyser.api.GeyserApi")
            val api = apiClass.getMethod("api").invoke(null)
            if (api == null) {
                false
            } else {
                val isBedrockPlayer = apiClass.getMethod("isBedrockPlayer", UUID::class.java)
                java.lang.Boolean.TRUE == isBedrockPlayer.invoke(api, uuid)
            }
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }

    private fun isFloodgatePlayer(uuid: UUID?): Boolean =
        try {
            val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val api = apiClass.getMethod("getInstance").invoke(null)
            if (api == null) {
                false
            } else {
                val isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID::class.java)
                java.lang.Boolean.TRUE == isFloodgatePlayer.invoke(api, uuid)
            }
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: LinkageError) {
            false
        }
}
