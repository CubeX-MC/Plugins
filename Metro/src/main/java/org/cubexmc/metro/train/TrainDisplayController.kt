package org.cubexmc.metro.train

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.cubexmc.metro.Metro
import org.cubexmc.metro.event.MetroTrainArrivalEvent
import org.cubexmc.metro.event.MetroTrainDepartureEvent
import org.cubexmc.metro.manager.LineManager
import org.cubexmc.metro.model.Line
import org.cubexmc.metro.model.Stop
import org.cubexmc.metro.util.SchedulerUtil
import org.cubexmc.metro.util.SoundUtil
import org.cubexmc.metro.util.TextUtil

class TrainDisplayController(private val plugin: Metro) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTrainArrival(event: MetroTrainArrivalEvent) {
        val passenger = event.passenger
        if (passenger == null || !passenger.isOnline) {
            return
        }

        val targetStop = event.currentStop
        val line = event.line
        val minecart = event.minecart

        if (event.arrivalType == MetroTrainArrivalEvent.ArrivalType.ENTERING) {
            playArrivalSound(passenger)
            playStationArrivalSound(targetStop, passenger)

            if (event.isTerminus()) {
                if (plugin.configFacade.isTerminalStopTitleEnabled) {
                    showTerminalStopInfo(passenger, targetStop, line)
                }
            } else {
                showArriveStopInfo(passenger, targetStop, line)
            }
        } else if (event.arrivalType == MetroTrainArrivalEvent.ArrivalType.DOCKED) {
            if (!event.isTerminus()) {
                showWaitingInfo(passenger, minecart, targetStop, line)
                startWaitingSound(minecart, passenger)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTrainDeparture(event: MetroTrainDepartureEvent) {
        val passenger = event.passenger
        if (passenger == null || !passenger.isOnline) {
            return
        }

        val currentStop = event.currentStop
        val nextStop = event.nextStop
        val line = event.line
        val minecart = event.minecart

        playDepartureSound(passenger)
        if (plugin.configFacade.isDepartureTitleEnabled) {
            showDepartureInfo(passenger, minecart, currentStop, nextStop, line)
        }
    }

    private fun playStationArrivalSound(stop: Stop, passenger: Player) {
        if (!plugin.configFacade.isStationArrivalSoundEnabled) {
            return
        }

        val notes = plugin.configFacade.stationArrivalNotes
        if (notes.isEmpty()) {
            return
        }

        val initialDelay = plugin.configFacade.stationArrivalInitialDelay
        val stopLocation = stop.stopPointLocation
        val world = stopLocation?.world ?: return

        for (player in world.players) {
            if (player == passenger) {
                continue
            }
            if (stop.isInStop(player.location)) {
                SoundUtil.playNoteSequence(plugin, player, notes, initialDelay)
            }
        }
    }

    private fun playArrivalSound(passenger: Player?) {
        if (plugin.configFacade.isArrivalSoundEnabled &&
            plugin.configFacade.arrivalNotes.isNotEmpty() &&
            passenger != null
        ) {
            SoundUtil.playNoteSequence(
                plugin,
                passenger,
                plugin.configFacade.arrivalNotes,
                plugin.configFacade.arrivalInitialDelay,
            )
        }
    }

    private fun playDepartureSound(passenger: Player?) {
        if (plugin.configFacade.isDepartureSoundEnabled &&
            plugin.configFacade.departureNotes.isNotEmpty() &&
            passenger != null
        ) {
            SoundUtil.playNoteSequence(
                plugin,
                passenger,
                plugin.configFacade.departureNotes,
                plugin.configFacade.departureInitialDelay,
            )
        }
    }

    private fun showArriveStopInfo(passenger: Player?, stop: Stop, line: Line) {
        if (passenger == null || !passenger.isOnline) {
            return
        }

        val nextStopId = line.getNextStopId(stop.id)
        val nextStop = if (nextStopId != null) plugin.stopManager.getStop(nextStopId) else null
        val stopIds = line.orderedStopIds
        val terminusStop = if (stopIds.isNotEmpty()) {
            plugin.stopManager.getStop(stopIds[stopIds.size - 1])
        } else {
            null
        }

        showStopInfo(
            passenger,
            line,
            "arrive_stop",
            stop,
            null,
            nextStop,
            terminusStop,
            plugin.configFacade.arriveStopFadeIn,
            plugin.configFacade.arriveStopStay,
            plugin.configFacade.arriveStopFadeOut,
        )
    }

    private fun showTerminalStopInfo(passenger: Player?, stop: Stop, line: Line) {
        if (passenger == null || !passenger.isOnline) {
            return
        }

        showStopInfo(
            passenger,
            line,
            "terminal_stop",
            stop,
            null,
            null,
            stop,
            plugin.configFacade.terminalStopFadeIn,
            plugin.configFacade.terminalStopStay,
            plugin.configFacade.terminalStopFadeOut,
        )
    }

    private fun showDepartureInfo(passenger: Player?, minecart: Minecart?, currentStop: Stop, nextStop: Stop, line: Line) {
        if (passenger == null || !passenger.isOnline) {
            return
        }

        val stopIds = line.orderedStopIds
        val terminusStop = if (stopIds.isNotEmpty()) {
            plugin.stopManager.getStop(stopIds[stopIds.size - 1])
        } else {
            null
        }

        var actionbarTemplate = plugin.configFacade.departureActionbar
        val customTitle = currentStop.getCustomTitle("departure")
        if (customTitle != null && customTitle.containsKey("actionbar")) {
            actionbarTemplate = customTitle.getValue("actionbar")
        }

        if (actionbarTemplate.contains("{countdown}")) {
            startCountdownActionbar(
                passenger,
                minecart,
                line,
                "departure",
                currentStop,
                currentStop,
                nextStop,
                terminusStop,
            )
        }

        showStopInfo(
            passenger,
            line,
            "departure",
            currentStop,
            currentStop,
            nextStop,
            terminusStop,
            plugin.configFacade.departureFadeIn,
            plugin.configFacade.departureStay,
            plugin.configFacade.departureFadeOut,
        )
    }

    private fun showWaitingInfo(passenger: Player?, minecart: Minecart?, currentStop: Stop, line: Line) {
        if (passenger == null || !passenger.isOnline) {
            return
        }
        if (!plugin.configFacade.isWaitingTitleEnabled) {
            return
        }

        val stopManager = plugin.stopManager
        val nextStopId = line.getNextStopId(currentStop.id)
        val nextStop = if (nextStopId != null) stopManager.getStop(nextStopId) else null
        val stopIds = line.orderedStopIds
        val terminusStop = if (stopIds.isNotEmpty()) stopManager.getStop(stopIds[stopIds.size - 1]) else null
        val lineManager = plugin.lineManager

        var titleTemplate = plugin.configFacade.waitingTitle
        var subtitleTemplate = plugin.configFacade.waitingSubtitle

        val customTitle = currentStop.getCustomTitle("waiting")
        if (customTitle != null) {
            if (customTitle.containsKey("title")) {
                titleTemplate = customTitle.getValue("title")
            }
            if (customTitle.containsKey("subtitle")) {
                subtitleTemplate = customTitle.getValue("subtitle")
            }
        }

        var title = TextUtil.replacePlaceholders(titleTemplate, line, currentStop, null, nextStop, terminusStop, lineManager)
        var subtitle = TextUtil.replacePlaceholders(
            subtitleTemplate,
            line,
            currentStop,
            null,
            nextStop,
            terminusStop,
            lineManager,
        )

        title = ChatColor.translateAlternateColorCodes('&', title)
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle)
        passenger.sendTitle(title, subtitle, 0, 1000000, 0)

        var actionbarTemplate = plugin.configFacade.waitingActionbar
        if (customTitle != null && customTitle.containsKey("actionbar")) {
            actionbarTemplate = customTitle.getValue("actionbar")
        }

        if (actionbarTemplate.contains("{countdown}")) {
            startCountdownActionbar(passenger, minecart, line, "waiting", currentStop, null, nextStop, terminusStop)
        } else {
            var actionbarText = TextUtil.replacePlaceholders(
                actionbarTemplate,
                line,
                currentStop,
                null,
                nextStop,
                terminusStop,
                lineManager,
            )
            actionbarText = ChatColor.translateAlternateColorCodes('&', actionbarText)
            passenger.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(actionbarText))
        }
    }

    private fun startCountdownActionbar(
        passenger: Player?,
        minecart: Minecart?,
        line: Line,
        infoType: String,
        mainStop: Stop,
        prevStop: Stop?,
        nextStop: Stop?,
        terminusStop: Stop?,
    ) {
        if (passenger == null || !passenger.isOnline) {
            return
        }

        var actionbarTemplate = ""
        when (infoType) {
            "waiting" -> actionbarTemplate = plugin.configFacade.waitingActionbar
            "departure" -> actionbarTemplate = plugin.configFacade.departureActionbar
            "arrive_stop", "terminal_stop" -> {
                // No default actionbar for these yet, wait for implementation if needed
            }
        }

        val customTitle = mainStop.getCustomTitle(infoType)
        if (customTitle != null && customTitle.containsKey("actionbar")) {
            actionbarTemplate = customTitle.getValue("actionbar")
        }

        val template = actionbarTemplate
        val lineManager = plugin.lineManager
        val totalSeconds = kotlin.math.ceil(plugin.configFacade.cartDepartureDelay / 20.0).toInt()

        for (secondsLeft in totalSeconds downTo 0) {
            val delayTicks = (totalSeconds - secondsLeft) * 20L
            scheduleTrainTask(
                minecart,
                Runnable {
                    if (passenger == null || !passenger.isOnline || passenger.vehicle != minecart) {
                        return@Runnable
                    }

                    var text = template.replace("{countdown}", secondsLeft.toString())
                    text = TextUtil.replacePlaceholders(text, line, mainStop, prevStop, nextStop, terminusStop, lineManager)
                    text = ChatColor.translateAlternateColorCodes('&', text)
                    passenger.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(text))
                },
                delayTicks,
                -1,
            )
        }
    }

    private fun startWaitingSound(minecart: Minecart?, passenger: Player?) {
        if (!plugin.configFacade.isWaitingSoundEnabled ||
            plugin.configFacade.waitingNotes.isEmpty() ||
            passenger == null
        ) {
            return
        }

        scheduleTrainTask(
            minecart,
            Runnable {
                playWaitingSoundOnce(passenger)
            },
            plugin.configFacade.waitingInitialDelay.toLong(),
            -1,
        )

        val interval = plugin.configFacade.waitingSoundInterval
        if (interval <= 0) {
            return
        }

        val repeatTimes = (plugin.configFacade.cartDepartureDelay + interval - 1L) / interval
        for (index in 1..repeatTimes) {
            scheduleTrainTask(
                minecart,
                Runnable {
                    if (passenger.isOnline && passenger.vehicle == minecart) {
                        playWaitingSoundOnce(passenger)
                    }
                },
                plugin.configFacade.waitingInitialDelay + interval * index,
                -1,
            )
        }
    }

    private fun scheduleTrainTask(minecart: Minecart?, task: Runnable, delay: Long, period: Long): Any? {
        if (minecart == null) {
            return null
        }
        val movementTask = TrainMovementTask.getTaskFor(minecart)
        if (movementTask != null) {
            return movementTask.scheduleSessionTask(task, delay, period)
        }
        return SchedulerUtil.entityRun(plugin, minecart, task, delay, period)
    }

    private fun playWaitingSoundOnce(passenger: Player?) {
        if (passenger != null && passenger.isOnline) {
            SoundUtil.playNoteSequence(plugin, passenger, plugin.configFacade.waitingNotes, 0)
        }
    }

    private fun showStopInfo(
        passenger: Player?,
        line: Line,
        infoType: String,
        mainStop: Stop?,
        prevStop: Stop?,
        nextStop: Stop?,
        terminusStop: Stop?,
        fadeIn: Int,
        stay: Int,
        fadeOut: Int,
    ) {
        if (passenger == null || !passenger.isOnline || mainStop == null) {
            return
        }

        val lineManager = plugin.lineManager
        var titleTemplate = ""
        var subtitleTemplate = ""
        var actionbarTemplate = ""

        when (infoType) {
            "arrive_stop" -> {
                titleTemplate = plugin.configFacade.arriveStopTitle
                subtitleTemplate = plugin.configFacade.arriveStopSubtitle
            }

            "terminal_stop" -> {
                titleTemplate = plugin.configFacade.terminalStopTitle
                subtitleTemplate = plugin.configFacade.terminalStopSubtitle
            }

            "departure" -> {
                titleTemplate = plugin.configFacade.departureTitle
                subtitleTemplate = plugin.configFacade.departureSubtitle
                actionbarTemplate = plugin.configFacade.departureActionbar
            }

            "waiting" -> {
                titleTemplate = plugin.configFacade.waitingTitle
                subtitleTemplate = plugin.configFacade.waitingSubtitle
                actionbarTemplate = plugin.configFacade.waitingActionbar
            }
        }

        val customTitle = mainStop.getCustomTitle(infoType)
        if (customTitle != null) {
            if (customTitle.containsKey("title")) {
                titleTemplate = customTitle.getValue("title")
            }
            if (customTitle.containsKey("subtitle")) {
                subtitleTemplate = customTitle.getValue("subtitle")
            }
            if (customTitle.containsKey("actionbar")) {
                actionbarTemplate = customTitle.getValue("actionbar")
            }
        }

        var title = TextUtil.replacePlaceholders(titleTemplate, line, mainStop, prevStop, nextStop, terminusStop, lineManager)
        var subtitle = TextUtil.replacePlaceholders(
            subtitleTemplate,
            line,
            mainStop,
            prevStop,
            nextStop,
            terminusStop,
            lineManager,
        )
        var actionbar = TextUtil.replacePlaceholders(
            actionbarTemplate,
            line,
            mainStop,
            prevStop,
            nextStop,
            terminusStop,
            lineManager,
        )

        title = ChatColor.translateAlternateColorCodes('&', title)
        subtitle = ChatColor.translateAlternateColorCodes('&', subtitle)
        actionbar = ChatColor.translateAlternateColorCodes('&', actionbar)

        passenger.sendTitle(title, subtitle, fadeIn, stay, fadeOut)
        passenger.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(actionbar))
    }
}
