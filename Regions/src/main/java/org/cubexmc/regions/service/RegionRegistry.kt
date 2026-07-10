package org.cubexmc.regions.service

import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.storage.RegionStorage

class RegionRegistry(
    private val storage: RegionStorage,
    private val validator: RegionValidationService,
) {
    fun all(): List<RegionDefinition> = storage.all()

    fun find(id: String): RegionDefinition? = storage.find(id)

    fun put(region: RegionDefinition): ServiceResult {
        val issues = validator.validate(region).filter { it.severity.name == "ERROR" }
        if (issues.isNotEmpty()) {
            return ServiceResult.fail(issues.joinToString("; ") { it.message })
        }
        storage.put(region)
        storage.flushIfDirty()
        return ServiceResult.ok()
    }

    fun remove(id: String): ServiceResult {
        if (!storage.remove(id)) {
            return ServiceResult.fail("Region not found: $id")
        }
        storage.flushIfDirty()
        return ServiceResult.ok()
    }
}
