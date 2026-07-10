package org.cubexmc.regions.model

import org.bukkit.Location
import java.util.UUID

data class RegionDefinition(
    val id: String,
    val name: String,
    val source: RegionSourceRef,
    val ownerPolicy: OwnerPolicy = OwnerPolicy.ADMIN,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val mode: ModeConfig? = ModeConfig("free_event"),
    val flags: Map<String, FlagConfig> = emptyMap(),
    val effects: List<EffectConfig> = emptyList(),
    val triggers: Map<RegionTrigger, List<ActionBlockConfig>> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
)

data class RegionSourceRef(
    val type: String,
    val values: Map<String, String> = emptyMap(),
) {
    fun describe(): String = if (values.isEmpty()) type else "$type:${values.values.joinToString(":")}"
}

enum class OwnerPolicy {
    ADMIN,
    LANDS_OWNER,
    SOURCE_OWNER,
}

data class ModeConfig(
    val type: String,
    val values: Map<String, String> = emptyMap(),
)

data class FlagConfig(
    val key: String,
    val value: String,
    val values: Map<String, String> = emptyMap(),
)

data class EffectConfig(
    val type: String,
    val scope: EffectScope = EffectScope.WHILE_INSIDE,
    val values: Map<String, String> = emptyMap(),
)

enum class EffectScope {
    WHILE_INSIDE,
    UNTIL_MODE_END,
    TIMED,
}

data class ActionBlockConfig(
    val name: String? = null,
    val conditions: List<ConditionConfig> = emptyList(),
    val thenActions: List<ActionConfig> = emptyList(),
    val elseActions: List<ActionConfig> = emptyList(),
)

data class ActionConfig(
    val type: String,
    val values: Map<String, String> = emptyMap(),
)

data class ConditionConfig(
    val type: String,
    val values: Map<String, String> = emptyMap(),
    val negated: Boolean = false,
)

enum class RegionTrigger(val key: String) {
    ON_ENTER("on_enter"),
    ON_LEAVE("on_leave"),
    ON_DEATH("on_death"),
    ON_KILL("on_kill"),
    ON_RESPAWN("on_respawn"),
    ON_INTERACT("on_interact"),
    ON_COMMAND("on_command"),
    ON_TIMER("on_timer"),
    ON_MODE_START("on_mode_start"),
    ON_MODE_END("on_mode_end"),
    ON_ROLE_ASSIGNED("on_role_assigned"),
    ON_FOUND("on_found"),
    ON_SCORE("on_score"),
    ON_CHECKPOINT("on_checkpoint"),
    ON_FINISH("on_finish"),
    ;

    companion object {
        fun fromKey(key: String): RegionTrigger? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

enum class RuleResult {
    ALLOW,
    DENY,
    PASS,
}

data class ExternalRegion(
    val id: String,
    val name: String,
    val sourceType: String,
)

data class RegionSession(
    val id: UUID,
    val playerId: UUID,
    val regionId: String,
    val enteredAtMillis: Long,
    val activeLeases: MutableList<EffectLease> = ArrayList(),
    val metadata: MutableMap<String, String> = HashMap(),
)

data class EffectLease(
    val id: UUID,
    val playerId: UUID,
    val regionId: String,
    val effectType: String,
    val scope: EffectScope,
    val appliedAtMillis: Long,
    val expiresAtMillis: Long?,
    val snapshot: PlayerStateSnapshot = PlayerStateSnapshot(),
    val metadata: Map<String, String> = emptyMap(),
)

data class PlayerStateSnapshot(
    val values: Map<String, String> = emptyMap(),
)

data class LocationSnapshot(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    companion object {
        fun of(location: Location): LocationSnapshot =
            LocationSnapshot(location.world?.name ?: "", location.x, location.y, location.z)
    }
}

data class ValidationIssue(
    val regionId: String,
    val severity: ValidationSeverity,
    val message: String,
)

enum class ValidationSeverity {
    ERROR,
    WARNING,
}

data class UnionRef(
    val id: String,
    val name: String,
    val providerType: String,
)
