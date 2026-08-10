package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import kotlin.math.max

data class PublishingDependency(
    val id: String,
    val available: Boolean,
    val detail: String,
)

data class PublishingPreview(
    val changes: List<RegionChange>,
    val issues: List<ValidationIssue>,
    val dependencies: List<PublishingDependency>,
    val resolution: OverlapResolution,
)

class RegionPublishingService(private val plugin: RegionsPlugin) {
    fun editable(regionId: String): RegionDefinition? =
        plugin.storage().findDraft(regionId) ?: plugin.regions().find(regionId)

    fun draft(regionId: String): RegionDefinition? = plugin.storage().findDraft(regionId)

    fun history(regionId: String): List<RegionDefinition> =
        plugin.storage().revisionHistory(regionId).sortedByDescending { it.revision }

    fun preview(regionId: String): List<RegionChange> {
        val draft = draft(regionId) ?: return emptyList()
        val current = plugin.regions().find(regionId)
        val published = if (current?.lifecycle == RegionLifecycle.PUBLISHED) {
            current
        } else {
            current?.publishedRevision?.let { plugin.storage().findRevision(regionId, it) }
        }
        return RegionDiffService.compare(published, draft)
    }

    fun previewIssues(sender: CommandSender, regionId: String): List<ValidationIssue> {
        val draft = draft(regionId) ?: return emptyList()
        return publishingIssues(sender, draft)
    }

    fun previewReport(sender: CommandSender, regionId: String): PublishingPreview? {
        val candidate = draft(regionId) ?: return null
        val overlapping = plugin.overlaps().overlappingRegions(candidate, plugin.regions().all()) { geometry(it) }
        return PublishingPreview(
            changes = preview(regionId),
            issues = publishingIssues(sender, candidate),
            dependencies = dependencies(candidate),
            resolution = plugin.overlaps().resolve(overlapping + candidate),
        )
    }

    fun publishingIssues(sender: CommandSender, candidate: RegionDefinition): List<ValidationIssue> =
        plugin.validation().validate(candidate) +
            plugin.overlaps().validateCandidate(candidate, plugin.regions().all()) { geometry(it) } +
            geometryCoverageIssues(candidate) +
            dependencyIssues(candidate) +
            publishingPolicyIssues(sender, candidate)

    fun createDraft(sender: CommandSender, candidate: RegionDefinition): ServiceResult {
        if (plugin.regions().find(candidate.id) != null || draft(candidate.id) != null) {
            return ServiceResult.fail("Region already exists: ${candidate.id}")
        }
        val authority = plugin.authority().canCreate(sender, candidate.source)
        if (!authority.allowed) return denied(authority)
        val player = sender as? Player
        val maxRegions = plugin.config.getInt("safety.max-regions-per-player", 5).coerceAtLeast(0)
        if (
            player != null &&
            maxRegions > 0 &&
            !player.hasPermission("regions.bypass.limit") &&
            !plugin.authority().isSuperAdmin(player)
        ) {
            val managed = plugin.regions().all().count { plugin.authority().canView(player, it).allowed }
            if (managed >= maxRegions) {
                return ServiceResult.fail("Region limit reached ($managed/$maxRegions).")
            }
        }
        val draft = candidate.copy(
            lifecycle = RegionLifecycle.DRAFT,
            revision = 1,
            publishedRevision = null,
        )
        plugin.storage().put(draft)
        plugin.storage().putDraft(draft)
        persistOrReload()?.let { return it }
        plugin.audit().record(sender, draft.id, "region.draft.create", details = mapOf("revision" to "1"))
        return ServiceResult.ok()
    }

    fun saveDraft(sender: CommandSender, candidate: RegionDefinition): ServiceResult {
        val current = plugin.regions().find(candidate.id)
            ?: return createDraft(sender, candidate)
        val authority = plugin.authority().canManage(sender, current)
        if (!authority.allowed) return denied(authority)
        val sourceAuthority = plugin.authority().canCreate(sender, candidate.source)
        if (!sourceAuthority.allowed) return denied(sourceAuthority)
        val base = draft(candidate.id) ?: current
        val nextRevision = max(base.revision, current.revision) + 1
        val updated = candidate.copy(
            lifecycle = RegionLifecycle.DRAFT,
            revision = nextRevision,
            publishedRevision = current.publishedRevision,
        )
        runCatching { plugin.trials().stopRegion(updated.id, "draft-updated") }
        plugin.storage().putDraft(updated)
        if (current.lifecycle == RegionLifecycle.DRAFT) plugin.storage().put(updated)
        persistOrReload()?.let { return it }
        plugin.audit().record(
            sender,
            updated.id,
            "region.draft.update",
            details = mapOf("revision" to nextRevision.toString()),
        )
        return ServiceResult.ok()
    }

    fun publish(sender: CommandSender, regionId: String): ServiceResult {
        val draft = draft(regionId) ?: return ServiceResult.fail("Region has no draft: $regionId")
        val authority = plugin.authority().canManage(sender, draft)
        if (!authority.allowed) return denied(authority)
        val issues = previewIssues(sender, regionId).filter { it.severity == ValidationSeverity.ERROR }
        if (issues.isNotEmpty()) return ServiceResult.fail(issues.joinToString("; ") { it.message })
        val current = plugin.regions().find(regionId)
        val nextRevision = max(current?.revision ?: 0, draft.revision).coerceAtLeast(1)
        val published = draft.copy(
            lifecycle = RegionLifecycle.PUBLISHED,
            revision = nextRevision,
            publishedRevision = nextRevision,
        )
        current?.takeIf { it.lifecycle == RegionLifecycle.PUBLISHED }?.let { recordRevision(it) }
        plugin.storage().put(published)
        recordRevision(published)
        plugin.storage().removeDraft(regionId)
        persistOrReload()?.let { return it }
        quiesce(regionId, "region-publish")
        plugin.audit().record(
            sender,
            regionId,
            "region.publish",
            details = mapOf(
                "from-revision" to (current?.publishedRevision?.toString() ?: "none"),
                "to-revision" to nextRevision.toString(),
            ),
        )
        return ServiceResult.ok()
    }

    fun withdraw(sender: CommandSender, regionId: String): ServiceResult {
        val current = plugin.regions().find(regionId) ?: return ServiceResult.fail("Region not found: $regionId")
        val authority = plugin.authority().canManage(sender, current)
        if (!authority.allowed) return denied(authority)
        if (current.lifecycle != RegionLifecycle.PUBLISHED) {
            return ServiceResult.fail("Region is not published: $regionId")
        }
        recordRevision(current)
        val next = current.copy(
            lifecycle = RegionLifecycle.DRAFT,
            revision = current.revision + 1,
            publishedRevision = current.publishedRevision,
        )
        plugin.storage().put(next)
        plugin.storage().putDraft(next)
        persistOrReload()?.let { return it }
        quiesce(regionId, "region-withdraw")
        plugin.audit().record(sender, regionId, "region.withdraw", details = mapOf("revision" to next.revision.toString()))
        return ServiceResult.ok()
    }

    fun archive(sender: CommandSender, regionId: String): ServiceResult {
        val current = plugin.regions().find(regionId) ?: return ServiceResult.fail("Region not found: $regionId")
        val authority = plugin.authority().canManage(sender, current)
        if (!authority.allowed) return denied(authority)
        if (current.lifecycle == RegionLifecycle.PUBLISHED) recordRevision(current)
        val archived = current.copy(
            enabled = false,
            lifecycle = RegionLifecycle.ARCHIVED,
            revision = current.revision + 1,
            publishedRevision = null,
        )
        plugin.storage().put(archived)
        plugin.storage().removeDraft(regionId)
        persistOrReload()?.let { return it }
        quiesce(regionId, "region-archive")
        plugin.audit().record(sender, regionId, "region.archive", details = mapOf("revision" to archived.revision.toString()))
        return ServiceResult.ok()
    }

    fun rollback(sender: CommandSender, regionId: String, targetRevision: Long): ServiceResult {
        val current = plugin.regions().find(regionId) ?: return ServiceResult.fail("Region not found: $regionId")
        val authority = if (current.lifecycle == RegionLifecycle.ARCHIVED || current.lifecycle == RegionLifecycle.FROZEN) {
            plugin.authority().canUseGlobalAdministration(sender)
        } else {
            plugin.authority().canManage(sender, current)
        }
        if (!authority.allowed) return denied(authority)
        val target = plugin.storage().findRevision(regionId, targetRevision)
            ?: return ServiceResult.fail("Revision $targetRevision not found for $regionId")
        val targetSourceAuthority = plugin.authority().canCreate(sender, target.source)
        if (!targetSourceAuthority.allowed) return denied(targetSourceAuthority)
        val issues = publishingIssues(sender, target)
            .filter { it.severity == ValidationSeverity.ERROR }
        if (issues.isNotEmpty()) return ServiceResult.fail(issues.joinToString("; ") { it.message })
        val nextRevision = max(current.revision, history(regionId).maxOfOrNull { it.revision } ?: 0) + 1
        val metadata = LinkedHashMap(target.metadata)
        val targetOwner = plugin.sources().find(target.source.type)?.ownerId(target.source)
        if (targetOwner == null) metadata.remove(RegionAuthorityService.SOURCE_OWNER_METADATA)
        else metadata[RegionAuthorityService.SOURCE_OWNER_METADATA] = targetOwner.toString()
        val restored = target.copy(
            lifecycle = RegionLifecycle.PUBLISHED,
            revision = nextRevision,
            publishedRevision = nextRevision,
            metadata = metadata,
        )
        if (current.lifecycle == RegionLifecycle.PUBLISHED) recordRevision(current)
        plugin.storage().put(restored)
        recordRevision(restored)
        plugin.storage().removeDraft(regionId)
        persistOrReload()?.let { return it }
        quiesce(regionId, "region-rollback")
        plugin.audit().record(
            sender,
            regionId,
            "region.rollback",
            details = mapOf(
                "target-revision" to targetRevision.toString(),
                "new-revision" to nextRevision.toString(),
            ),
        )
        return ServiceResult.ok()
    }

    private fun recordRevision(region: RegionDefinition) {
        plugin.storage().recordRevision(
            region,
            plugin.config.getInt("publishing.keep-revisions", 20),
        )
    }

    private fun quiesce(regionId: String, reason: String) {
        runCatching { plugin.trials().stopRegion(regionId, reason) }
        plugin.combatModes().forceEnd(regionId, reason)
        plugin.raceModes().forceEnd(regionId, reason)
        plugin.roundModes().forceEnd(regionId, reason)
        plugin.sessions().cleanupRegionAll(regionId, reason)
    }

    private fun persistOrReload(): ServiceResult? {
        if (plugin.storage().flushIfDirty()) return null
        plugin.storage().load()
        return ServiceResult.fail("Failed to persist regions.yml; the previous on-disk state was restored.")
    }

    private fun denied(decision: AuthorityDecision): ServiceResult =
        ServiceResult.fail(decision.denial?.messageKey ?: "no-permission")

    private fun geometry(region: RegionDefinition) =
        plugin.sources().find(region.source.type)?.geometry(region.source)

    private fun publishingPolicyIssues(sender: CommandSender, region: RegionDefinition): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (
            region.mode?.type.equals("union_war", ignoreCase = true) &&
            runCatching { plugin.unions().active()?.type }.getOrNull() == "fallback"
        ) {
            issues.add(ValidationIssue(
                region.id,
                ValidationSeverity.ERROR,
                "union_war requires an available UnionProvider; the fallback provider cannot identify unions.",
            ))
        }
        if (!region.allActions().any { it.type.equals("console_command", ignoreCase = true) }) {
            return issues
        }
        issues.add(if (plugin.authority().isSuperAdmin(sender)) {
            ValidationIssue(
                region.id,
                ValidationSeverity.WARNING,
                "This revision contains console_command actions. They run with server-console authority and will be audited.",
            )
        } else {
            ValidationIssue(
                region.id,
                ValidationSeverity.ERROR,
                "console_command is reserved for Regions super-administrators and cannot be published by a ruler.",
            )
        })
        return issues
    }

    private fun dependencyIssues(region: RegionDefinition): List<ValidationIssue> =
        dependencies(region)
            .filterNot { it.available }
            .map { dependency ->
                ValidationIssue(
                    region.id,
                    ValidationSeverity.ERROR,
                    "Required dependency '${dependency.id}' is unavailable: ${dependency.detail}",
                )
            }

    private fun geometryCoverageIssues(candidate: RegionDefinition): List<ValidationIssue> {
        val candidateGeometry = geometry(candidate)
        val unknownSources = plugin.regions().all()
            .asSequence()
            .filter { it.id != candidate.id && it.enabled && it.lifecycle == RegionLifecycle.PUBLISHED }
            .filter { it.source.type != candidate.source.type }
            .filter { candidateGeometry == null || geometry(it) == null }
            .map { it.source.type }
            .toSortedSet()
        if (unknownSources.isEmpty()) return emptyList()
        return listOf(ValidationIssue(
            candidate.id,
            ValidationSeverity.WARNING,
            "Cross-source geometry cannot be statically proven against: ${unknownSources.joinToString()}. " +
                "Runtime priority, Effect combination, and primary Trigger rules remain deterministic.",
        ))
    }

    private fun dependencies(region: RegionDefinition): List<PublishingDependency> {
        val catalog = runCatching { plugin.capabilities() }.getOrNull() ?: return emptyList()
        val requirements = linkedSetOf<String>()
        fun add(kind: CapabilityKind, id: String) {
            catalog.find(kind, id)?.requiredPlugins?.let { requirements.addAll(it) }
        }
        add(CapabilityKind.SOURCE, region.source.type)
        region.mode?.let { add(CapabilityKind.MODE, it.type) }
        region.flags.values.forEach { add(CapabilityKind.FLAG, it.key) }
        region.effects.forEach { add(CapabilityKind.EFFECT, it.type) }
        for (blocks in region.triggers.values) {
            for (block in blocks) {
                block.conditions.forEach { add(CapabilityKind.CONDITION, it.type) }
                (block.thenActions + block.elseActions).forEach { add(CapabilityKind.ACTION, it.type) }
            }
        }
        val pluginManager = runCatching { plugin.server.pluginManager }.getOrNull()
        return requirements.sorted().map { dependency ->
            val available = pluginManager?.getPlugin(dependency)?.isEnabled == true
            PublishingDependency(
                dependency,
                available,
                if (available) "enabled" else "plugin is missing or disabled",
            )
        }
    }

    private fun RegionDefinition.allActions(): Sequence<ActionConfig> =
        triggers.values.asSequence()
            .flatten()
            .flatMap { block -> (block.thenActions + block.elseActions).asSequence() }
}
