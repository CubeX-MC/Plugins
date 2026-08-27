package org.cubexmc.gui.chat

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

/**
 * 测试类路径是 spigot-api，**没有** `io.papermc.paper.event.player.AsyncChatEvent`——
 * 正好就是要验证的降级场景：Spigot 上必须安静跳过，而不是抛异常打断插件启用。
 */
class ModernChatBridgeTest {

    @Test
    fun `reports unavailable when the paper chat event is absent`() {
        assertFalse(ModernChatBridge.isAvailable)
    }

    @Test
    fun `register returns null instead of throwing, and never touches the plugin`() {
        val plugin = mock(Plugin::class.java)

        val listener = ModernChatBridge.register(plugin) { _, _ -> true }

        assertNull(listener)
        verifyNoInteractions(plugin)
    }

    @Test
    fun `unregister tolerates a null listener`() {
        ModernChatBridge.unregister(null)
    }
}
