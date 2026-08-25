package org.cubexmc.core

import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayerStateLeaseStackTest {
    @Test
    fun `removing a buried lease preserves the top and final removal restores the original base`() {
        val player = mock(Player::class.java)
        val pdc = mock(PersistentDataContainer::class.java)
        var encoded: String? = null
        var current = "1.0"
        `when`(player.persistentDataContainer).thenReturn(pdc)
        `when`(pdc.get(any(), org.mockito.ArgumentMatchers.eq(PersistentDataType.STRING))).thenAnswer { encoded }
        doAnswer { invocation -> encoded = invocation.getArgument(2); null }
            .`when`(pdc).set(any(), org.mockito.ArgumentMatchers.eq(PersistentDataType.STRING), any(String::class.java))
        doAnswer { encoded = null; null }.`when`(pdc).remove(any())

        assertTrue(PlayerStateLeaseStack.apply(player, "scale", "statecharge", "0.5", { current }, { current = it }))
        assertTrue(PlayerStateLeaseStack.apply(player, "scale", "regions", "2.0", { current }, { current = it }))
        assertEquals("2.0", current)

        assertTrue(PlayerStateLeaseStack.remove(player, "scale", "statecharge") { current = it })
        assertEquals("2.0", current)
        assertTrue(PlayerStateLeaseStack.remove(player, "scale", "regions") { current = it })
        assertEquals("1.0", current)
        assertFalse(PlayerStateLeaseStack.hasLeases(player, "scale"))
    }
}
