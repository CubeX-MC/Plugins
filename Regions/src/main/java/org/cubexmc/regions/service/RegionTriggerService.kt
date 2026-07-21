package org.cubexmc.regions.service

import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.config.PaperText
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import java.time.Duration
import java.util.Locale
import kotlin.random.Random

class RegionTriggerService(private val plugin: RegionsPlugin) {
    fun fire(trigger: RegionTrigger, player: Player, region: RegionDefinition) {
        val blocks = region.triggers[trigger] ?: return
        val limit = plugin.config.getInt("safety.max-actions-per-trigger", 16).coerceAtLeast(1)
        var executed = 0
        for (block in blocks) {
            if (block.execution == TriggerExecution.PRIMARY_REGION && !isPrimaryRegion(player, region)) {
                continue
            }
            val actions = if (matches(player, region, block)) block.thenActions else block.elseActions
            for (action in actions) {
                if (executed >= limit) {
                    plugin.logger.warning("Trigger ${trigger.key} in region ${region.id} hit max action limit $limit.")
                    return
                }
                execute(action, player, region)
                executed++
            }
        }
    }

    private fun matches(player: Player, region: RegionDefinition, block: ActionBlockConfig): Boolean =
        block.conditions.all { condition -> evaluate(condition, player, region) }

    private fun isPrimaryRegion(player: Player, region: RegionDefinition): Boolean {
        val active = plugin.sessions().activeSessions(player.uniqueId)
            .mapNotNull { plugin.regions().find(it.regionId) }
        return plugin.overlaps().resolve(active).primaryTriggerRegion?.id == region.id
    }

    private fun evaluate(condition: ConditionConfig, player: Player, region: RegionDefinition): Boolean {
        val matched = when (condition.type.lowercase(Locale.ROOT)) {
            "permission" -> player.hasPermission(condition.values["value"] ?: condition.values["node"] ?: "")
            "region" -> region.id.equals(condition.values["value"], ignoreCase = true)
            "mode" -> region.mode?.type.equals(condition.values["value"], ignoreCase = true)
            "chance" -> {
                val value = condition.values["value"]?.toDoubleOrNull()
                    ?: condition.values["percent"]?.toDoubleOrNull()
                    ?: 100.0
                Random.nextDouble(100.0) < value.coerceIn(0.0, 100.0)
            }
            "has_union" -> plugin.unions().active()?.getUnion(player.uniqueId) != null
            "union" -> {
                val expected = condition.values["value"] ?: condition.values["id"]
                expected != null && plugin.unions().active()?.getUnion(player.uniqueId)?.id.equals(expected, ignoreCase = true)
            }
            "metadata" -> {
                val key = condition.values["key"]
                key != null && region.metadata[key] == condition.values["value"]
            }
            "session_metadata" -> {
                val key = condition.values["key"]
                key != null && plugin.sessions().activeSession(player.uniqueId, region.id)?.metadata?.get(key) == condition.values["value"]
            }
            else -> false
        }
        return if (condition.negated) !matched else matched
    }

    private fun execute(action: ActionConfig, player: Player, region: RegionDefinition) {
        when (action.type.lowercase(Locale.ROOT)) {
            "message" -> message(action, player, region)
            "title" -> title(action, player, region)
            "sound" -> sound(action, player)
            "effect_apply" -> effectApply(action, player, region)
            "effect_clear" -> plugin.effects().cleanupRegion(player, region.id, "trigger-effect-clear")
            "broadcast" -> broadcast(action, player, region)
            "console_command" -> consoleCommand(action, player, region)
            "player_command" -> playerCommand(action, player, region)
            "teleport" -> teleport(action, player, region)
            "heal" -> heal(action, player)
            "feed" -> feed(action, player)
            "extinguish" -> player.fireTicks = 0
            "give_item" -> giveItem(action, player)
            "take_item" -> takeItem(action, player)
            "set_metadata" -> setMetadata(action, player, region)
            "clear_metadata" -> clearMetadata(action, player, region)
            "cleanup_region" -> plugin.sessions().leave(player, region.id, "trigger-cleanup-region")
            "mode_command" -> modeCommand(action, player, region)
            else -> plugin.logger.fine("Action ${action.type} is registered but has no runtime yet.")
        }
    }

    private fun message(action: ActionConfig, player: Player, region: RegionDefinition) {
        val text = action.values["text"] ?: action.values["message"] ?: return
        plugin.lang().sendRaw(player, replace(text, player, region))
    }

    private fun title(action: ActionConfig, player: Player, region: RegionDefinition) {
        val title = replace(action.values["title"] ?: "", player, region)
        val subtitle = replace(action.values["subtitle"] ?: "", player, region)
        val fadeIn = action.values["fade-in"]?.toIntOrNull() ?: 10
        val stay = action.values["stay"]?.toIntOrNull() ?: 40
        val fadeOut = action.values["fade-out"]?.toIntOrNull() ?: 10
        player.showTitle(
            Title.title(
                PaperText.parse(title),
                PaperText.parse(subtitle),
                Title.Times.times(
                    Duration.ofMillis(fadeIn.coerceAtLeast(0) * TICK_MILLIS),
                    Duration.ofMillis(stay.coerceAtLeast(0) * TICK_MILLIS),
                    Duration.ofMillis(fadeOut.coerceAtLeast(0) * TICK_MILLIS),
                ),
            ),
        )
    }

    private fun sound(action: ActionConfig, player: Player) {
        val name = action.values["sound"] ?: action.values["name"] ?: return
        val sound = resolveSound(name) ?: return
        val volume = action.values["volume"]?.toFloatOrNull() ?: 1.0f
        val pitch = action.values["pitch"]?.toFloatOrNull() ?: 1.0f
        player.playSound(player.location, sound, volume, pitch)
    }

    private fun effectApply(action: ActionConfig, player: Player, region: RegionDefinition) {
        val effectType = action.values["effect"] ?: action.values["effect-type"] ?: return
        val values = LinkedHashMap(action.values)
        values.remove("effect")
        values.remove("effect-type")
        val scope = when (values.remove("scope")?.lowercase(Locale.ROOT)) {
            "timed" -> EffectScope.TIMED
            "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
            else -> EffectScope.WHILE_INSIDE
        }
        plugin.effects().apply(player, region, EffectConfig(effectType, scope, values))
    }

    private fun consoleCommand(action: ActionConfig, player: Player, region: RegionDefinition) {
        val command = replace(action.values["command"] ?: return, player, region).removePrefix("/")
        val commandRoot = command.substringBefore(' ').lowercase(Locale.ROOT)
        plugin.audit().record(
            null,
            region.id,
            "action.console-command.execute",
            details = mapOf(
                "command-root" to commandRoot,
                "player" to player.uniqueId.toString(),
            ),
        )
        plugin.regionScheduler().runGlobal(Runnable {
            plugin.server.dispatchCommand(plugin.server.consoleSender, command)
        })
    }

    private fun playerCommand(action: ActionConfig, player: Player, region: RegionDefinition) {
        val command = replace(action.values["command"] ?: return, player, region).removePrefix("/")
        player.performCommand(command)
    }

    private fun broadcast(action: ActionConfig, player: Player, region: RegionDefinition) {
        val text = replace(action.values["text"] ?: action.values["message"] ?: return, player, region)
        for (target in plugin.server.onlinePlayers.toList()) {
            if (plugin.sessions().activeSession(target.uniqueId, region.id) == null) {
                continue
            }
            plugin.regionScheduler().runAtEntity(target, Runnable {
                plugin.lang().sendRaw(target, text)
            })
        }
    }

    private fun teleport(action: ActionConfig, player: Player, region: RegionDefinition) {
        val raw = action.values["location"] ?: action.values["value"] ?: action.values["to"] ?: return
        val location = parseLocation(raw, region)
            ?: return
        plugin.regionScheduler().teleportAsync(player, location)
    }

    private fun heal(action: ActionConfig, player: Player) {
        val amount = action.values["amount"]?.toDoubleOrNull()
        val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: player.health
        if (amount == null) {
            player.health = maxHealth
            return
        }
        player.health = (player.health + amount).coerceAtMost(maxHealth)
    }

    private fun feed(action: ActionConfig, player: Player) {
        val amount = action.values["amount"]?.toIntOrNull()
        player.foodLevel = if (amount == null) 20 else (player.foodLevel + amount).coerceAtMost(20)
        player.saturation = action.values["saturation"]?.toFloatOrNull() ?: player.saturation
    }

    private fun giveItem(action: ActionConfig, player: Player) {
        val item = parseItem(action.values["item"] ?: action.values["value"] ?: return) ?: return
        player.inventory.addItem(item)
    }

    private fun takeItem(action: ActionConfig, player: Player) {
        val item = parseItem(action.values["item"] ?: action.values["value"] ?: return) ?: return
        player.inventory.removeItem(item)
    }

    private fun setMetadata(action: ActionConfig, player: Player, region: RegionDefinition) {
        val key = action.values["key"] ?: return
        val value = replace(action.values["value"] ?: "true", player, region)
        plugin.sessions().setMetadata(player, region.id, key, value)
    }

    private fun clearMetadata(action: ActionConfig, player: Player, region: RegionDefinition) {
        val key = action.values["key"] ?: return
        plugin.sessions().clearMetadata(player, region.id, key)
    }

    private fun modeCommand(action: ActionConfig, player: Player, region: RegionDefinition) {
        when ((action.values["command"] ?: action.values["value"] ?: "").lowercase(Locale.ROOT)) {
            "ready" -> {
                if (plugin.raceModes().isRaceMode(region)) {
                    plugin.raceModes().ready(player, region.id)
                } else if (plugin.roundModes().isRoundMode(region)) {
                    plugin.roundModes().ready(player, region.id)
                } else {
                    plugin.combatModes().ready(player, region.id)
                }
            }
            "end", "stop" -> {
                if (plugin.roundModes().isRoundMode(region)) {
                    plugin.roundModes().forceEnd(region.id, "trigger-mode-command")
                } else if (plugin.raceModes().isRaceMode(region)) {
                    plugin.raceModes().forceEnd(region.id, "trigger-mode-command")
                } else {
                    plugin.combatModes().forceEnd(region.id, "trigger-mode-command")
                }
            }
        }
    }

    private fun parseLocation(value: String, region: RegionDefinition): Location? {
        val raw = when (value.lowercase(Locale.ROOT)) {
            "respawn", "outside" -> region.mode?.values?.get(value.lowercase(Locale.ROOT)) ?: return null
            else -> value
        }
        val parts = raw.split(',')
        if (parts.size < 4) {
            return null
        }
        val world = plugin.server.getWorld(parts[0].trim()) ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0.0f
        val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0.0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun parseItem(value: String): ItemStack? {
        val parts = value.split(':')
        val material = Material.matchMaterial(parts[0].trim().uppercase(Locale.ROOT)) ?: return null
        val amount = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        return ItemStack(material, amount)
    }

    private fun resolveSound(value: String): Sound? {
        val normalized = value.lowercase(Locale.ROOT)
        val explicit = NamespacedKey.fromString(normalized)
        if (explicit != null) {
            Registry.SOUND_EVENT.get(explicit)?.let { return it }
        }
        return Registry.SOUND_EVENT.get(NamespacedKey.minecraft(normalized.replace('_', '.')))
    }

    private fun replace(input: String, player: Player, region: RegionDefinition): String {
        val metadata = plugin.sessions().activeSession(player.uniqueId, region.id)?.metadata ?: emptyMap()
        return input
            .replace("{player}", player.name)
            .replace("{uuid}", player.uniqueId.toString())
            .replace("{region}", region.id)
            .replace("{region_name}", region.name)
            .replace("{mode}", region.mode?.type ?: "none")
            .replace("{union}", plugin.unions().active()?.getUnion(player.uniqueId)?.name ?: "")
            .replace("{rank}", metadata["race_rank"] ?: "")
            .replace("{checkpoint}", metadata["race_checkpoint"] ?: "")
            .replace("{race_time_ms}", metadata["race_time_ms"] ?: "")
            .replace("{round_role}", metadata["round_role"] ?: "")
            .replace("{found_by}", metadata["round_found_by"] ?: "")
    }

    private companion object {
        const val TICK_MILLIS = 50L
    }
}
