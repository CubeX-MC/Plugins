package org.cubexmc.regions.gui

import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.service.RegionAuthorityService
import java.util.Locale
import java.util.UUID

/**
 * Pure value helpers behind the mode/flag/effect editors. Nothing here touches Bukkit state, so the
 * menu classes stay about layout and the editing rules stay testable on their own.
 */
internal object GuiValues {
    fun adjustInt(
        values: Map<String, String>,
        key: String,
        default: Int,
        subtract: Boolean,
        min: Int,
        removeAtZero: Boolean = false,
        step: Int = 1,
    ): Map<String, String> {
        val next = ((values[key]?.toIntOrNull() ?: default) + if (subtract) -step else step).coerceAtLeast(min)
        return LinkedHashMap(values).apply {
            if (removeAtZero && next <= 0) remove(key) else this[key] = next.toString()
        }
    }

    fun toggleBool(values: Map<String, String>, key: String, default: Boolean): Map<String, String> =
        LinkedHashMap(values).apply {
            this[key] = (!(this[key]?.toBooleanStrictOrNull() ?: default)).toString()
        }

    fun adjustDouble(
        values: Map<String, String>,
        key: String,
        default: Double,
        subtract: Boolean,
        min: Double,
    ): Map<String, String> {
        val next = ((values[key]?.toDoubleOrNull() ?: default) + if (subtract) -1.0 else 1.0).coerceAtLeast(min)
        return LinkedHashMap(values).apply { this[key] = "%.1f".format(Locale.ROOT, next) }
    }

    fun cycleVehicle(values: Map<String, String>, key: String, default: String): Map<String, String> {
        val options = listOf("none", "any", "boat", "horse", "minecart", "pass")
        val current = values[key] ?: default
        val index = options.indexOfFirst { it.equals(current, ignoreCase = true) }
        val next = options[(if (index < 0) 0 else index + 1) % options.size]
        return LinkedHashMap(values).apply {
            if (next == default && key != "vehicle") remove(key) else this[key] = next
        }
    }

    /**
     * 把 [target] 加进裁判名单，已在名单里则移除。
     *
     * 名单存 UUID 而不是玩家名：按名字匹配会在玩家改名后失效，更糟的是旧名可能被别人注册走，
     * 等于把发令权悄悄转给陌生人。旧版本按名字写下的条目在这里被丢弃——那时候这个字段还没有被
     * 任何玩法逻辑读过，没有行为可以保留。
     */
    fun toggleJudge(values: Map<String, String>, target: UUID): Map<String, String> {
        val judges = parseJudges(values).toMutableList()
        if (!judges.remove(target)) judges.add(target)
        return LinkedHashMap(values).apply {
            if (judges.isEmpty()) {
                remove(RegionAuthorityService.JUDGES_KEY)
            } else {
                this[RegionAuthorityService.JUDGES_KEY] = judges.joinToString(",")
            }
        }
    }

    fun clearJudges(values: Map<String, String>): Map<String, String> =
        LinkedHashMap(values).apply { remove(RegionAuthorityService.JUDGES_KEY) }

    fun parseJudges(values: Map<String, String>): List<UUID> =
        values[RegionAuthorityService.JUDGES_KEY]
            ?.split(',', ';')
            ?.mapNotNull { entry -> runCatching { UUID.fromString(entry.trim()) }.getOrNull() }
            .orEmpty()

    fun appendTrigger(
        current: Map<RegionTrigger, List<ActionBlockConfig>>,
        trigger: RegionTrigger,
        block: ActionBlockConfig,
    ): Map<RegionTrigger, List<ActionBlockConfig>> =
        LinkedHashMap(current).apply { this[trigger] = (this[trigger] ?: emptyList()) + block }

    fun parseEffectScope(value: String?): EffectScope =
        when (value?.lowercase(Locale.ROOT)) {
            "until_mode_end", "until-mode-end", "mode" -> EffectScope.UNTIL_MODE_END
            "timed", "time" -> EffectScope.TIMED
            else -> EffectScope.WHILE_INSIDE
        }

    fun parseEffectCombination(value: String?): EffectCombination =
        when (value?.lowercase(Locale.ROOT)) {
            "exclusive" -> EffectCombination.EXCLUSIVE
            "stack" -> EffectCombination.STACK
            "merge_by_type", "merge-by-type", "merge" -> EffectCombination.MERGE_BY_TYPE
            else -> EffectCombination.HIGHEST_PRIORITY
        }

    fun parsePairs(args: List<String>): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for (arg in args) {
            val pair = parsePair(arg) ?: continue
            values[pair.first] = pair.second
        }
        return values
    }

    fun parsePair(raw: String): Pair<String, String>? {
        val index = raw.indexOf('=')
        if (index <= 0) return null
        val key = raw.substring(0, index).trim()
        if (key.isBlank()) return null
        return key to raw.substring(index + 1).trim()
    }

    fun splitArgs(raw: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (char in raw) {
            when {
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }

    fun formatLocation(location: Location): String {
        val part = { value: Double -> "%.2f".format(Locale.ROOT, value) }
        return listOf(
            location.world?.name ?: "world",
            part(location.x),
            part(location.y),
            part(location.z),
            "%.2f".format(Locale.ROOT, location.yaw),
            "%.2f".format(Locale.ROOT, location.pitch),
        ).joinToString(",")
    }

    fun checkpointCount(raw: String?): Int = raw?.split(';')?.count { it.isNotBlank() } ?: 0

    fun defaultVehicle(type: String): String =
        when (type.lowercase(Locale.ROOT)) {
            "boat_race" -> "boat"
            "horse_race" -> "horse"
            "run_race" -> "none"
            else -> "pass"
        }

    fun defaultModeValues(type: String): Map<String, String> =
        when (type) {
            "dual_pvp" -> linkedMapOf(
                "min-players" to "2",
                "require-ready" to "true",
                "replace-gear" to "true",
            )
            "union_war" -> linkedMapOf(
                "min-players" to "2",
                "min-unions" to "2",
                "require-ready" to "true",
                "replace-gear" to "true",
            )
            "run_race", "boat_race", "horse_race" -> linkedMapOf(
                "min-players" to "1",
                "start-mode" to "vote",
                "require-start" to "true",
                "radius" to "2.5",
                "timeout-seconds" to "300",
                "vehicle" to defaultVehicle(type),
            )
            "hide_and_seek" -> linkedMapOf(
                "min-players" to "2",
                "start-mode" to "vote",
                "hide-seconds" to "30",
                "round-seconds" to "300",
                "found-becomes-seeker" to "true",
            )
            else -> emptyMap()
        }

    fun nextFlagValue(value: String): String =
        when (value.lowercase(Locale.ROOT)) {
            "pass" -> "deny"
            "deny" -> "allow"
            else -> "pass"
        }

    fun cuboidFromCurrentChunk(player: Player, id: String): RegionSourceRef {
        val chunk = player.location.chunk
        val world = player.world
        val minX = chunk.x * 16
        val minZ = chunk.z * 16
        return RegionSourceRef(
            "cuboid",
            linkedMapOf(
                "id" to id,
                "name" to id,
                "world" to world.name,
                "min-x" to minX.toString(),
                "min-y" to world.minHeight.toString(),
                "min-z" to minZ.toString(),
                "max-x" to (minX + 15).toString(),
                "max-y" to world.maxHeight.toString(),
                "max-z" to (minZ + 15).toString(),
            ),
        )
    }

    val COMBAT_KITS: Map<Int, Map<String, String>> = mapOf(
        28 to linkedMapOf(
            "kit" to "IRON_SWORD:1,BOW:1,ARROW:16,COOKED_BEEF:8",
            "armor" to "IRON_BOOTS:1,IRON_LEGGINGS:1,IRON_CHESTPLATE:1,IRON_HELMET:1",
            "offhand" to "SHIELD:1",
            "replace-gear" to "true",
        ),
        29 to linkedMapOf(
            "kit" to "BOW:1,ARROW:32,WOODEN_SWORD:1,COOKED_BEEF:6",
            "armor" to "LEATHER_BOOTS:1,LEATHER_LEGGINGS:1,LEATHER_CHESTPLATE:1,LEATHER_HELMET:1",
            "replace-gear" to "true",
        ),
        30 to linkedMapOf(
            "kit" to "DIAMOND_SWORD:1,BOW:1,ARROW:32,COOKED_BEEF:16",
            "armor" to "DIAMOND_BOOTS:1,DIAMOND_LEGGINGS:1,DIAMOND_CHESTPLATE:1,DIAMOND_HELMET:1",
            "offhand" to "SHIELD:1",
            "replace-gear" to "true",
        ),
    )

    fun applyKit(values: Map<String, String>, slot: Int): Map<String, String> {
        val kit = COMBAT_KITS[slot] ?: return values
        return LinkedHashMap(values).apply {
            remove("offhand")
            putAll(kit)
        }
    }

    fun clearKit(values: Map<String, String>): Map<String, String> =
        LinkedHashMap(values).apply {
            remove("kit")
            remove("armor")
            remove("offhand")
            put("replace-gear", "false")
        }
}
