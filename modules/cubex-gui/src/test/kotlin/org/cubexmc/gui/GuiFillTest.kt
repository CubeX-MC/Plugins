package org.cubexmc.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class GuiFillTest {

    /** 用数组给 mock 的 Inventory 撑出真实的读写行为。 */
    private fun inventoryOf(size: Int): Pair<Inventory, Array<ItemStack?>> {
        val slots = arrayOfNulls<ItemStack>(size)
        val inventory = mock(Inventory::class.java)
        `when`(inventory.size).thenReturn(size)
        `when`(inventory.getItem(anyInt())).thenAnswer { slots[it.getArgument<Int>(0)] }
        `when`(inventory.setItem(anyInt(), org.mockito.ArgumentMatchers.any())).thenAnswer {
            slots[it.getArgument<Int>(0)] = it.getArgument(1)
            null
        }
        return inventory to slots
    }

    @Test
    fun `fills every slot of an empty inventory`() {
        val (inventory, slots) = inventoryOf(9)
        val filler = mock(ItemStack::class.java)

        inventory.fillEmpty(filler)

        assertEquals(9, slots.count { it === filler })
    }

    @Test
    fun `never overwrites a slot that already holds something`() {
        val (inventory, slots) = inventoryOf(9)
        val button = mock(ItemStack::class.java)
        val filler = mock(ItemStack::class.java)
        slots[4] = button

        inventory.fillEmpty(filler)

        assertSame(button, slots[4])
        assertEquals(8, slots.count { it === filler })
    }

    @Test
    fun `leaves a fully occupied inventory untouched`() {
        val (inventory, slots) = inventoryOf(3)
        val button = mock(ItemStack::class.java)
        val filler = mock(ItemStack::class.java)
        slots.indices.forEach { slots[it] = button }

        inventory.fillEmpty(filler)

        assertEquals(3, slots.count { it === button })
    }

    @Test
    fun `an empty-sized inventory is a no-op`() {
        val (inventory, slots) = inventoryOf(0)

        inventory.fillEmpty(mock(ItemStack::class.java))

        assertEquals(0, slots.size)
    }
}
