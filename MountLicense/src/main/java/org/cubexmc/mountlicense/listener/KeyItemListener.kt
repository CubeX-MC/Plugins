package org.cubexmc.mountlicense.listener

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.lang.LanguageManager
import org.cubexmc.mountlicense.service.ItemFactory
import org.cubexmc.mountlicense.service.OwnershipService
import org.cubexmc.mountlicense.service.RecallService
import java.util.UUID

class KeyItemListener(
    private val plugin: MountLicensePlugin,
    private val itemFactory: ItemFactory,
    private val ownership: OwnershipService,
    private val recall: RecallService,
    private val lang: LanguageManager,
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAirInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR) return
        val player = event.player
        val item: ItemStack = player.inventory.itemInMainHand
        if (!itemFactory.isKey(item)) return

        val bound: UUID? = itemFactory.readBoundVehicleId(item)
        val result = if (bound != null) recall.recallById(player, bound) else recall.recallNearest(player)

        recall.sendResultMessage(player, result, bound)
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMountInteract(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player: Player = event.player
        if (!player.isSneaking) return

        val item: ItemStack = player.inventory.itemInMainHand
        if (!itemFactory.isKey(item)) return

        val target: Entity = event.rightClicked
        val vehicleId = ownership.readVehicleId(target)
        if (vehicleId == null) {
            lang.send(player, "key.target_not_registered")
            event.isCancelled = true
            return
        }
        if (!ownership.isOwner(target, player.uniqueId) && !player.hasPermission(OwnershipService.BYPASS_PERMISSION)) {
            lang.send(player, "key.target_not_owner")
            event.isCancelled = true
            return
        }

        val record = plugin.vehicleIndex().byId(vehicleId)
        val shortLabel = record?.shortId() ?: vehicleId.toString().substring(0, 8)
        itemFactory.bindKey(item, vehicleId, shortLabel)

        val ph = HashMap<String, String>()
        ph["short_id"] = shortLabel
        ph["entity_type"] = target.type.name
        lang.send(player, "key.bound", ph)
        event.isCancelled = true
    }
}
