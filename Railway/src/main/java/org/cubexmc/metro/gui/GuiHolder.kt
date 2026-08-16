package org.cubexmc.metro.gui

import java.util.Collections
import org.bukkit.inventory.Inventory

/** GUI 持有者，用于标识和存储 GUI 数据。 */
class GuiHolder(private val type: GuiType) : NullableInventoryHolder() {
    enum class GuiType {
        MAIN_MENU,
        LINE_LIST,
        STOP_LIST,
        LINE_VARIANTS,
        STOP_VARIANTS,
        LINE_DETAIL,
        STOP_DETAIL,
        ADD_STOP_LIST,
        ADD_STOP_VARIANTS,
        LINE_BOARDING_CHOICE,
        LINE_SETTINGS,
        STOP_SETTINGS,
        CONFIRM_ACTION,
    }

    private val data: MutableMap<String, Any?> = HashMap()
    private var previousView: GuiView? = null
    private var inventory: Inventory? = null

    fun getType(): GuiType = type

    fun setData(key: String, value: Any?) {
        data[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(key: String): T? = data[key] as T?

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(key: String, defaultValue: T): T {
        val value = data[key] ?: return defaultValue
        return value as T
    }

    fun getPreviousView(): GuiView? = previousView

    fun setPreviousView(previousView: GuiView?) {
        this.previousView = previousView
    }

    fun snapshot(): GuiView = GuiView(type, data, previousView)

    fun setInventory(inventory: Inventory?) {
        this.inventory = inventory
    }

    override fun currentInventory(): Inventory? = inventory

    class GuiView internal constructor(
        private val type: GuiType,
        data: Map<String, Any?>,
        private val previousView: GuiView?,
    ) {
        private val data: Map<String, Any?> = Collections.unmodifiableMap(HashMap(data))

        fun getType(): GuiType = type

        @Suppress("UNCHECKED_CAST")
        fun <T> getData(key: String): T? = data[key] as T?

        @Suppress("UNCHECKED_CAST")
        fun <T> getData(key: String, defaultValue: T): T {
            val value = data[key] ?: return defaultValue
            return value as T
        }

        fun getPreviousView(): GuiView? = previousView
    }
}
