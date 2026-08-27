package org.cubexmc.core

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor

/**
 * 事件注册的薄糖层。
 *
 * 它存在的理由**不是**少写几行，而是消灭一整类 bug：手写 `Listener` 时忘记把它绑进
 * [TerminableConsumer] 资源栈，插件 reload / disable 之后监听器还活着，
 * 而且**不会立刻报错** —— 症状要等到下一次事件触发才出现，很难定位。
 * [onEvent] 注册完就自动 `bind`，让这件事不可能发生。
 *
 * 仍然保留裸 `Listener` 的用法：一个类里要处理多个事件、需要 `@EventHandler` 的注解语义、
 * 或者监听器本身有状态时，照旧写 `Listener` 并用 `CubexPlugin.registerListener`。
 * 这层糖只覆盖“一个事件 → 一段逻辑”的常见情形。
 */
object CubexEvents {

    /**
     * 构造只对 [type] 生效的 [EventExecutor]。
     *
     * Bukkit 会把**子类事件**也派发给父类的注册（例如注册 `PlayerEvent` 会收到 `PlayerJoinEvent`），
     * 所以这里必须再做一次类型检查，否则 `handler` 会拿到它并不想要的事件。
     */
    fun <T : Event> executor(type: Class<T>, handler: (T) -> Unit): EventExecutor =
        EventExecutor { _, event ->
            if (type.isInstance(event)) {
                handler(type.cast(event))
            }
        }
}

/**
 * 注册一个事件处理器，并把注销动作绑进插件的资源栈。
 *
 * 返回的 [Terminable] 可以用来**提前**注销（例如一次性监听）；不调用也没关系，
 * 插件 disable 时会连同其它资源一起 LIFO 关闭。
 *
 * 需要在编译期就知道事件类型时用 [onEvent]，本函数是它的非 inline 落点。
 */
fun <T : Event> CubexPlugin.registerEventHandler(
    type: Class<T>,
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    handler: (T) -> Unit,
): Terminable {
    val listener = object : Listener {}
    server.pluginManager.registerEvent(
        type,
        listener,
        priority,
        CubexEvents.executor(type, handler),
        this,
        ignoreCancelled,
    )
    // unregisterAll 可重复调用:Bukkit 在 disable 时也会注销本插件的监听器,两边都跑不会出错。
    return bind(Runnable { HandlerList.unregisterAll(listener) })
}

/**
 * ```
 * onEvent<PlayerJoinEvent> { event ->
 *     messager().send(event.player, text().color("&a欢迎"))
 * }
 * ```
 *
 * 等价于手写 `Listener` + `registerEvent` + `bind(注销动作)`，但少了忘记最后一步的可能。
 */
inline fun <reified T : Event> CubexPlugin.onEvent(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    noinline handler: (T) -> Unit,
): Terminable = registerEventHandler(T::class.java, priority, ignoreCancelled, handler)
