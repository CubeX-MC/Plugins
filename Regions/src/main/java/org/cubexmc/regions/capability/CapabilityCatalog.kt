package org.cubexmc.regions.capability

import java.util.Locale

class CapabilityCatalog {
    private val descriptors: MutableMap<Pair<CapabilityKind, String>, CapabilityDescriptor> = LinkedHashMap()

    fun register(descriptor: CapabilityDescriptor) {
        val normalized = descriptor.id.lowercase(Locale.ROOT)
        require(normalized.isNotBlank()) { "Capability id cannot be blank." }
        val key = descriptor.kind to normalized
        require(!descriptors.containsKey(key)) { "Capability ${descriptor.kind}:$normalized is already registered." }
        descriptors[key] = descriptor.copy(id = normalized)
    }

    fun find(kind: CapabilityKind, id: String): CapabilityDescriptor? =
        descriptors[kind to id.lowercase(Locale.ROOT)]

    fun all(kind: CapabilityKind? = null): List<CapabilityDescriptor> =
        descriptors.values.filter { kind == null || it.kind == kind }

    fun stableIds(kind: CapabilityKind): Set<String> =
        all(kind).filter { it.status == CapabilityStatus.STABLE }.mapTo(LinkedHashSet()) { it.id }

    fun validate(kind: CapabilityKind, id: String, values: Map<String, String>): List<CapabilityValidationIssue> {
        val descriptor = find(kind, id)
            ?: return listOf(CapabilityValidationIssue("Unknown $kind capability '$id'."))
        if (descriptor.status != CapabilityStatus.STABLE) {
            return listOf(CapabilityValidationIssue("Capability ${descriptor.kind}:${descriptor.id} is ${descriptor.status}."))
        }
        val normalizedValues = values.mapKeys { it.key.lowercase(Locale.ROOT) }
        val issues = ArrayList<CapabilityValidationIssue>()
        for (parameter in descriptor.parameters) {
            val lookupKeys = listOf(parameter.key.lowercase(Locale.ROOT)) +
                parameter.aliases.map { it.lowercase(Locale.ROOT) }
            val entry = lookupKeys.firstNotNullOfOrNull { key ->
                normalizedValues[key]?.let { key to it }
            }
            if (entry == null) {
                if (parameter.required) {
                    issues.add(CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} requires '${parameter.key}'."))
                }
                continue
            }
            validateValue(descriptor, parameter, entry.second)?.let { issues.add(it) }
        }
        if (descriptor.strictParameters) {
            val accepted = descriptor.parameters.flatMapTo(HashSet()) { it.acceptedKeys() }
            for (key in normalizedValues.keys) {
                if (!accepted.contains(key)) {
                    issues.add(CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} does not support parameter '$key'."))
                }
            }
        }
        return issues
    }

    private fun validateValue(
        descriptor: CapabilityDescriptor,
        parameter: ParameterDescriptor,
        raw: String,
    ): CapabilityValidationIssue? {
        if (raw.isBlank() && !parameter.allowBlank) {
            return if (parameter.required) {
                CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' cannot be blank.")
            } else {
                null
            }
        }
        val number = when (parameter.type) {
            ParameterType.INTEGER -> raw.toLongOrNull()?.toDouble()
            ParameterType.DECIMAL -> raw.toDoubleOrNull()
            else -> null
        }
        when (parameter.type) {
            ParameterType.INTEGER -> if (number == null) {
                return CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be an integer.")
            }
            ParameterType.DECIMAL -> if (number == null || !number.isFinite()) {
                return CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be a finite number.")
            }
            ParameterType.BOOLEAN -> if (raw.toBooleanStrictOrNull() == null) {
                return CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be true or false.")
            }
            ParameterType.ENUM -> if (parameter.allowedValues.none { it.equals(raw, ignoreCase = true) }) {
                return CapabilityValidationIssue(
                    "${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be one of ${parameter.allowedValues.joinToString()}.",
                )
            }
            ParameterType.STRING -> Unit
        }
        if (number != null && parameter.min != null && number < parameter.min) {
            return CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be >= ${parameter.min}.")
        }
        if (number != null && parameter.max != null && number > parameter.max) {
            return CapabilityValidationIssue("${descriptor.kind}:${descriptor.id} parameter '${parameter.key}' must be <= ${parameter.max}.")
        }
        return null
    }
}
