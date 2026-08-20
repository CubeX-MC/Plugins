package org.cubexmc.statecharge.gui

import io.papermc.paper.event.player.AsyncChatEvent
import java.math.BigDecimal
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.cubexmc.gui.ItemBuilder
import org.cubexmc.gui.Menu
import org.cubexmc.gui.MenuRegistry
import org.cubexmc.gui.chat.AcceptResult
import org.cubexmc.gui.chat.ChatInputState
import org.cubexmc.gui.chat.ChatOutcome
import org.cubexmc.gui.fillEmpty
import org.cubexmc.statecharge.StateChargePlugin
import org.cubexmc.statecharge.model.StateSpec
import org.cubexmc.statecharge.service.ToggleResult

/**
 * 玩家交易页：一状态一按钮，点击即 toggle；**只显示玩家有权限的状态**。
 *
 * 还有一颗"余额保险"按钮：点开之后在聊天栏输入金额，余额低于它时全部收费状态自动关闭。
 * 聊天输入走 `cubex-gui` 的 [ChatInputState]（超时、`cancel` 关键字、两条聊天链路去重都在里面）。
 *
 * 本插件编译到 paper-api 且不 relocate Adventure，所以现代聊天事件直接 `@EventHandler`，
 * 不需要走 `ModernChatBridge` 的反射。**两个聊天事件都要监听**：只要服务器上有任何插件
 * 监听 legacy，Paper 就对全服走 legacy 链路，只听现代事件会一次都收不到。
 */
class StateShopGui(private val plugin: StateChargePlugin) : Listener {

    private val menus = MenuRegistry()
    private val guardPrompts = ChatInputState<Player>()

    fun registry(): MenuRegistry = menus

    fun open(player: Player) {
        menus.open(player, build(player))
    }

    // ---- 菜单构建 ----

    private fun build(player: Player): Menu {
        val specs = visibleFor(player)
        val rows = (specs.size / 9 + 2).coerceIn(2, 6)
        val menu = Menu(plugin.lang().ui("gui-title"), rows)

        specs.forEachIndexed { index, spec ->
            if (index < (rows - 1) * 9) {
                menu.button(index, iconFor(player, spec)) { toggle(player, spec) }
            }
        }

        menu.button(rows * 9 - 1, guardIcon(player)) { promptGuard(player) }
        // 摆完按钮之后再铺底,顺序反了会把还没放的位置提前占掉。
        menu.fillEmpty(ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(" ").build())
        return menu
    }

    /** 只列出启用的、且玩家有权限的状态。 */
    private fun visibleFor(player: Player): List<StateSpec> =
        plugin.definitions().all().filter { spec ->
            if (!spec.enabled()) {
                return@filter false
            }
            val permission = spec.permission()
            permission == null || player.hasPermission(permission)
        }

    private fun iconFor(player: Player, spec: StateSpec) =
        ItemBuilder(spec.icon())
            .name(
                plugin.lang().ui(
                    if (plugin.storage().isActive(player.uniqueId, spec.id())) "gui-state-on" else "gui-state-off",
                    mapOf("state" to spec.display()),
                ),
            )
            .lore(
                plugin.lang().ui(
                    "gui-state-rate",
                    mapOf(
                        "price" to plugin.economy().format(spec.price()),
                        "duration" to plugin.durationText(spec.unitSeconds()),
                    ),
                ),
                plugin.lang().ui("gui-state-hint"),
            )
            // 开着的状态发光,一眼能看出来
            .apply { if (plugin.storage().isActive(player.uniqueId, spec.id())) glow() }
            .build()

    private fun guardIcon(player: Player) =
        ItemBuilder(Material.SHIELD)
            .name(plugin.lang().ui("gui-guard-name"))
            .lore(
                plugin.lang().ui(
                    "gui-guard-current",
                    mapOf("amount" to plugin.economy().format(plugin.states().guardOf(player.uniqueId))),
                ),
                plugin.lang().ui("gui-guard-hint"),
            )
            .build()

    // ---- 点击处理 ----

    private fun toggle(player: Player, spec: StateSpec) {
        val result = plugin.states().toggle(player, spec.id())
        player.sendMessage(messageFor(result, spec))
        // 重开界面刷新开/关状态与发光
        open(player)
    }

    private fun messageFor(result: ToggleResult, spec: StateSpec): String {
        if (result.success()) {
            return if (result.nowActive()) {
                plugin.lang().message("toggle-on", mapOf("state" to spec.display()))
            } else {
                plugin.lang().message(
                    "toggle-off",
                    mapOf("state" to spec.display(), "price" to plugin.economy().format(result.charged())),
                )
            }
        }
        val key = when (result.failure()) {
            ToggleResult.Failure.UNKNOWN_STATE -> "toggle-unknown-state"
            ToggleResult.Failure.DISABLED -> "toggle-disabled"
            ToggleResult.Failure.NO_PERMISSION -> "toggle-no-permission"
            ToggleResult.Failure.GUARD_REACHED -> "toggle-guard-reached"
            null -> "toggle-unknown-state"
        }
        return plugin.lang().message(key, mapOf("state" to spec.display()))
    }

    private fun promptGuard(player: Player) {
        guardPrompts.open(player.uniqueId, allowClear = true, timeoutMillis = PROMPT_TIMEOUT_MS, payload = player)
        player.closeInventory()
        player.sendMessage(plugin.lang().message("guard-prompt"))
    }

    // ---- 聊天输入 ----

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (capture(event.player, text)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLegacyChat(event: AsyncPlayerChatEvent) {
        if (capture(event.player, event.message)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        guardPrompts.forget(event.player.uniqueId)
    }

    private fun capture(player: Player, message: String): Boolean {
        val playerId = player.uniqueId
        return when (val result = guardPrompts.accept(playerId, message)) {
            AcceptResult.NotOurs -> false
            AcceptResult.AlreadyTaken -> true
            is AcceptResult.Accepted -> {
                plugin.scheduleAtPlayer(player) {
                    guardPrompts.settle(playerId)
                    deliverGuard(it, result.outcome)
                }
                true
            }
        }
    }

    private fun deliverGuard(player: Player, outcome: ChatOutcome) {
        when (outcome) {
            ChatOutcome.Cancelled, ChatOutcome.TimedOut -> {
                player.sendMessage(plugin.lang().message("guard-cancelled"))
                open(player)
            }

            // 输入 clear 表示"取消保险",回到配置默认值
            ChatOutcome.Cleared -> {
                plugin.states().setGuard(player.uniqueId, null)
                player.sendMessage(
                    plugin.lang().message(
                        "guard-reset",
                        mapOf("amount" to plugin.economy().format(plugin.defaultGuard())),
                    ),
                )
                open(player)
            }

            is ChatOutcome.Submitted -> applyGuard(player, outcome.text)
        }
    }

    private fun applyGuard(player: Player, raw: String) {
        val amount = raw.trim().toBigDecimalOrNull()
        if (amount == null || amount.signum() < 0) {
            player.sendMessage(plugin.lang().message("guard-invalid"))
            open(player)
            return
        }
        plugin.states().setGuard(player.uniqueId, amount)
        player.sendMessage(
            plugin.lang().message("guard-set", mapOf("amount" to plugin.economy().format(amount))),
        )
        open(player)
    }

    private fun String.toBigDecimalOrNull(): BigDecimal? =
        try {
            BigDecimal(this)
        } catch (ex: NumberFormatException) {
            null
        }

    private companion object {
        const val PROMPT_TIMEOUT_MS = 30_000L
    }
}
