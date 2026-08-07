package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef

enum class AuthorityDenial(val messageKey: String) {
    NOT_RULER("authority-not-ruler"),
    NOT_SOURCE_OWNER("authority-not-source-owner"),
    SOURCE_UNKNOWN("authority-source-unknown"),
    SOURCE_UNAVAILABLE("authority-source-unavailable"),
    SUPERADMIN_REQUIRED("authority-superadmin-required"),
    REGION_FROZEN("authority-region-frozen"),
    OWNERSHIP_CHANGED("authority-ownership-changed"),
}

data class AuthorityDecision(
    val allowed: Boolean,
    val denial: AuthorityDenial? = null,
) {
    companion object {
        fun allow(): AuthorityDecision = AuthorityDecision(true)
        fun deny(reason: AuthorityDenial): AuthorityDecision = AuthorityDecision(false, reason)
    }
}

class RegionAuthorityService(
    private val sources: RegionSourceRegistry,
    private val rulerPermission: String = RULER_PERMISSION,
    private val superAdminPermission: String = SUPERADMIN_PERMISSION,
) {
    fun isRuler(sender: CommandSender): Boolean =
        sender.hasPermission(rulerPermission)

    fun isSuperAdmin(sender: CommandSender): Boolean =
        sender is ConsoleCommandSender || sender.hasPermission(superAdminPermission)

    fun canEnterManagement(sender: CommandSender): AuthorityDecision {
        if (isSuperAdmin(sender) || isRuler(sender)) {
            return AuthorityDecision.allow()
        }
        return AuthorityDecision.deny(AuthorityDenial.NOT_RULER)
    }

    fun canUseGlobalAdministration(sender: CommandSender): AuthorityDecision =
        if (isSuperAdmin(sender)) AuthorityDecision.allow()
        else AuthorityDecision.deny(AuthorityDenial.SUPERADMIN_REQUIRED)

    fun canCreate(sender: CommandSender, sourceRef: RegionSourceRef): AuthorityDecision =
        authorizeSource(sender, sourceRef)

    fun canManage(sender: CommandSender, region: RegionDefinition): AuthorityDecision =
        if (region.lifecycle == RegionLifecycle.FROZEN || region.lifecycle == RegionLifecycle.ARCHIVED) {
            AuthorityDecision.deny(AuthorityDenial.REGION_FROZEN)
        } else if (isSuperAdmin(sender)) {
            AuthorityDecision.allow()
        } else {
            authorizeRegionSource(sender, region)
        }

    fun canView(sender: CommandSender, region: RegionDefinition): AuthorityDecision =
        if (isSuperAdmin(sender)) AuthorityDecision.allow()
        else authorizeRegionSource(sender, region)

    fun visibleRegions(sender: CommandSender, regions: Collection<RegionDefinition>): List<RegionDefinition> {
        if (isSuperAdmin(sender)) {
            return regions.toList()
        }
        return regions.filter { canManage(sender, it).allowed }
    }

    private fun authorizeSource(sender: CommandSender, sourceRef: RegionSourceRef): AuthorityDecision {
        if (isSuperAdmin(sender)) {
            return AuthorityDecision.allow()
        }
        val player = sender as? Player
            ?: return AuthorityDecision.deny(AuthorityDenial.SUPERADMIN_REQUIRED)
        if (!isRuler(player)) {
            return AuthorityDecision.deny(AuthorityDenial.NOT_RULER)
        }
        val source = sources.find(sourceRef.type)
            ?: return AuthorityDecision.deny(AuthorityDenial.SOURCE_UNKNOWN)
        if (!source.isAvailable()) {
            return AuthorityDecision.deny(AuthorityDenial.SOURCE_UNAVAILABLE)
        }
        if (!source.isOwner(sourceRef, player.uniqueId)) {
            return AuthorityDecision.deny(AuthorityDenial.NOT_SOURCE_OWNER)
        }
        return AuthorityDecision.allow()
    }

    private fun authorizeRegionSource(sender: CommandSender, region: RegionDefinition): AuthorityDecision {
        val sourceDecision = authorizeSource(sender, region.source)
        if (!sourceDecision.allowed) return sourceDecision
        val player = sender as? Player ?: return sourceDecision
        val recordedOwner = region.metadata[SOURCE_OWNER_METADATA]?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
        val currentOwner = sources.find(region.source.type)?.ownerId(region.source)
        if (recordedOwner != null && currentOwner != null && recordedOwner != currentOwner) {
            return AuthorityDecision.deny(AuthorityDenial.OWNERSHIP_CHANGED)
        }
        if (recordedOwner != null && recordedOwner != player.uniqueId) {
            return AuthorityDecision.deny(AuthorityDenial.OWNERSHIP_CHANGED)
        }
        return sourceDecision
    }

    companion object {
        const val RULER_PERMISSION = "regions.admin"
        const val SUPERADMIN_PERMISSION = "regions.superadmin"
        const val SOURCE_OWNER_METADATA = "source-owner"
    }
}
