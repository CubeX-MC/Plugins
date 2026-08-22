package org.cubexmc.gui

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * 用 [filler] 铺满界面里**还空着**的格子，已放东西的格子一律不动。
 *
 * 下沉自 Metro / Railway 的 `MainMenuView` 与 EcoBalancer 的 `fillBackground` ——
 * 三处（Metro 与 Railway 同源）是**逐字相同**的 5 行循环。
 *
 * 摆完全部按钮**之后**再调用，否则会把还没放的位置提前占掉。
 */
fun Inventory.fillEmpty(filler: ItemStack) {
    for (slot in 0 until size) {
        if (getItem(slot) == null) {
            setItem(slot, filler)
        }
    }
}

/** 同 [Inventory.fillEmpty]，作用于本菜单的界面。 */
fun Menu.fillEmpty(filler: ItemStack) {
    inventory.fillEmpty(filler)
}
