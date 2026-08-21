package org.cubexmc.economy

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.util.UUID

/** 按名字解析账户的结果。 */
sealed interface NameLookup {

    /** 解析成功。[source] 说明走的是哪条路径,用于启动日志。 */
    data class Found(val player: OfflinePlayer, val source: String) : NameLookup

    /**
     * 只拿到 Bukkit 按名字**编造**的离线 UUID —— profile 查询没跑或没查到。
     *
     * 这个 UUID 和验证服务器(LittleSkin / Mojang)发的 v4 UUID 不是同一个账户,
     * 拿它入账就是把钱转进幽灵账户,所以必须和真正解析成功区分开。
     */
    data object Fabricated : NameLookup

    /** 完全解析不出来。 */
    data object Unknown : NameLookup
}

/**
 * 把配置里的账户名/UUID 变成 Vault 能用的 [OfflinePlayer]。
 *
 * 抽成接口是为了让 [VaultEconomy] 的路由逻辑能脱离服务器单测 ——
 * `Bukkit` 的这几个方法都是静态的,mock 不动。
 */
interface OfflinePlayerLookup {

    fun byUuid(uuid: UUID): OfflinePlayer

    fun byName(name: String): NameLookup
}

/**
 * 默认实现。按可信度从高到低找:
 *
 * 1. 在线玩家精确匹配;
 * 2. Paper 的 `Server#getOfflinePlayerIfCached` —— 纯查 usercache,不扫盘、不联网。
 *    Spigot 没有这个方法,所以走反射,拿不到就跳过这一步;
 * 3. 兜底的 `Bukkit.getOfflinePlayer(name)`。**在线模式**下 Paper 会拿这个名字去
 *    profile 服务(挂了 authlib-injector 就是 LittleSkin)查一次并把结果写进 usercache ——
 *    从不登录的银行账户正是靠这一步解析出来的,查到之后服务器自己会记住。
 *
 * 第 3 步的结果要分辨真假:查不到时 Bukkit 不会失败,而是**按名字哈希编造**一个
 * `OfflinePlayer:<name>` 的 v3 UUID。本实现把这个 UUID 自己算一遍做比对 ——
 * 比"看版本号是不是 4"精确,也不受代理服(online-mode=false 但 UUID 是 v4)干扰。
 *
 * 编造出来的 UUID 只有在**离线模式**服务器上才是对的(那台服上所有账户本来就是这么算的)。
 *
 * 注意这里**没有**用 `Bukkit.getOfflinePlayers()`:那个方法每次调用都要列一遍
 * `playerdata` 目录,在主线程上是 O(存档数) 的开销,而且从不登录的账户根本不在里面。
 */
object BukkitOfflinePlayerLookup : OfflinePlayerLookup {

    private val ifCachedMethod: Method? by lazy {
        try {
            Server::class.java.getMethod("getOfflinePlayerIfCached", String::class.java)
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    override fun byUuid(uuid: UUID): OfflinePlayer = Bukkit.getOfflinePlayer(uuid)

    override fun byName(name: String): NameLookup {
        Bukkit.getPlayerExact(name)?.let { return NameLookup.Found(it, "online player") }
        cachedByName(name)?.let { return NameLookup.Found(it, "server profile cache") }

        @Suppress("DEPRECATION") // 在线模式下这一步就是 profile 查询;下面会分辨真假。
        val looked = Bukkit.getOfflinePlayer(name)
        if (looked.uniqueId != fabricatedUuidOf(name)) {
            return NameLookup.Found(looked, "profile lookup")
        }
        // 离线模式服务器上,按名字哈希出来的 UUID 就是这台服上正确的账户标识。
        return if (Bukkit.getServer().onlineMode) NameLookup.Fabricated else NameLookup.Found(looked, "offline-mode name hash")
    }

    /** Bukkit 查不到 profile 时编造 UUID 用的算法,原样复刻一份用于比对。 */
    private fun fabricatedUuidOf(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))

    private fun cachedByName(name: String): OfflinePlayer? {
        val method = ifCachedMethod ?: return null
        return try {
            method.invoke(Bukkit.getServer(), name) as? OfflinePlayer
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}
