package org.cubexmc.regions.capability

import java.util.Locale

enum class CapabilityKind {
    SOURCE,
    MODE,
    FLAG,
    EFFECT,
    ACTION,
    CONDITION,
}

enum class CapabilityStatus {
    STABLE,
    BETA,
    UNAVAILABLE,
    NOT_IMPLEMENTED,
}

enum class CapabilityRisk {
    LOW,
    MEDIUM,
    HIGH,
}

enum class ParameterType {
    STRING,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    ENUM,
}

data class ParameterDescriptor(
    val key: String,
    val type: ParameterType = ParameterType.STRING,
    val required: Boolean = false,
    val aliases: Set<String> = emptySet(),
    val allowedValues: Set<String> = emptySet(),
    val min: Double? = null,
    val max: Double? = null,
    val allowBlank: Boolean = false,
) {
    fun acceptedKeys(): Set<String> =
        (aliases + key).map { it.lowercase(Locale.ROOT) }.toSet()
}

data class CapabilityDescriptor(
    val kind: CapabilityKind,
    val id: String,
    val status: CapabilityStatus = CapabilityStatus.STABLE,
    val risk: CapabilityRisk = CapabilityRisk.LOW,
    val parameters: List<ParameterDescriptor> = emptyList(),
    val requiredPlugins: Set<String> = emptySet(),
    val strictParameters: Boolean = true,
)

data class CapabilityValidationIssue(
    val message: String,
    val error: Boolean = true,
)
