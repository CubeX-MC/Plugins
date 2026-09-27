package org.cubexmc.gui

import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

/**
 * A single GUI slot: an icon plus the action to run when it is clicked.
 * Ported from the AuctionHouse `InventoryButton` pattern — each button owns its own click
 * handler, so screens no longer need a central `when (slot)` dispatch.
 */
public class InventoryButton(public val icon: ItemStack, public val onClick: (InventoryClickEvent) -> Unit = {})
