package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.cubexmc.regions.gui.GuiText.Ui
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationSeverity

/** The publish confirmation page: draft diff, dependencies, blocking issues and effective rules. */
internal class RegionPublishMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun open(player: Player, regionId: String) {
        val draft = plugin.publishing().draft(regionId) ?: return gui.openDetail(player, regionId)
        val report = plugin.publishing().previewReport(player, regionId) ?: return gui.openDetail(player, regionId)
        val changes = report.changes
        val issues = report.issues
        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.size - errors
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.PUBLISH_PREVIEW, regionId),
            54,
            text.component("gui.publish.title", mapOf("id" to regionId)),
        )

        changes.take(36).forEachIndexed { index, change ->
            val material = when {
                change.before == null -> Material.LIME_DYE
                change.after == null -> Material.RED_DYE
                else -> Material.YELLOW_DYE
            }
            inventory.setItem(
                index,
                text.item(
                    material,
                    "gui.publish.change",
                    mapOf(
                        "path" to change.path,
                        "before" to previewValue(change.before),
                        "after" to previewValue(change.after),
                    ),
                ),
            )
        }
        if (changes.isEmpty()) {
            inventory.setItem(22, text.item(Material.PAPER, "gui.publish.no-changes"))
        }

        inventory.setItem(45, text.item(Material.BARRIER, "gui.publish.back"))
        inventory.setItem(46, dependencyItem(report))
        inventory.setItem(47, issueItem(issues, errors, warnings, regionId))
        inventory.setItem(48, effectiveRulesItem(report))
        inventory.setItem(49, confirmItem(errors, changes.size, draft.revision))
        inventory.setItem(
            50,
            text.item(
                Material.OBSERVER,
                "gui.publish.overlaps",
                mapOf("count" to report.resolution.orderedRegions.size.toString()),
                extraLore = report.resolution.orderedRegions.map {
                    text.text(
                        "gui.publish.overlap-line",
                        mapOf("id" to it.id, "priority" to it.priority.toString(), "source" to it.source.type),
                    )
                },
            ),
        )
        player.openInventory(inventory)
    }

    private fun dependencyItem(report: org.cubexmc.regions.service.PublishingPreview): org.bukkit.inventory.ItemStack {
        val satisfied = report.dependencies.all { it.available }
        val lore = if (report.dependencies.isEmpty()) {
            text.lore("gui.publish.dependencies.none")
        } else {
            report.dependencies.map {
                "${if (it.available) Ui.GREEN else Ui.RED}${it.id}: ${it.detail}"
            }
        }
        return text.named(
            if (satisfied) Material.ENDER_CHEST else Material.TRAPPED_CHEST,
            text.text(if (satisfied) "gui.publish.dependencies.ok" else "gui.publish.dependencies.missing"),
            lore,
        )
    }

    private fun issueItem(
        issues: List<org.cubexmc.regions.model.ValidationIssue>,
        errors: Int,
        warnings: Int,
        regionId: String,
    ): org.bukkit.inventory.ItemStack {
        val material = when {
            errors > 0 -> Material.REDSTONE_BLOCK
            warnings > 0 -> Material.YELLOW_CONCRETE
            else -> Material.LIME_CONCRETE
        }
        val name = if (errors > 0) {
            text.text("gui.publish.blocked", mapOf("errors" to errors.toString()))
        } else {
            text.text("gui.publish.validated", mapOf("warnings" to warnings.toString()))
        }
        val lore = issues.take(5).map { issue ->
            "${if (issue.severity == ValidationSeverity.ERROR) Ui.RED else Ui.YELLOW}${issue.message}"
        } + if (issues.size > 5) {
            listOf(text.text("gui.publish.more-issues", mapOf("count" to (issues.size - 5).toString(), "id" to regionId)))
        } else {
            emptyList()
        }
        return text.named(material, name, lore)
    }

    private fun effectiveRulesItem(report: org.cubexmc.regions.service.PublishingPreview): org.bukkit.inventory.ItemStack {
        val resolution = report.resolution
        val none = text.text("gui.common.none")
        val displayedMode = resolution.primaryModeRegion ?: resolution.orderedRegions.firstOrNull { it.mode != null }
        return text.item(
            Material.COMPARATOR,
            "gui.publish.effective",
            mapOf(
                "mode" to (displayedMode?.let { "${it.id}:${it.mode?.type}" } ?: none),
                "trigger" to (resolution.primaryTriggerRegion?.id ?: none),
                "flags" to resolution.flags.values
                    .joinToString { "${it.key}=${it.config.value}@${it.sourceRegionId}" }
                    .ifBlank { none },
                "effects" to resolution.effects
                    .joinToString { "${it.config.type}@${it.sourceRegionId}" }
                    .ifBlank { none },
            ),
        )
    }

    private fun confirmItem(errors: Int, changeCount: Int, revision: Long): org.bukkit.inventory.ItemStack {
        val truncated = changeCount > 36
        return text.item(
            if (errors > 0) Material.BARRIER else Material.EMERALD_BLOCK,
            if (errors > 0) "gui.publish.fix-first" else "gui.publish.confirm",
            mapOf("revision" to revision.toString(), "count" to changeCount.toString()),
            extraLore = listOf(text.text(if (truncated) "gui.publish.truncated" else "gui.publish.recheck")),
        )
    }

    fun click(player: Player, regionId: String, slot: Int) {
        when (slot) {
            45 -> gui.openDetail(player, regionId)
            49 -> {
                val result = plugin.publishing().publish(player, regionId)
                if (result.success) {
                    text.send(player, "gui.publish.ok", mapOf("id" to regionId))
                } else {
                    text.send(player, "gui.publish.failed", mapOf("reason" to result.reason))
                }
                gui.openDetail(player, regionId)
            }
        }
    }

    fun sendRevisionHistory(player: Player, region: RegionDefinition) {
        val revisions = plugin.publishing().history(region.id)
        text.send(player, "gui.history.header", mapOf("id" to region.id, "count" to revisions.size.toString()))
        if (revisions.isEmpty()) text.send(player, "gui.history.empty")
        revisions.take(20).forEach { snapshot ->
            text.send(
                player,
                "gui.history.line",
                mapOf(
                    "revision" to snapshot.revision.toString(),
                    "name" to snapshot.name,
                    "mode" to (snapshot.mode?.type ?: text.text("gui.common.none")),
                ),
            )
        }
        text.send(player, "gui.history.rollback-hint", mapOf("id" to region.id))
    }

    fun sendValidation(player: Player, region: RegionDefinition) {
        val issues = plugin.validation().validate(region)
        if (issues.isEmpty()) {
            plugin.lang().send(player, "validate-ok")
            return
        }
        plugin.lang().send(player, "validate-header", mapOf("count" to issues.size.toString()))
        for (issue in issues) {
            plugin.lang().send(
                player,
                "validate-line",
                mapOf("id" to issue.regionId, "severity" to issue.severity.name, "message" to issue.message),
            )
        }
    }

    private fun previewValue(value: String?): String {
        if (value == null) return text.text("gui.common.absent")
        return if (value.length <= 80) value else value.take(77) + "..."
    }
}
