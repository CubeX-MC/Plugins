package org.cubexmc.mountlicense.listener

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.mountlicense.MountLicensePlugin
import org.cubexmc.mountlicense.service.PdcKeys
import org.cubexmc.mountlicense.service.RegistryService

class RegistrationListener(
    private val plugin: MountLicensePlugin,
    private val registryService: RegistryService,
    private val keys: PdcKeys,
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return

        val player: Player = event.player
        if (!player.isSneaking) return

        val hand: ItemStack = player.inventory.itemInMainHand
        if (!isLicenseRole(hand)) return

        val target: Entity = event.rightClicked
        val distance = player.location.distance(target.location)
        if (distance > plugin.configManager().getMaxInteractDistance()) return

        val result = registryService.tryRegister(player, target, hand)
        if (result == RegistryService.Result.SUCCESS || result != RegistryService.Result.NO_PROFILE) {
            event.isCancelled = true
        }
    }

    private fun isLicenseRole(item: ItemStack?): Boolean {
        if (item == null) return false
        val meta = item.itemMeta ?: return false
        val role = meta.persistentDataContainer.get(keys.itemRole(), PersistentDataType.STRING)
        return PdcKeys.ITEM_ROLE_LICENSE == role
    }
}
