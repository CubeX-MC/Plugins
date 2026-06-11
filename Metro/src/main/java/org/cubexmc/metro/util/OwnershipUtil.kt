package org.cubexmc.metro.util

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Portal
import org.cubexmc.metro.model.Stop
import java.util.Objects
import java.util.UUID

/**
 * 所有权与权限检查工具类，封装线路和站点的管理逻辑。
 */
object OwnershipUtil {
    const val PERMISSION_ADMIN: String = "metro.admin"
    const val PERMISSION_LINE_CREATE: String = "metro.line.create"
    const val PERMISSION_STOP_CREATE: String = "metro.stop.create"
    const val PERMISSION_PORTAL_CREATE: String = "metro.portal.create"

    @JvmStatic
    fun hasAdminBypass(sender: CommandSender): Boolean = sender !is Player || sender.hasPermission(PERMISSION_ADMIN)

    @JvmStatic
    fun canCreateLine(sender: CommandSender): Boolean = hasAdminBypass(sender) || sender.hasPermission(PERMISSION_LINE_CREATE)

    @JvmStatic
    fun canCreateStop(sender: CommandSender): Boolean = hasAdminBypass(sender) || sender.hasPermission(PERMISSION_STOP_CREATE)

    @JvmStatic
    fun canCreatePortal(sender: CommandSender): Boolean = hasAdminBypass(sender) || sender.hasPermission(PERMISSION_PORTAL_CREATE)

    @JvmStatic
    fun isServerOwned(line: Line?): Boolean = line != null && line.owner == null

    @JvmStatic
    fun isServerOwned(stop: Stop?): Boolean = stop != null && stop.owner == null

    @JvmStatic
    fun isServerOwned(portal: Portal?): Boolean = portal != null && portal.owner == null

    @JvmStatic
    fun isLineAdmin(playerId: UUID?, line: Line?): Boolean = line != null && playerId != null && line.admins.contains(playerId)

    @JvmStatic
    fun isStopAdmin(playerId: UUID?, stop: Stop?): Boolean = stop != null && playerId != null && stop.admins.contains(playerId)

    @JvmStatic
    fun isPortalAdmin(playerId: UUID?, portal: Portal?): Boolean = portal != null && playerId != null && portal.admins.contains(playerId)

    @JvmStatic
    fun canManageLine(sender: CommandSender, line: Line?): Boolean {
        if (hasAdminBypass(sender)) {
            return true
        }
        if (sender !is Player || line == null) {
            return false
        }
        if (isServerOwned(line)) {
            return sender.isOp
        }
        return isLineAdmin(sender.uniqueId, line)
    }

    @JvmStatic
    fun canManageStop(sender: CommandSender, stop: Stop?): Boolean {
        if (hasAdminBypass(sender)) {
            return true
        }
        if (sender !is Player || stop == null) {
            return false
        }
        if (isServerOwned(stop)) {
            return sender.isOp
        }
        return isStopAdmin(sender.uniqueId, stop)
    }

    @JvmStatic
    fun canManagePortal(sender: CommandSender, portal: Portal?): Boolean {
        if (hasAdminBypass(sender)) {
            return true
        }
        if (sender !is Player || portal == null) {
            return false
        }
        if (isServerOwned(portal)) {
            return sender.isOp
        }
        return isPortalAdmin(sender.uniqueId, portal)
    }

    @JvmStatic
    fun canModifyLineStops(sender: CommandSender, line: Line?, stop: Stop?): Boolean {
        if (!canManageLine(sender, line) || stop == null) {
            return false
        }
        if (hasAdminBypass(sender)) {
            return true
        }
        val currentLine = line ?: return false
        if (sender !is Player) {
            return false
        }
        val playerId = sender.uniqueId
        if (isStopAdmin(playerId, stop)) {
            return true
        }
        if (isServerOwned(stop)) {
            return sender.isOp
        }
        return stop.isLineAllowed(currentLine.id)
    }

    @JvmStatic
    fun canLinkStopToLine(sender: CommandSender, line: Line?, stop: Stop?): Boolean {
        if (hasAdminBypass(sender)) {
            return true
        }
        if (sender !is Player || line == null || stop == null) {
            return false
        }
        val playerId = sender.uniqueId

        if (isServerOwned(line) && !sender.isOp) {
            return false
        }
        if (isServerOwned(stop)) {
            return sender.isOp
        }
        if (!isLineAdmin(playerId, line)) {
            return false
        }
        if (isStopAdmin(playerId, stop)) {
            return true
        }
        return stop.isLineAllowed(line.id)
    }

    @JvmStatic
    fun canLinkLineWithoutPlayer(line: Line?, stop: Stop?): Boolean {
        if (line == null || stop == null) {
            return false
        }
        if (stop.isLineAllowed(line.id)) {
            return true
        }
        return Objects.equals(stop.owner, line.owner)
    }
}
