package org.cubexmc.core

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlayerItemsTest {

    private val hand = mock(ItemStack::class.java)
    private val helmet = mock(ItemStack::class.java)
    private val stored = mock(ItemStack::class.java)

    private val inventory = mock(PlayerInventory::class.java)
    private val enderChest = mock(Inventory::class.java)
    private val player = mock(Player::class.java)

    init {
        `when`(player.inventory).thenReturn(inventory)
        `when`(player.enderChest).thenReturn(enderChest)
        `when`(inventory.itemInMainHand).thenReturn(hand)
        `when`(inventory.helmet).thenReturn(helmet)
        `when`(inventory.storageContents).thenReturn(arrayOf(stored, null))
        `when`(enderChest.size).thenReturn(2)
        `when`(enderChest.getItem(anyInt())).thenReturn(null)
    }

    @Test
    fun `hand slot reads the main hand and writes back through setItemInMainHand`() {
        val slot = PlayerItems.handSlot(player)

        assertEquals("hand", slot.label)
        assertSame(hand, slot.stack)

        val replacement = mock(ItemStack::class.java)
        slot.replace(replacement)
        verify(inventory).setItemInMainHand(replacement)
    }

    @Test
    fun `storage slots are indexed and keep empty slots`() {
        val slots = PlayerItems.storageSlots(player)

        assertEquals(listOf("inventory[0]", "inventory[1]"), slots.map { it.label })
        assertSame(stored, slots[0].stack)
        // 空格子必须保留:调用方可能要往里放东西
        assertNull(slots[1].stack)
    }

    @Test
    fun `storage slots write back to the matching index`() {
        val replacement = mock(ItemStack::class.java)

        PlayerItems.storageSlots(player)[1].replace(replacement)

        verify(inventory).setItem(1, replacement)
    }

    @Test
    fun `equipment covers offhand and all four armour pieces`() {
        val slots = PlayerItems.equipmentSlots(player)

        assertEquals(
            listOf(
                "equipment[offhand]",
                "equipment[helmet]",
                "equipment[chestplate]",
                "equipment[leggings]",
                "equipment[boots]",
            ),
            slots.map { it.label },
        )
        assertSame(helmet, slots[1].stack)
    }

    @Test
    fun `armour slots write back through their own setter`() {
        val replacement = mock(ItemStack::class.java)

        PlayerItems.equipmentSlots(player)[1].replace(replacement)

        verify(inventory).helmet = replacement
    }

    @Test
    fun `replacing with null clears the slot`() {
        PlayerItems.equipmentSlots(player)[4].replace(null)

        verify(inventory).boots = null
    }

    @Test
    fun `ender slots are labelled separately from the backpack`() {
        assertEquals(listOf("ender[0]", "ender[1]"), PlayerItems.enderSlots(player).map { it.label })
    }

    @Test
    fun `allSlots is storage plus equipment plus ender, and never repeats the main hand`() {
        val labels = PlayerItems.allSlots(player).map { it.label }

        assertEquals(2 + 5 + 2, labels.size)
        // 主手已经落在 inventory[...] 里,再单列一次会让调用方处理两遍同一件物品
        assertEquals(0, labels.count { it == "hand" })
    }
}
