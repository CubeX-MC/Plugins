package org.cubexmc.gui.chat

import java.lang.reflect.Method
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin

/**
 * 在 Paper 上补一条**现代聊天事件**（`io.papermc.paper.event.player.AsyncChatEvent`）的监听，
 * 在 Spigot 上安静地什么都不做。
 *
 * ### 为什么必须反射
 *
 * 两个原因，任何一个单独成立都足够：
 *
 * 1. 编译到 spigot-api 的插件（Metro / Railway / EcoBalancer）根本没有这个事件类。
 *    换成 paper-api 就等于**放弃纯 Spigot 支持**，那是兼容性决策，不该由一次重构顺手做掉。
 * 2. 更硬的一条：这些插件把 `net.kyori` **relocate** 进了自己的命名空间，
 *    所以它们编译期的 `Component` 与服务器传给事件的 `Component` 是**两个不同的类**，
 *    直接调用必然 `NoSuchMethodError`。**relocate 隔离的是类，而事件对象来自服务器。**
 *
 * 本模块自己不依赖 Adventure，反射按名字解析到的就是**服务器那一份**，两边对得上。
 *
 * ### 它解决什么、不解决什么
 *
 * **不**是"让 Paper 改走现代事件"——只要服务器上还有**任何**插件监听 legacy
 * （CMI 很常见，本仓库这几家自己也在监听），Paper 就对全服走 legacy 链路，现代事件一次都不触发。
 * 它保证的是：**两条链路哪条来都能接住**。配合 [ChatInputState] 的去重，同一行不会被处理两次。
 */
object ModernChatBridge {

    private const val EVENT_CLASS = "io.papermc.paper.event.player.AsyncChatEvent"
    private const val COMPONENT_CLASS = "net.kyori.adventure.text.Component"
    private const val SERIALIZER_CLASS = "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer"

    /** 服务器不是 Paper、或 Adventure 形状对不上时为 null——此时只走 legacy。 */
    private val support: Support? by lazy { resolve() }

    /** 本服是否支持现代聊天事件。仅用于日志/诊断。 */
    val isAvailable: Boolean get() = support != null

    /**
     * 注册现代聊天事件监听。[handler] 返回 `true` 表示这行归调用方、必须挡在公屏之外。
     *
     * @return 注册出来的 [Listener]（交给调用方决定何时 [unregister]），Spigot 上为 null。
     */
    fun register(
        plugin: Plugin,
        priority: EventPriority = EventPriority.LOWEST,
        ignoreCancelled: Boolean = true,
        handler: (Player, String) -> Boolean,
    ): Listener? {
        val resolved = support ?: return null
        val listener = object : Listener {}

        val executor = EventExecutor { _, event ->
            if (!resolved.eventType.isInstance(event)) return@EventExecutor
            val player = (event as? PlayerEvent)?.player ?: return@EventExecutor
            val message = resolved.plainText(event) ?: return@EventExecutor
            if (handler(player, message) && event is Cancellable) {
                event.isCancelled = true
            }
        }

        plugin.server.pluginManager.registerEvent(
            resolved.eventType,
            listener,
            priority,
            executor,
            plugin,
            ignoreCancelled,
        )
        return listener
    }

    /** 注销 [register] 返回的监听器；传 null 是合法的空操作。 */
    fun unregister(listener: Listener?) {
        if (listener != null) {
            HandlerList.unregisterAll(listener)
        }
    }

    private fun resolve(): Support? = runCatching {
        val eventType = Class.forName(EVENT_CLASS).asSubclass(Event::class.java)
        val componentType = Class.forName(COMPONENT_CLASS)
        val serializerType = Class.forName(SERIALIZER_CLASS)

        Support(
            eventType = eventType,
            messageMethod = eventType.getMethod("message"),
            serializer = serializerType.getMethod("plainText").invoke(null),
            serializeMethod = serializerType.getMethod("serialize", componentType),
        )
    }.getOrNull()

    private class Support(
        val eventType: Class<out Event>,
        val messageMethod: Method,
        val serializer: Any,
        val serializeMethod: Method,
    ) {
        /** 反射失败不抛给事件总线：宁可放这行走 legacy，也不要炸掉整条聊天链路。 */
        fun plainText(event: Any): String? = runCatching {
            serializeMethod.invoke(serializer, messageMethod.invoke(event)) as? String
        }.getOrNull()
    }
}
