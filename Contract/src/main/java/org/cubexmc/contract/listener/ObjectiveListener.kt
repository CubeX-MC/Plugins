package org.cubexmc.contract.listener

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.service.ObjectiveProgressUpdate
import org.cubexmc.contract.util.Text
import java.util.Locale

class ObjectiveListener(private val plugin: ContractPlugin) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        val result = event.recipe.result
        record(player, ObjectiveType.CRAFT_ITEM, result.type.name, result.amount.coerceAtLeast(1))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        record(event.player, ObjectiveType.BLOCK_BREAK, event.block.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) {
            return
        }
        val caught = event.caught
        val target = if (caught is Item) caught.itemStack.type.name else "ANY"
        record(event.player, ObjectiveType.FISH, target, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        record(event.player, ObjectiveType.BLOCK_PLACE, event.blockPlaced.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (event.entity is Player) {
            record(killer, ObjectiveType.KILL_PLAYER, (event.entity as Player).name, 1)
            return
        }
        record(killer, ObjectiveType.KILL_ENTITY, event.entity.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        record(event.player, ObjectiveType.CONSUME_ITEM, event.item.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        record(event.enchanter, ObjectiveType.ENCHANT_ITEM, event.item.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShear(event: PlayerShearEntityEvent) {
        record(event.player, ObjectiveType.SHEAR, event.entity.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        record(player, ObjectiveType.BREED, event.entity.type.name, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val player = event.owner as? Player ?: return
        record(player, ObjectiveType.TAME, event.entity.type.name, 1)
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val message = event.message
        plugin.scheduler().runAtEntity(event.player, Runnable {
            record(event.player, ObjectiveType.CHAT, message, 1)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val command = event.message.removePrefix("/").trim().lowercase(Locale.ROOT)
        if (command.isBlank()) {
            return
        }
        record(event.player, ObjectiveType.RUN_COMMAND, command, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != null && event.hand != EquipmentSlot.HAND) {
            return
        }
        val block = event.clickedBlock
        if (block != null) {
            record(event.player, ObjectiveType.BLOCK_INTERACT, block.type.name, 1)
        }
        val item = event.item ?: return
        record(event.player, ObjectiveType.USE_ITEM, item.type.name, 1)
    }

    private fun record(player: Player, type: ObjectiveType, target: String, amount: Int) {
        val updates = plugin.contracts().recordObjectiveProgress(player, type, target, amount)
        for (update in updates) {
            notify(player, update)
        }
    }

    private fun notify(player: Player, update: ObjectiveProgressUpdate) {
        val contract = update.contract()
        val objective = contract.objective() ?: return
        if (update.completed()) {
            val result = update.result()
            if (result.success()) {
                player.sendMessage(Text.color("&#69DB7C目标完成: &#FFFFFF${contract.title()} &#69DB7C已由系统自动结算。"))
            } else {
                player.sendMessage(Text.color("&#E63946目标已达成,但自动结算失败: &#FFFFFF${result.reason()}"))
            }
            return
        }
        player.spigot().sendMessage(
            ChatMessageType.ACTION_BAR,
            TextComponent(Text.color("&#69DB7C${contract.title()} &#CFD8DC${objective.progressText()}")),
        )
    }
}
