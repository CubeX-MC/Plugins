package org.cubexmc.metro.gui

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.cubexmc.metro.Metro
import org.cubexmc.metro.gui.controller.AddStopController
import org.cubexmc.metro.gui.controller.ConfirmActionController
import org.cubexmc.metro.gui.controller.LineBoardingChoiceController
import org.cubexmc.metro.gui.controller.LineDetailController
import org.cubexmc.metro.gui.controller.LineListController
import org.cubexmc.metro.gui.controller.LineSettingsController
import org.cubexmc.metro.gui.controller.MainMenuController
import org.cubexmc.metro.gui.controller.StopListController
import org.cubexmc.metro.gui.controller.StopSettingsController

/** GUI 事件监听器。 */
class GuiListener(plugin: Metro) : Listener {
    private val addStopController = AddStopController(plugin)
    private val lineBoardingChoiceController = LineBoardingChoiceController(plugin)
    private val lineDetailController = LineDetailController(plugin)
    private val lineListController = LineListController(plugin)
    private val lineSettingsController = LineSettingsController(plugin)
    private val mainMenuController = MainMenuController(plugin)
    private val stopListController = StopListController(plugin)
    private val stopSettingsController = StopSettingsController(plugin)
    private val confirmActionController = ConfirmActionController(plugin)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val inventory = event.inventory
        val holder = inventory.holder as? GuiHolder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        if (slot < 0 || slot >= inventory.size) return

        when (holder.getType()) {
            GuiHolder.GuiType.MAIN_MENU -> mainMenuController.handleClick(player, holder, slot)
            GuiHolder.GuiType.LINE_LIST -> lineListController.handleLineListClick(player, holder, slot, event.isRightClick)
            GuiHolder.GuiType.STOP_LIST -> stopListController.handleStopListClick(player, holder, slot, event.isRightClick)
            GuiHolder.GuiType.LINE_VARIANTS -> lineListController.handleLineVariantsClick(player, holder, slot, event.isRightClick)
            GuiHolder.GuiType.STOP_VARIANTS -> stopListController.handleStopVariantsClick(player, holder, slot, event.isRightClick)
            GuiHolder.GuiType.LINE_DETAIL -> lineDetailController.handleClick(player, holder, slot, event.isRightClick, event.isShiftClick)
            GuiHolder.GuiType.ADD_STOP_LIST -> addStopController.handleAddStopListClick(player, holder, slot)
            GuiHolder.GuiType.ADD_STOP_VARIANTS -> addStopController.handleAddStopVariantsClick(player, holder, slot)
            GuiHolder.GuiType.LINE_BOARDING_CHOICE -> lineBoardingChoiceController.handleClick(player, holder, slot, event.isRightClick)
            GuiHolder.GuiType.LINE_SETTINGS -> lineSettingsController.handleClick(player, holder, slot)
            GuiHolder.GuiType.STOP_SETTINGS -> stopSettingsController.handleClick(player, holder, slot)
            GuiHolder.GuiType.CONFIRM_ACTION -> confirmActionController.handleClick(player, holder, slot)
            GuiHolder.GuiType.STOP_DETAIL -> Unit
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is GuiHolder) event.isCancelled = true
    }
}
