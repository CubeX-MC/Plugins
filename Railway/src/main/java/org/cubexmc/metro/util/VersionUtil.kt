package org.cubexmc.metro.util

import org.bukkit.Bukkit
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 版本兼容工具类
 * 用于检测服务器版本和特性支持
 */
object VersionUtil {
    private val MC_VERSION_PATTERN: Pattern = Pattern.compile("\\bMC:\\s*(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?")
    private val VERSION_PATTERN: Pattern = Pattern.compile("(?<!\\d)(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?!\\d)")
    private val SERVER_VERSION: ServerVersion = parseBukkitVersion(readBukkitVersion())
    private val MAJOR_VERSION: Int = SERVER_VERSION.major
    private val MINOR_VERSION: Int = SERVER_VERSION.minor
    private val PATCH_VERSION: Int = SERVER_VERSION.patch
    private val IS_FOLIA: Boolean = checkClass("io.papermc.paper.threadedregions.RegionizedServer")
    private val IS_PAPER: Boolean = checkClass("com.destroystokyo.paper.PaperConfig") ||
        checkClass("io.papermc.paper.configuration.Configuration")

    private fun readBukkitVersion(): String = try {
        Bukkit.getBukkitVersion()
    } catch (_: RuntimeException) {
        ""
    } catch (_: LinkageError) {
        ""
    }

    @JvmStatic
    fun parseBukkitVersion(rawVersion: String?): ServerVersion {
        if (rawVersion.isNullOrBlank()) {
            return ServerVersion.UNKNOWN
        }

        val mcVersionMatcher = MC_VERSION_PATTERN.matcher(rawVersion)
        if (mcVersionMatcher.find()) {
            return toServerVersion(mcVersionMatcher)
        }

        val versionMatcher = VERSION_PATTERN.matcher(rawVersion)
        if (versionMatcher.find()) {
            return toServerVersion(versionMatcher)
        }

        return ServerVersion.UNKNOWN
    }

    private fun toServerVersion(matcher: Matcher): ServerVersion = ServerVersion(
        parseVersionPart(matcher.group(1)),
        parseVersionPart(matcher.group(2)),
        parseVersionPart(matcher.group(3)),
    )

    private fun parseVersionPart(part: String?): Int {
        if (part.isNullOrBlank()) {
            return 0
        }
        return try {
            part.toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }

    private fun checkClass(className: String): Boolean = try {
        Class.forName(className)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    @JvmStatic
    fun getMajorVersion(): Int = MAJOR_VERSION

    @JvmStatic
    fun getMinorVersion(): Int = MINOR_VERSION

    @JvmStatic
    fun getPatchVersion(): Int = PATCH_VERSION

    @JvmStatic
    fun isVersionAtLeast(major: Int, minor: Int): Boolean = SERVER_VERSION.isAtLeast(major, minor, 0)

    @JvmStatic
    fun isVersionAtLeast(major: Int, minor: Int, patch: Int): Boolean = SERVER_VERSION.isAtLeast(major, minor, patch)

    @JvmStatic
    fun isModernVersion(): Boolean = isVersionAtLeast(1, 20)

    @JvmStatic
    fun isFolia(): Boolean = IS_FOLIA

    @JvmStatic
    fun isPaper(): Boolean = IS_PAPER

    @JvmStatic
    fun getVersionString(): String = SERVER_VERSION.toString()

    data class ServerVersion(val major: Int, val minor: Int, val patch: Int) {
        fun major(): Int = major

        fun minor(): Int = minor

        fun patch(): Int = patch

        fun isAtLeast(requiredMajor: Int, requiredMinor: Int, requiredPatch: Int): Boolean {
            if (major > requiredMajor) return true
            if (major < requiredMajor) return false
            if (minor > requiredMinor) return true
            if (minor < requiredMinor) return false
            return patch >= requiredPatch
        }

        override fun toString(): String = "$major.$minor.$patch"

        companion object {
            @JvmField val UNKNOWN: ServerVersion = ServerVersion(0, 0, 0)
        }
    }
}
