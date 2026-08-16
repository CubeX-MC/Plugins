package org.cubexmc.clarity

import java.util.LinkedHashMap
import java.util.Locale
import org.bukkit.configuration.file.FileConfiguration

/** config.yml 的不可变快照。 */
class ClarityConfig private constructor(
    private val autoCleanOnJoin: Boolean,
    private val dryRun: Boolean,
    private val joinDelayTicks: Long,
    attributeModifierIds: List<String>,
    effectTypes: List<String>,
    private val effectsInfiniteOnly: Boolean,
    private val itemLevelToolsEnabled: Boolean,
    itemLevelToolsPdcKeys: List<String>,
    private val itemLevelToolsRemoveLore: Boolean,
    private val itemLevelToolsRemoveAttributesOnMarkedItems: Boolean,
    itemAttributeModifierIds: List<String>,
    itemAttributeMaxAmounts: Map<String, Double>,
) {
    private val attributeModifierIds = java.util.List.copyOf(attributeModifierIds)
    private val effectTypes = java.util.List.copyOf(effectTypes)
    private val itemLevelToolsPdcKeys = java.util.List.copyOf(itemLevelToolsPdcKeys)
    private val itemAttributeModifierIds = java.util.List.copyOf(itemAttributeModifierIds)
    private val itemAttributeMaxAmounts = java.util.Map.copyOf(itemAttributeMaxAmounts)

    fun autoCleanOnJoin(): Boolean = autoCleanOnJoin
    fun dryRun(): Boolean = dryRun
    fun joinDelayTicks(): Long = joinDelayTicks
    fun attributeModifierIds(): List<String> = attributeModifierIds
    fun effectTypes(): List<String> = effectTypes
    fun effectsInfiniteOnly(): Boolean = effectsInfiniteOnly
    fun itemLevelToolsEnabled(): Boolean = itemLevelToolsEnabled
    fun itemLevelToolsPdcKeys(): List<String> = itemLevelToolsPdcKeys
    fun itemLevelToolsRemoveLore(): Boolean = itemLevelToolsRemoveLore
    fun itemLevelToolsRemoveAttributesOnMarkedItems(): Boolean = itemLevelToolsRemoveAttributesOnMarkedItems
    fun itemAttributeModifierIds(): List<String> = itemAttributeModifierIds
    fun itemAttributeMaxAmounts(): Map<String, Double> = itemAttributeMaxAmounts

    companion object {
        private val DEFAULT_LEVELTOOLS_PDC_KEYS = listOf(
            "leveltools:leveltoolslevel",
            "leveltools:leveltoolsxp",
            "leveltools:leveltoolsreward",
        )

        @JvmStatic
        fun load(config: FileConfiguration): ClarityConfig = ClarityConfig(
            config.getBoolean("auto-clean-on-join", false),
            config.getBoolean("dry-run", true),
            maxOf(1L, config.getLong("join-delay-ticks", 40L)),
            config.getStringList("attributes.remove-modifier-ids"),
            config.getStringList("effects.remove-types"),
            config.getBoolean("effects.infinite-only", true),
            config.getBoolean("items.leveltools.enabled", true),
            stringListOrDefault(config, "items.leveltools.remove-pdc-keys", DEFAULT_LEVELTOOLS_PDC_KEYS),
            config.getBoolean("items.leveltools.remove-lore", true),
            config.getBoolean("items.leveltools.remove-attributes-on-marked-items", false),
            config.getStringList("items.attributes.remove-modifier-ids"),
            parseMaxAmounts(config.getStringList("items.attributes.max-amounts")),
        )

        private fun stringListOrDefault(
            config: FileConfiguration,
            path: String,
            fallback: List<String>,
        ): List<String> = if (config.contains(path)) config.getStringList(path) else fallback

        private fun parseMaxAmounts(entries: List<String?>): Map<String, Double> {
            val result = LinkedHashMap<String, Double>()
            for (raw in entries) {
                val value = raw?.trim() ?: continue
                val split = value.lastIndexOf('=')
                if (split <= 0 || split >= value.length - 1) continue
                val key = value.substring(0, split).trim().lowercase(Locale.ROOT)
                if (key.isEmpty()) continue
                val max = value.substring(split + 1).trim().toDoubleOrNull() ?: continue
                if (max >= 0.0) result[key] = max
            }
            return result
        }
    }
}
