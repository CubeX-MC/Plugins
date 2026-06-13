package org.cubexmc.metro.train

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.megavex.scoreboardlibrary.api.ScoreboardLibrary
import net.megavex.scoreboardlibrary.api.sidebar.Sidebar
import net.megavex.scoreboardlibrary.api.sidebar.component.ComponentSidebarLayout
import net.megavex.scoreboardlibrary.api.sidebar.component.SidebarComponent
import org.bukkit.entity.Player
import org.cubexmc.metro.Metro
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.util.ColorUtil

/**
 * 计分板管理器，用于在玩家乘坐矿车时显示线路信息。
 * 现已重构为基于 scoreboard-library 的数据包虚拟侧边栏实现，兼容 Folia 并极大优化并发性能与兼容性。
 */
class ScoreboardManager(private val plugin: Metro) {
    private val playerSidebars: MutableMap<UUID, Sidebar> = ConcurrentHashMap()
    private val playerCurrentLine: MutableMap<UUID, String> = ConcurrentHashMap()
    private val library: ScoreboardLibrary? = plugin.globalScoreboardLibrary

    /**
     * 为进入站点区域的乘客更新计分板
     */
    fun updateEnteringStopScoreboard(player: Player?, line: Line?, currentStopId: String?) {
        if (!shouldUseScoreboard(player, line)) {
            return
        }
        val nextStopId = line?.getNextStopId(currentStopId)
        updateScoreboardInternal(player ?: return, line ?: return, currentStopId, nextStopId)
    }

    /**
     * 为行驶中的乘客更新计分板（离开站点后）
     */
    fun updateTravelingScoreboard(player: Player?, line: Line?, targetStopId: String?) {
        if (!shouldUseScoreboard(player, line)) {
            return
        }
        updateScoreboardInternal(player ?: return, line ?: return, null, targetStopId)
    }

    /**
     * 为到达终点站的乘客更新计分板
     */
    fun updateTerminalScoreboard(player: Player?, line: Line?, currentStopId: String?) {
        if (!shouldUseScoreboard(player, line)) {
            return
        }
        updateScoreboardInternal(player ?: return, line ?: return, currentStopId, null)
    }

    private fun shouldUseScoreboard(player: Player?, line: Line?): Boolean {
        if (player == null || !player.isOnline) {
            plugin.debug("scoreboard", "shouldUseScoreboard: player null or offline")
            return false
        }
        if (line == null) {
            plugin.debug("scoreboard", "shouldUseScoreboard: line is null")
            return false
        }
        if (library == null) {
            plugin.debug("scoreboard", "shouldUseScoreboard: ScoreboardLibrary is null (failed to load)")
            return false
        }
        val enabled = plugin.configFacade.isScoreboardEnabled
        if (!enabled) {
            plugin.debug("scoreboard", "shouldUseScoreboard: scoreboard disabled in config")
        }
        return enabled
    }

    /**
     * 更新侧边栏核心逻辑
     */
    private fun updateScoreboardInternal(player: Player, line: Line, currentStopId: String?, nextStopId: String?) {
        val playerId = player.uniqueId
        var sidebar = playerSidebars[playerId]

        if (sidebar == null) {
            plugin.debug("scoreboard", "Creating new sidebar for player=${player.name}")
            val scoreboardLibrary = library ?: return
            sidebar = scoreboardLibrary.createSidebar()
            sidebar.addPlayer(player)
            playerSidebars[playerId] = sidebar
        }

        plugin.debug(
            "scoreboard",
            "Updating sidebar for player=${player.name} line=${line.id} current=$currentStopId next=$nextStopId",
        )
        updateLines(sidebar, line, currentStopId, nextStopId)
        playerCurrentLine[playerId] = line.id
    }

    private fun updateLines(sidebar: Sidebar, line: Line, currentStopId: String?, nextStopId: String?) {
        val stopIds = line.orderedStopIds
        val stopManager = plugin.stopManager
        val lineManager = plugin.lineManager
        val serializer = LegacyComponentSerializer.legacySection()

        val styleCurrent = plugin.configFacade.sbStyleCurrent
        val stylePassed = plugin.configFacade.sbStylePassed
        val styleWaitingNext = plugin.configFacade.sbStyleWaitingNext
        val styleMovingNext = plugin.configFacade.sbStyleMovingNext
        val styleTerminal = plugin.configFacade.sbStyleTerminal
        val styleFolding = plugin.configFacade.sbStyleFolding
        val styleNext = plugin.configFacade.sbStyleNext
        val lineSymbol = plugin.configFacade.lineSymbol

        val currentStopIndex = if (currentStopId != null) stopIds.indexOf(currentStopId) else -1
        val nextStopIndex = if (nextStopId != null) stopIds.indexOf(nextStopId) else -1
        val isWaiting = currentStopId != null

        var startIndex = if (isWaiting) currentStopIndex else if (nextStopIndex != -1) nextStopIndex - 1 else 0
        if (startIndex < 0) {
            startIndex = 0
        }

        val dots = stopIds.size > 9
        val maxStations = if (dots) 7 else 8
        val displayStops = ArrayList<String>()
        for (index in startIndex until stopIds.size) {
            if (displayStops.size >= maxStations) {
                break
            }
            displayStops.add(stopIds[index])
        }

        var terminalStopId: String? = null
        if (displayStops.size < stopIds.size - startIndex) {
            terminalStopId = stopIds[stopIds.size - 1]
        }

        val componentBuilder = SidebarComponent.builder()
        for (index in displayStops.indices) {
            val stop = stopManager.getStop(displayStops[index]) ?: continue
            val prefix = if (index == 0) {
                if (isWaiting) styleCurrent else stylePassed
            } else if (index == 1 && !isWaiting) {
                styleMovingNext
            } else {
                if (isWaiting && index == 1) styleWaitingNext else styleNext
            }

            val rawLine = buildStopLine(stop, line, prefix, lineSymbol, lineManager)
            val lineComponent = serializer.deserialize(rawLine)
                .decoration(TextDecoration.ITALIC, false)
            componentBuilder.addComponent(SidebarComponent.staticLine(lineComponent))
        }

        if (terminalStopId != null) {
            if (dots) {
                val foldingComponent = serializer.deserialize(styleFolding)
                    .decoration(TextDecoration.ITALIC, false)
                componentBuilder.addComponent(SidebarComponent.staticLine(foldingComponent))
            }
            val terminalStop = stopManager.getStop(terminalStopId)
            if (terminalStop != null) {
                val rawLine = buildStopLine(terminalStop, line, styleTerminal, lineSymbol, lineManager)
                val lineComponent = serializer.deserialize(rawLine)
                    .decoration(TextDecoration.ITALIC, false)
                componentBuilder.addComponent(SidebarComponent.staticLine(lineComponent))
            }
        }

        val titleComponent = Component.text(line.name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
        val layout = ComponentSidebarLayout(
            SidebarComponent.staticLine(titleComponent),
            componentBuilder.build(),
        )
        layout.apply(sidebar)
    }

    private fun buildStopLine(
        stop: Stop,
        currentLine: Line,
        prefix: String,
        lineSymbol: String,
        lineManager: LineManager,
    ): String {
        val displayName = stop.name
        val transferInfo = StringBuilder()
        val transferableLines = stop.transferableLines
        if (transferableLines != null && transferableLines.isNotEmpty()) {
            for (transferLineId in transferableLines) {
                if (transferLineId == currentLine.id) {
                    continue
                }
                val transferLine = lineManager.getLine(transferLineId)
                if (transferLine != null) {
                    transferInfo.append(ColorUtil.colorize(transferLine.color)).append(lineSymbol).append(" ")
                }
            }
        }
        return prefix + displayName + if (transferInfo.isNotEmpty()) " $transferInfo" else ""
    }

    /**
     * 恢复玩家原有的计分板（如今由于不污染服务端Scoreboard，只需直接关闭并解绑 Sidebar 即可）
     */
    fun clearScoreboard(player: Player?) {
        if (player == null || !player.isOnline) {
            return
        }

        val playerId = player.uniqueId
        val sidebar = playerSidebars.remove(playerId)
        if (sidebar != null) {
            sidebar.removePlayer(player)
            sidebar.close()
        }
        playerCurrentLine.remove(playerId)
    }

    /**
     * 清除玩家的地铁显示内容（包括侧边栏和title）
     */
    fun clearPlayerDisplay(player: Player?) {
        clearScoreboard(player)
        if (player != null && player.isOnline) {
            player.sendTitle("", "", 0, 0, 0)
        }
    }

    /**
     * 插件关闭时销毁全部 Sidebar
     */
    fun shutdown() {
        for (sidebar in playerSidebars.values) {
            sidebar.close()
        }
        playerSidebars.clear()
        playerCurrentLine.clear()
    }
}
