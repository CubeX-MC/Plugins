package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import java.util.Locale

/** Flag, effect and trigger editing for a single region. */
internal class RegionRuleMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun openFlags(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.FLAGS, region.id),
            54,
            text.component("gui.flag.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        for ((slot, flag) in GuiSlots.FLAGS) {
            // A flag with no runtime never appears, so the page cannot promise an unenforced rule.
            if (!plugin.flags().isRegistered(flag)) continue
            inventory.setItem(slot, items.flag(flag, region.flags[flag]?.value ?: "pass"))
        }
        inventory.setItem(48, text.item(Material.PAPER, "gui.flag.advanced"))
        inventory.setItem(49, items.back())
        player.openInventory(inventory)
    }

    fun clickFlags(player: Player, regionId: String, slot: Int) {
        if (slot == 49) return gui.openDetail(player, regionId)
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (slot == 48) return promptFlag(player, region)
        val flag = GuiSlots.FLAGS[slot] ?: return
        if (!plugin.flags().isRegistered(flag)) return
        val flags = LinkedHashMap(region.flags)
        val next = GuiValues.nextFlagValue(flags[flag]?.value ?: "pass")
        if (next == "pass") flags.remove(flag) else flags[flag] = FlagConfig(flag, next)
        gui.saveAndReopen(player, region.copy(flags = flags)) { openFlags(player, regionId) }
    }

    private fun promptFlag(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.flag") { raw ->
            val args = GuiValues.splitArgs(raw)
            if (args.size < 2) {
                text.send(player, "gui.flag.usage")
                openFlags(player, region.id)
                return@promptLine
            }
            val flags = LinkedHashMap(region.flags)
            val flag = args[0].lowercase(Locale.ROOT)
            val value = args[1].lowercase(Locale.ROOT)
            if (value == "pass" || value == "clear") {
                flags.remove(flag)
            } else {
                flags[flag] = FlagConfig(flag, value, GuiValues.parsePairs(args.drop(2)))
            }
            gui.saveAndReopen(player, region.copy(flags = flags)) { openFlags(player, region.id) }
        }
    }

    fun openEffects(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.EFFECTS, region.id),
            54,
            text.component("gui.effect.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        for ((index, effect) in region.effects.take(27).withIndex()) {
            inventory.setItem(index + 9, items.effect(index, effect))
        }
        inventory.setItem(37, text.item(Material.AMETHYST_SHARD, "gui.effect.preset.small"))
        inventory.setItem(38, text.item(Material.ENDER_PEARL, "gui.effect.preset.large"))
        inventory.setItem(39, text.item(Material.FEATHER, "gui.effect.preset.flight"))
        inventory.setItem(40, text.item(Material.SUGAR, "gui.effect.preset.speed"))
        inventory.setItem(41, text.item(Material.GLASS_BOTTLE, "gui.effect.preset.invisibility"))
        inventory.setItem(43, text.item(Material.LAVA_BUCKET, "gui.effect.clear"))
        inventory.setItem(48, text.item(Material.PAPER, "gui.effect.advanced"))
        inventory.setItem(49, items.back())
        player.openInventory(inventory)
    }

    fun clickEffects(player: Player, regionId: String, slot: Int) {
        if (slot == 49) return gui.openDetail(player, regionId)
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (slot == 48) return promptEffect(player, region)
        val effects = ArrayList(region.effects)
        if (slot in 9..35) {
            val index = slot - 9
            if (index < effects.size) {
                effects.removeAt(index)
                gui.saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, regionId) }
            }
            return
        }
        if (slot == 43) {
            return gui.saveAndReopen(player, region.copy(effects = emptyList())) { openEffects(player, regionId) }
        }
        val effect = when (slot) {
            37 -> EffectConfig("scale", EffectScope.WHILE_INSIDE, mapOf("value" to "0.35"))
            38 -> EffectConfig("scale", EffectScope.WHILE_INSIDE, mapOf("value" to "1.60"))
            39 -> EffectConfig("allow_flight", EffectScope.WHILE_INSIDE, mapOf("value" to "true"))
            40 -> EffectConfig("potion", EffectScope.WHILE_INSIDE, mapOf("effect" to "SPEED", "amplifier" to "1"))
            41 -> EffectConfig("invisibility_suppression", EffectScope.WHILE_INSIDE)
            else -> return
        }
        effects.add(effect)
        gui.saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, regionId) }
    }

    private fun promptEffect(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.effect") { raw ->
            val args = GuiValues.splitArgs(raw)
            if (args.isEmpty()) {
                text.send(player, "gui.effect.type-required")
                openEffects(player, region.id)
                return@promptLine
            }
            val values = GuiValues.parsePairs(args.drop(1))
            val scope = GuiValues.parseEffectScope(values.remove("scope"))
            val combination = GuiValues.parseEffectCombination(values.remove("combination"))
            val effects = ArrayList(region.effects)
            effects.add(EffectConfig(args[0].lowercase(Locale.ROOT), scope, values, combination))
            gui.saveAndReopen(player, region.copy(effects = effects)) { openEffects(player, region.id) }
        }
    }

    fun openTriggers(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.TRIGGERS, region.id),
            54,
            text.component("gui.trigger.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(region))
        var slot = 9
        for ((trigger, blocks) in region.triggers) {
            if (slot > 35) break
            val summary = blocks.take(4).map { block ->
                text.text(
                    "gui.trigger.block-line",
                    mapOf(
                        "name" to (block.name ?: text.text("gui.trigger.unnamed")),
                        "execution" to block.execution.name.lowercase(Locale.ROOT),
                        "actions" to block.thenActions.joinToString(", ") { it.type },
                    ),
                )
            }
            inventory.setItem(
                slot,
                text.named(
                    GuiIcons.TRIGGER,
                    text.text(
                        "gui.trigger.entry",
                        mapOf(
                            "trigger" to text.label("gui.trigger.key.${trigger.key}", trigger.key),
                            "count" to blocks.size.toString(),
                        ),
                    ),
                    summary + listOf(text.text("gui.trigger.clear-hint")),
                ),
            )
            slot += 1
        }
        inventory.setItem(37, text.item(Material.OAK_DOOR, "gui.trigger.preset.enter"))
        inventory.setItem(38, text.item(Material.FIREWORK_ROCKET, "gui.trigger.preset.start"))
        inventory.setItem(39, text.item(Material.GOLD_INGOT, "gui.trigger.preset.finish"))
        inventory.setItem(41, text.item(Material.PAPER, "gui.trigger.advanced"))
        inventory.setItem(43, text.item(Material.LAVA_BUCKET, "gui.trigger.clear"))
        inventory.setItem(49, items.back())
        player.openInventory(inventory)
    }

    fun clickTriggers(player: Player, regionId: String, slot: Int) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        if (slot == 49) return gui.openDetail(player, regionId)
        if (slot in 9..35) {
            val trigger = region.triggers.keys.toList().getOrNull(slot - 9) ?: return
            val triggers = LinkedHashMap(region.triggers)
            triggers.remove(trigger)
            return gui.saveAndReopen(player, region.copy(triggers = triggers)) { openTriggers(player, region.id) }
        }
        if (slot == 41) return promptTriggerAction(player, region)
        if (slot == 43) {
            return gui.saveAndReopen(player, region.copy(triggers = emptyMap())) { openTriggers(player, region.id) }
        }
        val block = when (slot) {
            37 -> RegionTrigger.ON_ENTER to ActionBlockConfig(
                "gui-enter-message",
                thenActions = listOf(ActionConfig("message", mapOf("text" to text.text("gui.trigger.preset.enter.text")))),
            )
            38 -> RegionTrigger.ON_MODE_START to ActionBlockConfig(
                "gui-start-title",
                thenActions = listOf(
                    ActionConfig(
                        "title",
                        mapOf(
                            "title" to text.text("gui.trigger.preset.start.title"),
                            "subtitle" to text.text("gui.trigger.preset.start.subtitle"),
                        ),
                    ),
                ),
            )
            39 -> RegionTrigger.ON_FINISH to ActionBlockConfig(
                "gui-finish-broadcast",
                thenActions = listOf(ActionConfig("broadcast", mapOf("text" to text.text("gui.trigger.preset.finish.text")))),
            )
            else -> return
        }
        gui.saveAndReopen(
            player,
            region.copy(triggers = GuiValues.appendTrigger(region.triggers, block.first, block.second)),
        ) { openTriggers(player, region.id) }
    }

    private fun promptTriggerAction(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.trigger") { raw ->
            val args = GuiValues.splitArgs(raw)
            if (args.size < 2) {
                text.send(player, "gui.trigger.usage")
                openTriggers(player, region.id)
                return@promptLine
            }
            val trigger = RegionTrigger.fromKey(args[0])
            if (trigger == null) {
                text.send(player, "gui.trigger.unknown", mapOf("trigger" to args[0]))
                openTriggers(player, region.id)
                return@promptLine
            }
            val values = GuiValues.parsePairs(args.drop(2))
            val execution = when (values.remove("execution")?.lowercase(Locale.ROOT)) {
                "primary", "primary_region", "primary-region" -> TriggerExecution.PRIMARY_REGION
                else -> TriggerExecution.ALL_ACTIVE
            }
            val action = ActionConfig(args[1].lowercase(Locale.ROOT), values)
            val block = ActionBlockConfig(
                "gui-${trigger.key}-${action.type}",
                thenActions = listOf(action),
                execution = execution,
            )
            gui.saveAndReopen(
                player,
                region.copy(triggers = GuiValues.appendTrigger(region.triggers, trigger, block)),
            ) { openTriggers(player, region.id) }
        }
    }
}
