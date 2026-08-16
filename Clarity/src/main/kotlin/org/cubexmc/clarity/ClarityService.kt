package org.cubexmc.clarity

import com.google.common.collect.Multimap
import java.util.Locale
import kotlin.math.abs
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.potion.PotionEffect

/**
 * 清理核心。所有对玩家属性/效果的读写都通过该玩家的 `EntityScheduler` 调度,
 * 在 Paper 与 Folia 上均线程安全。命令与进服监听共用本服务。
 */
class ClarityService(private val plugin: ClarityPlugin) {
    fun scan(sender: CommandSender?, player: Player) {
        player.scheduler.run(plugin, { doScan(sender, player) }, null)
    }

    fun purgeAttr(sender: CommandSender?, player: Player, pattern: String) {
        player.scheduler.run(plugin, { doPurgeAttr(sender, player, pattern) }, null)
    }

    fun purgeEffect(sender: CommandSender?, player: Player, what: String) {
        player.scheduler.run(plugin, { doPurgeEffect(sender, player, what) }, null)
    }

    fun scanItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        player.scheduler.run(plugin, { doScanItems(sender, player, scope) }, null)
    }

    fun sweepItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        player.scheduler.run(plugin, { doSweepItems(sender, player, scope) }, null)
    }

    fun purgeLevelToolsItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        player.scheduler.run(plugin, { doPurgeLevelToolsItems(sender, player, scope) }, null)
    }

    /** 按当前 config 黑名单清扫;delayTicks>=1 时延迟执行(进服用),honours dry-run。sender 可为 null。 */
    fun sweep(sender: CommandSender?, player: Player, delayTicks: Long) {
        if (delayTicks <= 0) {
            player.scheduler.run(plugin, { doSweep(sender, player) }, null)
        } else {
            player.scheduler.runDelayed(plugin, { doSweep(sender, player) }, null, delayTicks)
        }
    }

    private fun doScan(sender: CommandSender?, player: Player) {
        msg(sender, "§6=== [${player.name}] attribute modifiers ===")
        var any = false
        for (attribute in Registry.ATTRIBUTE) {
            val instance = player.getAttribute(attribute)
            if (instance == null || instance.modifiers.isEmpty()) continue
            any = true
            msg(
                sender,
                String.format(
                    Locale.ROOT,
                    "§e%s §7base=%.4f total=%.4f",
                    attribute.key,
                    instance.baseValue,
                    instance.value,
                ),
            )
            for (modifier in instance.modifiers) {
                msg(
                    sender,
                    String.format(
                        Locale.ROOT,
                        "   §f- %s §7amount=%.4f op=%s",
                        modifier.key,
                        modifier.amount,
                        modifier.operation,
                    ),
                )
            }
        }
        if (!any) msg(sender, "§a(none — attributes are clean)")

        msg(sender, "§6=== [${player.name}] potion effects ===")
        val effects = player.activePotionEffects
        if (effects.isEmpty()) {
            msg(sender, "§a(none)")
            return
        }
        for (effect in effects) {
            val infinite = ClarityMatcher.isInfiniteDuration(effect.duration)
            val duration = if (infinite) "INFINITE/long(${effect.duration})" else effect.duration.toString()
            msg(
                sender,
                String.format(
                    Locale.ROOT,
                    "   §f- %s §7amp=%d dur=%s%s",
                    effect.type.key,
                    effect.amplifier,
                    duration,
                    if (infinite) " §c<-- infinite/very long" else "",
                ),
            )
        }
    }

    private fun doPurgeAttr(sender: CommandSender?, player: Player, pattern: String) {
        if (pattern.equals("minecraft", ignoreCase = true)) {
            msg(sender, "§cRefusing to purge the whole 'minecraft' namespace. Target a specific id instead.")
            return
        }
        val removed = removeMatchingAttr(player, listOf(pattern), false)
        player.saveData()
        msg(sender, "§aRemoved §f${removed.size}§a attribute modifier(s) matching '$pattern' from ${player.name}.")
        removed.forEach { msg(sender, "   §7$it") }
        plugin.logger.info("[purge attr] player=${player.name} pattern='$pattern' removed=${removed.size} $removed")
    }

    private fun doPurgeEffect(sender: CommandSender?, player: Player, what: String) {
        val allInfinite = what.equals("all-infinite", ignoreCase = true)
        val types = if (allInfinite) emptyList() else listOf(what)
        val removed = removeMatchingEffects(player, types, allInfinite, false)
        player.saveData()
        msg(sender, "§aRemoved §f${removed.size}§a potion effect(s) [$what] from ${player.name}.")
        removed.forEach { msg(sender, "   §7$it") }
        plugin.logger.info("[purge effect] player=${player.name} what='$what' removed=${removed.size} $removed")
    }

    private fun doSweep(sender: CommandSender?, player: Player) {
        val config = plugin.config()
        val dryRun = config.dryRun()
        val tag = if (dryRun) "[DRY-RUN] " else ""
        val attributeHits = removeMatchingAttr(player, config.attributeModifierIds(), dryRun)
        val effectHits = removeMatchingEffects(player, config.effectTypes(), false, dryRun)

        if (attributeHits.isNotEmpty() || effectHits.isNotEmpty()) {
            if (!dryRun) player.saveData()
            plugin.logger.info(
                "${tag}sweep player=${player.name} attrRemoved=${attributeHits.size} " +
                    "effectRemoved=${effectHits.size} attrs=$attributeHits effects=$effectHits",
            )
            msg(
                sender,
                "§e${tag}§aSweep ${player.name}: ${attributeHits.size} modifier(s), ${effectHits.size} effect(s)" +
                    (if (dryRun) " §7(would be removed)" else " §aremoved") + ".",
            )
            attributeHits.forEach { msg(sender, "   §7attr: $it") }
            effectHits.forEach { msg(sender, "   §7effect: $it") }
        } else {
            msg(sender, "§aSweep ${player.name}: nothing matched the blacklist.")
        }
    }

    private fun doScanItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        val summary = runItemCleanup(sender, player, scope, ItemCleanupMode.SWEEP, true)
        if (summary.matches == 0) {
            msg(sender, "§aItem scan ${player.name} [${scope.id()}]: nothing matched configured rules.")
        } else {
            msg(
                sender,
                "§eItem scan ${player.name} [${scope.id()}]: ${summary.matches} suspicious item(s) across " +
                    "${summary.scanned} non-empty slot(s).",
            )
        }
    }

    private fun doSweepItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        val dryRun = plugin.config().dryRun()
        val summary = runItemCleanup(sender, player, scope, ItemCleanupMode.SWEEP, dryRun)
        val tag = if (dryRun) "[DRY-RUN] " else ""
        if (summary.matches == 0) {
            msg(sender, "§a${tag}Item sweep ${player.name} [${scope.id()}]: nothing matched configured rules.")
            return
        }
        if (!dryRun) {
            player.updateInventory()
            player.saveData()
        }
        plugin.logger.info(
            "${tag}item sweep player=${player.name} scope=${scope.id()} matched=${summary.matches} " +
                "changed=${summary.changed} details=${summary.details}",
        )
        msg(
            sender,
            "§e${tag}§aItem sweep ${player.name} [${scope.id()}]: ${summary.matches} item(s), " +
                "${summary.changed} changed" + (if (dryRun) " §7(would be changed)" else " §aremoved") + ".",
        )
    }

    private fun doPurgeLevelToolsItems(sender: CommandSender?, player: Player, scope: ItemScope) {
        val summary = runItemCleanup(sender, player, scope, ItemCleanupMode.LEVELTOOLS_PURGE, false)
        if (summary.matches == 0) {
            msg(sender, "§aItem purge ${player.name} [${scope.id()}]: no LevelTools data found.")
            return
        }
        player.updateInventory()
        player.saveData()
        plugin.logger.info(
            "item purge leveltools player=${player.name} scope=${scope.id()} matched=${summary.matches} " +
                "changed=${summary.changed} details=${summary.details}",
        )
        msg(
            sender,
            "§aItem purge ${player.name} [${scope.id()}]: removed LevelTools data from ${summary.changed} item(s).",
        )
    }

    private fun removeMatchingAttr(player: Player, blacklist: List<String>, dryRun: Boolean): List<String> {
        val hits = ArrayList<String>()
        for (attribute in Registry.ATTRIBUTE) {
            val instance: AttributeInstance = player.getAttribute(attribute) ?: continue
            for (modifier in ArrayList(instance.modifiers)) {
                val key = modifier.key
                if (ClarityMatcher.matchesModifier(key.toString(), key.namespace, blacklist)) {
                    hits.add("${attribute.key} <- $key (amount=${modifier.amount}, op=${modifier.operation})")
                    if (!dryRun) instance.removeModifier(modifier)
                }
            }
        }
        return hits
    }

    private fun removeMatchingEffects(
        player: Player,
        types: List<String>,
        allInfinite: Boolean,
        dryRun: Boolean,
    ): List<String> {
        val infiniteOnly = plugin.config().effectsInfiniteOnly()
        val hits = ArrayList<String>()
        for (effect in ArrayList(player.activePotionEffects)) {
            val key = effect.type.key
            val infinite = ClarityMatcher.isInfiniteDuration(effect.duration)
            val typeMatch = if (allInfinite) infinite else ClarityMatcher.matchesEffect(key.toString(), key.key, types)
            if (!typeMatch) continue
            if (!allInfinite && infiniteOnly && !infinite) continue
            val duration = if (infinite) "INFINITE/long(${effect.duration})" else effect.duration.toString()
            hits.add("$key (amp=${effect.amplifier}, dur=$duration)")
            if (!dryRun) player.removePotionEffect(effect.type)
        }
        return hits
    }

    private fun runItemCleanup(
        sender: CommandSender?,
        player: Player,
        scope: ItemScope,
        mode: ItemCleanupMode,
        dryRun: Boolean,
    ): ItemRunSummary {
        val config = plugin.config()
        val summary = ItemRunSummary()
        for (slot in itemSlots(player, scope)) {
            val item = slot.stack() ?: continue
            if (item.type.isAir) continue
            summary.scanned++
            val result = cleanItem(item, config, mode, dryRun)
            if (result.hits().isEmpty()) continue
            summary.matches++
            summary.details.add("${slot.label()} ${item.type} x${item.amount} -> ${result.hits()}")
            msg(sender, "§e${slot.label()} §7${item.type} x${item.amount}")
            result.hits().forEach { msg(sender, "   §7- $it") }
            if (!dryRun && result.changed()) {
                slot.setter()(result.stack())
                summary.changed++
            }
        }
        return summary
    }

    private fun cleanItem(
        original: ItemStack,
        config: ClarityConfig,
        mode: ItemCleanupMode,
        dryRun: Boolean,
    ): ItemCleanupResult {
        val originalMeta = original.itemMeta ?: return ItemCleanupResult.empty(original)
        val working = if (dryRun) original else original.clone()
        val meta = if (dryRun) originalMeta else working.itemMeta ?: return ItemCleanupResult.empty(original)
        val hits = ArrayList<String>()
        var changed = false

        val levelToolsRules = mode == ItemCleanupMode.LEVELTOOLS_PURGE || config.itemLevelToolsEnabled()
        var levelToolsMarked = false
        if (levelToolsRules) {
            val pdc = cleanLevelToolsPdc(meta, config.itemLevelToolsPdcKeys(), dryRun)
            hits.addAll(pdc.hits())
            changed = changed or pdc.changed()
            levelToolsMarked = levelToolsMarked or pdc.marked()
            if (mode == ItemCleanupMode.LEVELTOOLS_PURGE || config.itemLevelToolsRemoveLore()) {
                val lore = cleanLevelToolsLore(meta, dryRun)
                hits.addAll(lore.hits())
                changed = changed or lore.changed()
                levelToolsMarked = levelToolsMarked or lore.marked()
            }
        }

        val attributes = cleanItemAttributes(meta, config, mode, levelToolsMarked, dryRun)
        hits.addAll(attributes.hits())
        changed = changed or attributes.changed()
        if (!dryRun && changed) working.itemMeta = meta
        return ItemCleanupResult(hits, changed, working)
    }

    private fun cleanLevelToolsPdc(meta: ItemMeta, keys: List<String>, dryRun: Boolean): PdcResult {
        val pdc: PersistentDataContainer = meta.persistentDataContainer
        val hits = ArrayList<String>()
        var changed = false
        var marked = false
        for (key in ArrayList(pdc.keys)) {
            if (!ClarityMatcher.matchesNamespacedKey(key.toString(), key.namespace, keys, false)) continue
            marked = true
            hits.add("LevelTools PDC key $key")
            if (!dryRun) {
                pdc.remove(key)
                changed = true
            }
        }
        return PdcResult(hits, changed, marked)
    }

    @Suppress("DEPRECATION")
    private fun cleanLevelToolsLore(meta: ItemMeta, dryRun: Boolean): LoreResult {
        if (!meta.hasLore()) return LoreResult(emptyList(), false, false)
        val lore: List<String> = meta.getLore() ?: return LoreResult(emptyList(), false, false)
        val kept = ArrayList<String>()
        var removed = 0
        for (line in lore) {
            if (ClarityMatcher.isLevelToolsLoreLine(line)) removed++ else kept.add(line)
        }
        if (removed == 0) return LoreResult(emptyList(), false, false)
        if (!dryRun) meta.setLore(if (kept.isEmpty()) null else kept)
        return LoreResult(listOf("LevelTools lore line(s) $removed"), true, true)
    }

    private fun cleanItemAttributes(
        meta: ItemMeta,
        config: ClarityConfig,
        mode: ItemCleanupMode,
        levelToolsMarked: Boolean,
        dryRun: Boolean,
    ): AttributeResult {
        val modifiers: Multimap<Attribute, AttributeModifier> = meta.attributeModifiers
            ?: return AttributeResult(emptyList(), false)
        if (modifiers.isEmpty) return AttributeResult(emptyList(), false)
        val hits = ArrayList<String>()
        var changed = false
        for (entry in ArrayList(modifiers.entries())) {
            val attribute = entry.key
            val modifier = entry.value
            val reason = attributeRemovalReason(attribute, modifier, config, mode, levelToolsMarked) ?: continue
            hits.add(
                "${attribute.key} <- ${modifier.key} (amount=${modifier.amount}, op=${modifier.operation}, reason=$reason)",
            )
            if (!dryRun) {
                meta.removeAttributeModifier(attribute, modifier)
                changed = true
            }
        }
        return AttributeResult(hits, changed)
    }

    private fun attributeRemovalReason(
        attribute: Attribute,
        modifier: AttributeModifier,
        config: ClarityConfig,
        mode: ItemCleanupMode,
        levelToolsMarked: Boolean,
    ): String? {
        if (mode == ItemCleanupMode.LEVELTOOLS_PURGE) {
            return if (levelToolsMarked && config.itemLevelToolsRemoveAttributesOnMarkedItems()) {
                "leveltools-marked-item"
            } else {
                null
            }
        }

        // Paper marks getKey non-null, but retain the Java version's defensive null tolerance.
        val modifierKey: NamespacedKey? = modifier.key
        if (
            modifierKey != null &&
            ClarityMatcher.matchesModifier(
                modifierKey.toString(),
                modifierKey.namespace,
                config.itemAttributeModifierIds(),
            )
        ) {
            return "modifier-id"
        }
        val max = configuredMaxAmount(attribute, config.itemAttributeMaxAmounts())
        if (max != null && abs(modifier.amount) > max) return "amount>$max"
        if (levelToolsMarked && config.itemLevelToolsRemoveAttributesOnMarkedItems()) return "leveltools-marked-item"
        return null
    }

    private fun configuredMaxAmount(attribute: Attribute, maxAmounts: Map<String, Double>): Double? {
        if (maxAmounts.isEmpty()) return null
        val full = attribute.key.toString().lowercase(Locale.ROOT)
        val path = attribute.key.key.lowercase(Locale.ROOT)
        return maxAmounts[full] ?: maxAmounts[path]
    }

    private fun itemSlots(player: Player, scope: ItemScope): List<ItemSlot> {
        val slots = ArrayList<ItemSlot>()
        val inventory = player.inventory
        when (scope) {
            ItemScope.HAND -> slots.add(ItemSlot("hand", inventory.itemInMainHand) { inventory.setItemInMainHand(it) })
            ItemScope.INVENTORY -> addStorageSlots(slots, inventory)
            ItemScope.EQUIPMENT -> addEquipmentSlots(slots, inventory)
            ItemScope.ENDER -> addInventorySlots(slots, "ender", player.enderChest)
            ItemScope.ALL -> {
                addStorageSlots(slots, inventory)
                addEquipmentSlots(slots, inventory)
                addInventorySlots(slots, "ender", player.enderChest)
            }
        }
        return slots
    }

    private fun addStorageSlots(slots: MutableList<ItemSlot>, inventory: PlayerInventory) {
        val contents = inventory.storageContents
        for (index in contents.indices) {
            slots.add(ItemSlot("inventory[$index]", contents[index]) { inventory.setItem(index, it) })
        }
    }

    private fun addEquipmentSlots(slots: MutableList<ItemSlot>, inventory: PlayerInventory) {
        slots.add(ItemSlot("equipment[offhand]", inventory.itemInOffHand) { inventory.setItemInOffHand(it) })
        slots.add(ItemSlot("equipment[helmet]", inventory.helmet) { inventory.helmet = it })
        slots.add(ItemSlot("equipment[chestplate]", inventory.chestplate) { inventory.chestplate = it })
        slots.add(ItemSlot("equipment[leggings]", inventory.leggings) { inventory.leggings = it })
        slots.add(ItemSlot("equipment[boots]", inventory.boots) { inventory.boots = it })
    }

    private fun addInventorySlots(slots: MutableList<ItemSlot>, label: String, inventory: Inventory) {
        for (index in 0 until inventory.size) {
            slots.add(ItemSlot("$label[$index]", inventory.getItem(index)) { inventory.setItem(index, it) })
        }
    }

    private fun msg(sender: CommandSender?, text: String) {
        sender?.sendMessage(text)
    }

    private enum class ItemCleanupMode { SWEEP, LEVELTOOLS_PURGE }

    private class ItemSlot(
        private val label: String,
        private val stack: ItemStack?,
        private val setter: (ItemStack) -> Unit,
    ) {
        fun label(): String = label
        fun stack(): ItemStack? = stack
        fun setter(): (ItemStack) -> Unit = setter
    }

    private class ItemCleanupResult(
        private val hits: List<String>,
        private val changed: Boolean,
        private val stack: ItemStack,
    ) {
        fun hits(): List<String> = hits
        fun changed(): Boolean = changed
        fun stack(): ItemStack = stack

        companion object {
            fun empty(stack: ItemStack): ItemCleanupResult = ItemCleanupResult(emptyList(), false, stack)
        }
    }

    private class PdcResult(
        private val hits: List<String>,
        private val changed: Boolean,
        private val marked: Boolean,
    ) {
        fun hits(): List<String> = hits
        fun changed(): Boolean = changed
        fun marked(): Boolean = marked
    }

    private class LoreResult(
        private val hits: List<String>,
        private val changed: Boolean,
        private val marked: Boolean,
    ) {
        fun hits(): List<String> = hits
        fun changed(): Boolean = changed
        fun marked(): Boolean = marked
    }

    private class AttributeResult(private val hits: List<String>, private val changed: Boolean) {
        fun hits(): List<String> = hits
        fun changed(): Boolean = changed
    }

    private class ItemRunSummary {
        var scanned = 0
        var matches = 0
        var changed = 0
        val details = ArrayList<String>()
    }
}
