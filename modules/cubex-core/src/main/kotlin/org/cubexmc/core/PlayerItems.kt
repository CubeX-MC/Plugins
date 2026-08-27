package org.cubexmc.core

import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory

/**
 * 玩家身上某一个物品位置：**读得到，也写得回去**。
 *
 * 写回是关键——主手、副手、四件盔甲、背包下标、末影箱各有各的 setter，
 * 调用方拿到 [ItemSlot] 就不必再关心"这一格该用哪个 API 放回去"。
 */
class ItemSlot(
    /** 人类可读的位置标识，例如 `hand`、`equipment[helmet]`、`inventory[12]`。 */
    val label: String,
    val stack: ItemStack?,
    private val setter: (ItemStack?) -> Unit,
) {
    /** 把这一格换成 [stack]；传 null 表示清空。 */
    fun replace(stack: ItemStack?) {
        setter(stack)
    }
}

/**
 * 枚举玩家身上的物品位置。
 *
 * 下沉自 Clarity 的 `ClarityService`（清理别的插件遗留的物品元数据）。
 * 故意**不**把 Clarity 的 `ItemScope` 枚举一起搬上来——那是它命令行的语义；
 * 这里只出可组合的收集器，调用方自己决定要哪几类。
 *
 * 标签格式与 Clarity 下沉前逐字一致，因为它会出现在命令输出里。
 */
object PlayerItems {

    /** 主手。 */
    fun handSlot(player: Player): ItemSlot {
        val inventory = player.inventory
        return ItemSlot("hand", inventory.itemInMainHand) { inventory.setItemInMainHand(it) }
    }

    /**
     * 背包存储区（**不含**装备栏与副手）。
     *
     * 用 `storageContents` 而不是 `contents`：后者在 [PlayerInventory] 上还会带出
     * 盔甲与副手，与 [equipmentSlots] 重复。
     */
    fun storageSlots(player: Player): List<ItemSlot> {
        val inventory = player.inventory
        return inventory.storageContents.mapIndexed { index, stack ->
            ItemSlot("inventory[$index]", stack) { inventory.setItem(index, it) }
        }
    }

    /** 副手与四件盔甲。 */
    fun equipmentSlots(player: Player): List<ItemSlot> {
        val inventory = player.inventory
        return listOf(
            ItemSlot("equipment[offhand]", inventory.itemInOffHand) { inventory.setItemInOffHand(it) },
            ItemSlot("equipment[helmet]", inventory.helmet) { inventory.helmet = it },
            ItemSlot("equipment[chestplate]", inventory.chestplate) { inventory.chestplate = it },
            ItemSlot("equipment[leggings]", inventory.leggings) { inventory.leggings = it },
            ItemSlot("equipment[boots]", inventory.boots) { inventory.boots = it },
        )
    }

    /** 末影箱。 */
    fun enderSlots(player: Player): List<ItemSlot> = inventorySlots("ender", player.enderChest)

    /** 任意容器（箱子、潜影盒界面等）。 */
    fun inventorySlots(label: String, inventory: Inventory): List<ItemSlot> =
        (0 until inventory.size).map { index ->
            ItemSlot("$label[$index]", inventory.getItem(index)) { inventory.setItem(index, it) }
        }

    /** 背包 + 装备 + 末影箱。**不含**主手——它已经在背包存储区里了。 */
    fun allSlots(player: Player): List<ItemSlot> =
        storageSlots(player) + equipmentSlots(player) + enderSlots(player)
}
