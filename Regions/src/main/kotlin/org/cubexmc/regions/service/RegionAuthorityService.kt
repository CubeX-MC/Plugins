package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import java.util.UUID

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

    /**
     * 全服级管理操作(reload / inspect / cleanup / doctor)。
     *
     * 超管始终通过。此外可以只发 [permission] 这**一个**细粒度节点做定向授权 ——
     * `plugin.yml` 早就声明了 `regions.reload` / `regions.inspect` / `regions.cleanup`,
     * 但此前这里只看超管,给这几个节点等于什么都没给(与 `regions.use` 是同一类死节点)。
     *
     * **不给统治者开口子**:这些是全服级操作,和 [canManage] 的"能管自己的场地"不是一回事。
     * 传 null 表示该操作没有细粒度节点,只有超管能用。
     */
    fun canUseGlobalAdministration(sender: CommandSender, permission: String? = null): AuthorityDecision =
        if (isSuperAdmin(sender) || (permission != null && sender.hasPermission(permission))) {
            AuthorityDecision.allow()
        } else {
            AuthorityDecision.deny(AuthorityDenial.SUPERADMIN_REQUIRED)
        }

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

    /**
     * 谁能发令开赛 / 强制结束一局。
     *
     * 场主（[canManage]）始终可以；此外场主可以在 Mode 页面指定一个裁判团队，名单里的人**额外**获得
     * 这两项操作——和 Residence 里领地主给领地设 admin 是同一个思路。裁判权限仅限开赛和结束，
     * 不含改配置、发布、删场地。
     *
     * 名单存 UUID 而不是玩家名：玩家改名后按名字匹配会失效，更糟的是旧名可能被别人注册走，
     * 等于把发令权悄悄转给了陌生人。
     */
    fun canJudge(sender: CommandSender, region: RegionDefinition): AuthorityDecision {
        val managed = canManage(sender, region)
        if (managed.allowed) return managed
        // 场地冻结/归档后连场主都停权了，裁判自然也不该还能开赛。
        if (managed.denial == AuthorityDenial.REGION_FROZEN) return managed
        val player = sender as? Player ?: return managed
        return if (judgeIds(region).contains(player.uniqueId)) {
            AuthorityDecision.allow()
        } else {
            managed
        }
    }

    /** 解析 `judges` 里的 UUID 名单；解析不出来的条目（例如旧版按名字写的）直接忽略。 */
    fun judgeIds(region: RegionDefinition): Set<UUID> =
        region.mode?.values?.get(JUDGES_KEY)
            ?.split(',', ';')
            ?.mapNotNull { entry -> runCatching { UUID.fromString(entry.trim()) }.getOrNull() }
            ?.toSet()
            .orEmpty()

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

        /** Mode 参数名：逗号分隔的裁判 UUID 名单。 */
        const val JUDGES_KEY = "judges"
    }
}
