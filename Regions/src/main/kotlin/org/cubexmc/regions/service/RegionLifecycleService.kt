package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.RegionLifecycle

class RegionLifecycleService(private val plugin: RegionsPlugin) {
    fun freeze(sender: CommandSender, regionId: String, reason: String): ServiceResult {
        val authority = plugin.authority().canUseGlobalAdministration(sender)
        if (!authority.allowed) return ServiceResult.fail(authority.denial?.messageKey ?: "no-permission")
        return freezeInternal(regionId, reason, sender)
    }

    fun unfreeze(sender: CommandSender, regionId: String, reason: String): ServiceResult {
        val authority = plugin.authority().canUseGlobalAdministration(sender)
        if (!authority.allowed) return ServiceResult.fail(authority.denial?.messageKey ?: "no-permission")
        val region = plugin.regions().find(regionId) ?: return ServiceResult.fail("Region not found: $regionId")
        if (region.lifecycle != RegionLifecycle.FROZEN) return ServiceResult.fail("Region is not frozen: $regionId")
        val source = plugin.sources().find(region.source.type)
            ?: return ServiceResult.fail("Unknown region source: ${region.source.type}")
        if (!source.isAvailable()) return ServiceResult.fail("Region source is unavailable: ${region.source.type}")
        val currentOwner = source.ownerId(region.source)
        if (region.source.type.equals("lands", ignoreCase = true) && currentOwner == null) {
            return ServiceResult.fail("The bound Lands area has no resolvable owner")
        }
        val nextRevision = region.revision + 1
        val metadata = LinkedHashMap(region.metadata)
        val restoreEnabled = metadata.remove(PREVIOUS_ENABLED_METADATA)?.toBooleanStrictOrNull() ?: true
        currentOwner?.let { metadata[RegionAuthorityService.SOURCE_OWNER_METADATA] = it.toString() }
        val updated = region.copy(
            enabled = restoreEnabled,
            lifecycle = RegionLifecycle.PUBLISHED,
            revision = nextRevision,
            publishedRevision = nextRevision,
            metadata = metadata,
        )
        val result = plugin.regions().put(updated)
        if (result.success) {
            plugin.storage().recordRevision(updated, plugin.config.getInt("publishing.keep-revisions", 20))
            plugin.storage().flushIfDirty()
            plugin.audit().record(sender, regionId, "region.unfreeze", reason)
        }
        return result
    }

    fun reconcile() {
        for (region in plugin.regions().all()) {
            if (region.lifecycle != RegionLifecycle.PUBLISHED) continue
            val source = plugin.sources().find(region.source.type)
            if (source == null || !source.isAvailable()) {
                freezeInternal(region.id, "source-unavailable", null)
                continue
            }
            val owner = source.ownerId(region.source)
            if (region.source.type.equals("lands", ignoreCase = true) && owner == null) {
                freezeInternal(region.id, "source-owner-unresolvable", null)
                continue
            }
            val recorded = region.metadata[RegionAuthorityService.SOURCE_OWNER_METADATA]
            if (owner != null && recorded == null) {
                val metadata = LinkedHashMap(region.metadata)
                metadata[RegionAuthorityService.SOURCE_OWNER_METADATA] = owner.toString()
                plugin.regions().putSystem(region.copy(metadata = metadata))
                plugin.audit().record(null, region.id, "region.owner-snapshot", "lifecycle-reconcile")
            } else if (owner != null && !recorded.equals(owner.toString(), ignoreCase = true)) {
                freezeInternal(region.id, "source-owner-changed", null)
            }
        }
    }

    private fun freezeInternal(regionId: String, reason: String, sender: CommandSender?): ServiceResult {
        val region = plugin.regions().find(regionId) ?: return ServiceResult.fail("Region not found: $regionId")
        if (region.lifecycle == RegionLifecycle.FROZEN) return ServiceResult.ok()
        plugin.combatModes().forceEnd(regionId, "region-frozen:$reason")
        plugin.raceModes().forceEnd(regionId, "region-frozen:$reason")
        plugin.roundModes().forceEnd(regionId, "region-frozen:$reason")
        plugin.sessions().cleanupRegionAll(regionId, "region-frozen:$reason")
        val metadata = LinkedHashMap(region.metadata)
        metadata[PREVIOUS_ENABLED_METADATA] = region.enabled.toString()
        if (region.lifecycle == RegionLifecycle.PUBLISHED) {
            plugin.storage().recordRevision(region, plugin.config.getInt("publishing.keep-revisions", 20))
        }
        val result = plugin.regions().putSystem(region.copy(
            enabled = false,
            lifecycle = RegionLifecycle.FROZEN,
            revision = region.revision + 1,
            metadata = metadata,
        ))
        if (result.success) plugin.audit().record(sender, region.id, "region.freeze", reason)
        return result
    }

    companion object {
        const val PREVIOUS_ENABLED_METADATA = "lifecycle-previous-enabled"
    }
}
