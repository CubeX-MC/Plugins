package org.cubexmc.contract.gui

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.gui.framework.Menu
import org.cubexmc.contract.gui.framework.MenuRegistry
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.util.Text
import java.math.BigDecimal
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the contract GUI. The hall (`openHall`) is the landing screen; contracts are grouped by
 * their workflow state so players can answer "what needs attention now?" before opening details.
 * Navigation runs through the [framework.Menu]/[framework.MenuRegistry] button framework rather than
 * a central slot switch; text/number entry runs through [ChatInputService].
 */
class ContractGui(private val plugin: ContractPlugin) : Listener {
    private val drafts: MutableMap<UUID, CreateDraft> = HashMap()

    /** Pure presentation helpers (contract item icons, wizard preview). */
    private val render: ContractRenderer = ContractRenderer(plugin)

    /** Routes inventory click/close events to the open [Menu]. Registered as a Bukkit listener. */
    val registry: MenuRegistry = MenuRegistry()

    /** Public-chat text/number entry backend (replaces the old anvil GUIs). */
    val input: ChatInputService = ChatInputService(plugin)

    /**
     * Paper 1.21.6+ Dialog backend for the create form and the sign confirmation; `null` on older
     * Paper builds, where the inventory wizard/confirm GUIs + chat input are used instead. The guard
     * keeps [DialogInputService] from ever classloading where the Dialog API is absent.
     */
    private val dialogs: DialogInputService? = if (DialogSupport.available) DialogInputService(plugin) else null

    // ---- Public entry points -------------------------------------------------------------------

    /** Opens the hall landing screen (public board). */
    fun open(player: Player) {
        openHall(player, HallView.OPEN, 1)
    }

    fun closeSessions() {
        registry.closeAll()
        drafts.clear()
        input.cancelAll()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drafts.remove(event.player.uniqueId)
    }

    // ---- Hall (public board / my contracts / inbox) --------------------------------------------

    fun openHall(player: Player, view: HallView, page: Int) {
        val contracts = hallContracts(player, view)
        val pages = pageCount(contracts.size)
        val current = clampPage(page, pages)
        val menu = Menu(hallTitle(view), 6)
        fillBorder(menu.inventory)

        menu.button(1, viewButton(HallView.OPEN, view, Material.EMERALD, "开放")) { openHall(player, HallView.OPEN, 1) }
        menu.button(2, viewButton(HallView.ACTIVE, view, Material.CLOCK, "进行中")) { openHall(player, HallView.ACTIVE, 1) }
        menu.button(3, viewButton(HallView.SUBMITTED, view, Material.DIAMOND, "待确认")) { openHall(player, HallView.SUBMITTED, 1) }
        menu.button(4, viewButton(HallView.DISPUTED, view, Material.REDSTONE, "申诉中")) { openHall(player, HallView.DISPUTED, 1) }
        menu.button(5, viewButton(HallView.DONE, view, Material.NETHER_STAR, "已完成")) { openHall(player, HallView.DONE, 1) }
        menu.button(6, viewButton(HallView.CLOSED, view, Material.BARRIER, "已关闭")) { openHall(player, HallView.CLOSED, 1) }

        val back = { openHall(player, view, current) }
        val start = (current - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, contracts.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            val contract = contracts[index]
            val label = inboxLabel(player, contract)
            val contractId = contract.id()
            menu.button(slot, render.contractItem(contract, label, progressLabel(player, contract))) {
                plugin.storage().findByPrefix(contractId).ifPresent { found -> openDetails(player, found, false, back) }
            }
        }
        if (contracts.isEmpty()) {
            menu.decoration(22, button(Material.LIME_DYE, "&#69DB7C${emptyHint(view)}", "&#CFD8DC换个筛选或稍后再来看看"))
        }

        menu.button(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 $current/$pages 页")) { if (current > 1) openHall(player, view, current - 1) }
        menu.button(49, button(Material.WRITABLE_BOOK, "&#69DB7C创建合同", "&#CFD8DC发布委托、对赌或合作")) { openWizardType(player) }
        val inboxCount = inboxContracts(player).size
        menu.button(50, button(Material.BELL, "&#F4D03F刷新", "&#CFD8DC当前分栏: &#FFFFFF${contracts.size}", "&#CFD8DC待你处理: &#FFFFFF$inboxCount", "&#CFD8DC红色箭头代表当前轮到你操作")) { openHall(player, view, current) }
        if (player.hasPermission("contract.admin.view")) {
            menu.button(51, button(Material.CRAFTING_TABLE, "&#E63946管理工作台", "&#CFD8DC处理争议、中断结算与全部合同")) { openAdmin(player, AdminFilter.DISPUTED, 1) }
        }
        menu.button(52, button(Material.KNOWLEDGE_BOOK, "&#FFE066帮助", "&#CFD8DC查看命令与资金规则说明")) { sendHelp(player) }
        menu.button(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 $current/$pages 页")) { openHall(player, view, current + 1) }

        registry.open(player, menu)
    }

    fun openAdmin(player: Player, filter: AdminFilter, page: Int) {
        if (!player.hasPermission("contract.admin.view")) {
            player.sendMessage(plugin.lang().message("no-permission"))
            return
        }
        val contracts = adminContracts(filter)
        val pages = pageCount(contracts.size)
        val current = clampPage(page, pages)
        val menu = Menu(ADMIN_TITLE, 6)
        fillBorder(menu.inventory)

        menu.button(1, adminFilterButton(AdminFilter.DISPUTED, filter, Material.REDSTONE, "争议/中断结算")) { openAdmin(player, AdminFilter.DISPUTED, 1) }
        menu.button(2, adminFilterButton(AdminFilter.ACTIVE, filter, Material.CLOCK, "进行中")) { openAdmin(player, AdminFilter.ACTIVE, 1) }
        menu.button(3, adminFilterButton(AdminFilter.ALL, filter, Material.COMPASS, "全部")) { openAdmin(player, AdminFilter.ALL, 1) }

        val back = { openAdmin(player, filter, current) }
        val start = (current - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, contracts.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            val contract = contracts[index]
            val contractId = contract.id()
            menu.button(slot, render.contractItem(contract, adminLabel(contract))) {
                plugin.storage().findByPrefix(contractId).ifPresent { found -> openDetails(player, found, true, back) }
            }
        }

        menu.button(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 $current/$pages 页")) { if (current > 1) openAdmin(player, filter, current - 1) }
        menu.button(49, button(Material.EMERALD, "&#69DB7C返回合同大厅", "&#CFD8DC合同数: &#FFFFFF${contracts.size}")) { open(player) }
        menu.button(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 $current/$pages 页")) { openAdmin(player, filter, current + 1) }

        registry.open(player, menu)
    }

    // ---- Details -------------------------------------------------------------------------------

    private fun openDetails(player: Player, contract: Contract, adminMode: Boolean, back: () -> Unit) {
        val menu = Menu(DETAIL_TITLE_PREFIX + Text.color("&#FFE066#${contract.shortId()}"), 3)
        fillBorder(menu.inventory)
        menu.decoration(13, render.detailItem(contract))

        if (adminMode) {
            renderAdminActions(menu, player, contract, back)
        } else {
            renderParticipantActions(menu, player, contract, back)
        }
        menu.button(22, button(Material.ARROW, "&#FFE066返回", "&#CFD8DC回到上一页")) { back() }

        registry.open(player, menu)
    }

    private fun renderParticipantActions(menu: Menu, player: Player, contract: Contract, back: () -> Unit) {
        val contractId = contract.id()
        fun action(slot: Int, icon: ItemStack) = menu.button(slot, icon, participantHandler(player, contractId, slot, back))

        val mediator = isArbiter(contract, player.uniqueId)
        if (contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            action(10, button(Material.LECTERN, "&#69DB7C接受中间人职责", "&#CFD8DC接受后可在争议或失效时裁决"))
        } else if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
            if (contract.type() == ContractType.SERVICE) {
                action(10, button(Material.EMERALD, "&#69DB7C裁定付款", "&#CFD8DC认定合同有效完成并付款"))
                action(11, button(Material.REDSTONE, "&#E63946裁定退款", "&#CFD8DC认定合同失效并退款"))
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                action(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC甲方获得争议裁决"))
                action(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC乙方获得争议裁决"))
                action(12, button(Material.GOLD_INGOT, "&#FFE066退回双方押注", "&#CFD8DC按失败/失效规则退款"))
            }
        } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
            action(10, button(Material.EMERALD_BLOCK, "&#69DB7C接受邀请", "&#CFD8DC将扣除你的押注并进入进行中", "&#FFE066需要签署确认"))
        } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
            action(10, button(Material.EMERALD_BLOCK, "&#69DB7C接下合同", "&#CFD8DC托管奖金: &#69DB7C${plugin.economy().format(contract.reward())}", "&#FFE066需要签署确认"))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && player.uniqueId == contract.contractorUuid()) {
            action(10, button(Material.DIAMOND, "&#CDE0F5提交完成", "&#CFD8DC等待雇主确认后付款"))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
            action(10, button(Material.EMERALD, "&#69DB7C确认付款", "&#CFD8DC付款: &#69DB7C${plugin.economy().format(contract.payoutAmount())}", "&#FFE066需要签署确认"))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && player.uniqueId == contract.ownerUuid()) {
            action(10, button(Material.EMERALD, "&#69DB7C提前确认付款", "&#E63946⚠ 乙方尚未提交完成", "&#CFD8DC付款: &#69DB7C${plugin.economy().format(contract.payoutAmount())}", "&#FFE066需要签署确认"))
        }
        if (contract.type() == ContractType.PARTNERSHIP && contract.status() == ContractStatus.IN_PROGRESS && isParty(contract, player.uniqueId)) {
            action(10, button(Material.EMERALD, "&#69DB7C确认合作完成", "&#CFD8DC双方确认后按合作规则结算", "&#FFE066需要签署确认"))
        }
        if (contract.type() == ContractType.WAGER && (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED) && isArbiter(contract, player.uniqueId)) {
            action(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC将按对赌规则付款给甲方"))
            action(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC将按对赌规则付款给乙方"))
        }
        if (!contract.status().isFinal() && canCancel(player, contract)) {
            action(15, button(Material.BARRIER, "&#E63946取消合同", "&#CFD8DC按规则退款或转入争议", "&#FFE066需要签署确认"))
        }
        if (canDispute(contract)) {
            action(16, button(Material.REDSTONE_BLOCK, "&#E63946发起争议", "&#CFD8DC点击后在聊天输入原因", "&#CFD8DC60 秒内输入,或输 cancel 取消"))
        }
        if (contract.status() == ContractStatus.DISPUTED && isDisputeInitiator(contract, player.uniqueId)) {
            action(16, button(Material.WRITABLE_BOOK, "&#69DB7C撤销争议", "&#CFD8DC恢复到争议前的状态并继续", "&#CFD8DC仅你(发起者)可撤销"))
        }
    }

    private fun renderAdminActions(menu: Menu, player: Player, contract: Contract, back: () -> Unit) {
        if (contract.status().isFinal()) {
            menu.decoration(11, button(Material.GRAY_DYE, "&#CFD8DC合同已结束", "&#CFD8DC无可执行的管理操作"))
            return
        }
        val contractId = contract.id()
        menu.button(10, button(Material.EMERALD, "&#69DB7C强制付款", "&#CFD8DC按成功结算规则付款", "&#FFE066需要签署确认"), adminHandler(player, contractId, 10, back))
        menu.button(12, button(Material.REDSTONE, "&#E63946强制退款", "&#CFD8DC按失败规则退回托管", "&#FFE066需要签署确认"), adminHandler(player, contractId, 12, back))
        menu.button(14, button(Material.BARRIER, "&#E63946关闭合同", "&#CFD8DC不移动任何资金,需人工核对", "&#FFE066需要签署确认"), adminHandler(player, contractId, 14, back))
    }

    private fun participantHandler(player: Player, contractId: String, slot: Int, back: () -> Unit): (org.bukkit.event.inventory.InventoryClickEvent) -> Unit = {
        plugin.storage().findByPrefix(contractId).ifPresentOrElse({ contract ->
            val id = player.uniqueId
            val mediator = isArbiter(contract, id)
            when {
                slot == 10 && contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted() ->
                    runDirect(player, plugin.contracts().acceptMediation(player, contract), contract, false, back)
                slot == 10 && contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && id == contract.contractorUuid() ->
                    runDirect(player, plugin.contracts().submit(player, contract), contract, false, back)
                slot == 16 && contract.status() == ContractStatus.DISPUTED && isDisputeInitiator(contract, id) ->
                    runDirect(player, plugin.contracts().withdrawDispute(player, contract), contract, false, back)
                slot == 16 && canDispute(contract) ->
                    beginDisputePrompt(player, contract, false, back)
                else -> {
                    val action = detailAction(player, contract, slot, mediator)
                    if (action != null) openConfirm(player, action) { reopenDetails(player, contractId, false, back) }
                }
            }
        }, {
            player.sendMessage(plugin.lang().message("not-found"))
            back()
        })
    }

    private fun adminHandler(player: Player, contractId: String, slot: Int, back: () -> Unit): (org.bukkit.event.inventory.InventoryClickEvent) -> Unit = {
        plugin.storage().findByPrefix(contractId).ifPresentOrElse({ contract ->
            if (!player.hasPermission("contract.admin.settle")) {
                player.sendMessage(plugin.lang().message("no-permission"))
            } else if (!contract.status().isFinal()) {
                val action = when (slot) {
                    10 -> PendingAction.simple(PendingAction.Kind.ADMIN_PAY, contract, null, "管理员强制付款 #${contract.shortId()}", listOf("按合同的成功结算规则向收款方付款。", "系统佣金照常回收。"))
                    12 -> PendingAction.simple(PendingAction.Kind.ADMIN_REFUND, contract, null, "管理员强制退款 #${contract.shortId()}", listOf("按失败规则将托管退回发起方。"))
                    14 -> PendingAction.simple(PendingAction.Kind.ADMIN_CLOSE, contract, null, "管理员关闭合同 #${contract.shortId()}", listOf("仅关闭合同,不移动任何资金。", "资金需另行用 pay/refund 或人工核对处理。"))
                    else -> null
                }
                if (action != null) openConfirm(player, action) { reopenDetails(player, contractId, true, back) }
            }
        }, {
            player.sendMessage(plugin.lang().message("not-found"))
            back()
        })
    }

    private fun detailAction(player: Player, contract: Contract, slot: Int, mediator: Boolean): PendingAction? {
        val status = contract.status()
        if (slot == 10) {
            if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
                if (contract.type() == ContractType.SERVICE) {
                    return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "pay", "中间人裁定付款 #${contract.shortId()}", listOf("认定委托有效完成。", "接单者获得 ${plugin.economy().format(contract.payoutAmount())}。"))
                }
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "a", "中间人裁定甲方胜 #${contract.shortId()}", listOf("甲方获得争议裁决,取得双方押注。"))
            }
            if (status == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, "接受邀请 #${contract.shortId()}", render.acceptConsequences(contract))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, "接下委托 #${contract.shortId()}", listOf("接单本身不扣款。", "完成并经雇主确认后可获得 ${plugin.economy().format(contract.payoutAmount())}。"))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, "确认付款 #${contract.shortId()}", listOf("向接单者支付 ${plugin.economy().format(contract.payoutAmount())}。", "系统回收佣金 ${plugin.economy().format(contract.commissionAmount())}。"))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.IN_PROGRESS && player.uniqueId == contract.ownerUuid()) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, "提前确认付款 #${contract.shortId()}", listOf("⚠ 乙方尚未提交完成,你将提前付款。", "向接单者支付 ${plugin.economy().format(contract.payoutAmount())}。", "系统回收佣金 ${plugin.economy().format(contract.commissionAmount())}。"))
            }
            if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS && isParty(contract, player.uniqueId)) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, "确认合作完成 #${contract.shortId()}", listOf("记录你的确认。", "双方都确认后各自取回押注(扣除佣金)。"))
            }
            if (contract.type() == ContractType.WAGER && isArbiter(contract, player.uniqueId) && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "a", "裁定甲方胜 #${contract.shortId()}", listOf("甲方获得双方押注,扣除系统佣金。"))
            }
        }
        if (slot == 11 && isArbiter(contract, player.uniqueId)) {
            if (contract.type() == ContractType.WAGER) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "b", "裁定乙方胜 #${contract.shortId()}", listOf("乙方获得双方押注,扣除系统佣金。"))
            }
            if (contract.type() == ContractType.SERVICE && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund", "中间人裁定退款 #${contract.shortId()}", listOf("认定委托失效,雇主取回托管。"))
            }
            if (contract.type() == ContractType.PARTNERSHIP && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "b", "中间人裁定乙方胜 #${contract.shortId()}", listOf("乙方获得争议裁决,取得双方押注。"))
            }
        }
        if (slot == 12 && contract.type() == ContractType.PARTNERSHIP && isArbiter(contract, player.uniqueId) && canMediate(contract)) {
            return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund", "中间人退回双方押注 #${contract.shortId()}", listOf("按失败规则各自退回押注。"))
        }
        if (slot == 15 && !contract.status().isFinal() && canCancel(player, contract)) {
            return PendingAction.simple(PendingAction.Kind.CANCEL, contract, null, "取消合同 #${contract.shortId()}", listOf("按当前状态退款或转入争议。", "创建费在普通取消时通常不退还。"))
        }
        return null
    }

    private fun reopenDetails(player: Player, contractId: String, adminMode: Boolean, back: () -> Unit) {
        plugin.storage().findByPrefix(contractId).ifPresentOrElse(
            { openDetails(player, it, adminMode, back) },
            {
                player.sendMessage(plugin.lang().message("not-found"))
                back()
            },
        )
    }

    private fun runDirect(player: Player, result: ServiceResult, fallback: Contract, adminMode: Boolean, back: () -> Unit) {
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C操作成功。"))
            openDetails(player, result.contract() ?: fallback, adminMode, back)
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
        }
    }

    // ---- Create wizard -------------------------------------------------------------------------

    fun openWizardType(player: Player) {
        val menu = Menu(WIZARD_TYPE_TITLE, 3)
        fillBorder(menu.inventory)
        menu.button(11, button(Material.PAPER, "&#69DB7C委托 SERVICE", "&#CFD8DC你出钱托管,公开等待接单者完成", "&#CFD8DC完成后向接单者付款")) { startDraft(player, ContractType.SERVICE) }
        menu.button(13, button(Material.TARGET, "&#69DB7C对赌 WAGER", "&#CFD8DC双方等额押注,指定仲裁者裁决胜负")) { startDraft(player, ContractType.WAGER) }
        menu.button(15, button(Material.AMETHYST_CLUSTER, "&#69DB7C合作 PARTNERSHIP", "&#CFD8DC双方各自押注,双方确认后结算", "&#CFD8DC可选指定中间人")) { startDraft(player, ContractType.PARTNERSHIP) }
        menu.button(22, button(Material.ARROW, "&#FFE066返回合同大厅", "&#CFD8DC回到合同大厅")) { open(player) }
        registry.open(player, menu)
    }

    private fun startDraft(player: Player, type: ContractType) {
        drafts[player.uniqueId] = CreateDraft(type)
        openWizardForm(player)
    }

    fun openWizardForm(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val dialog = dialogs
        if (dialog != null) {
            dialog.createForm(player, draft) { submitCreateForm(player) }
            return
        }
        val menu = Menu(WIZARD_FORM_TITLE, 6)
        fillBorder(menu.inventory)

        menu.decoration(11, button(Material.NAME_TAG, "&#FFE066类型: &#FFFFFF${plugin.lang().type(draft.type())}", "&#CFD8DC如需更换类型请返回上一步"))
        menu.button(13, fieldButton(Material.WRITABLE_BOOK, "标题", draft.title())) { promptTextField(player, "&#FFE066请输入合同标题(输 cancel 取消)") { draft.title(it) } }
        menu.button(15, descriptionButton(draft.description())) { beginDescriptionPrompt(player) }
        if (draft.needsCounterparty()) {
            menu.button(20, fieldButton(Material.PLAYER_HEAD, "对方玩家", draft.counterparty())) { promptTextField(player, "&#FFE066请输入对方玩家名(输 cancel 取消)") { draft.counterparty(it) } }
        }
        menu.button(22, fieldButton(Material.GOLD_INGOT, if (draft.type() == ContractType.SERVICE) "奖金" else "我的押注", draft.amount()?.let { render.trimNumber(it) })) {
            promptNumberField(player, if (draft.type() == ContractType.SERVICE) "&#FFE066请输入奖金金额(输 cancel 取消)" else "&#FFE066请输入我的押注金额(输 cancel 取消)") { draft.amount(it) }
        }
        menu.button(24, fieldButton(Material.LECTERN, if (draft.mediatorRequired()) "仲裁者" else "中间人(可选)", draft.mediator())) {
            promptTextField(player, if (draft.mediatorRequired()) "&#FFE066请输入仲裁者玩家名(输 cancel 取消)" else "&#FFE066请输入中间人玩家名(输 cancel 取消)") { draft.mediator(it) }
        }
        if (draft.needsPartnerStake()) {
            menu.button(29, fieldButton(Material.GOLD_NUGGET, "对方押注", draft.partnerStake()?.let { render.trimNumber(it) })) { promptNumberField(player, "&#FFE066请输入对方押注金额(输 cancel 取消)") { draft.partnerStake(it) } }
        }
        menu.button(31, fieldButton(Material.CLOCK, "有效期(天)", draft.days()?.toString())) { promptNumberField(player, "&#FFE066请输入有效期天数(输 cancel 取消)") { draft.days(Math.round(it).toInt()) } }

        menu.decoration(33, button(Material.MAP, "&#F4D03F条款预览", *render.draftPreview(draft).toTypedArray()))
        menu.button(48, button(Material.ARROW, "&#FFE066上一步", "&#CFD8DC重新选择合同类型")) { openWizardType(player) }

        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays())
        if (validation == null) {
            menu.button(50, button(Material.EMERALD_BLOCK, "&#69DB7C预览并签署创建", "&#CFD8DC进入签署确认页", "&#FFE066需要签署确认")) {
                val recheck = draft.validate(minAmount(), maxAmount(), minDays(), maxDays())
                if (recheck != null) {
                    player.sendMessage(Text.color("&#E63946$recheck"))
                } else {
                    openConfirm(player, PendingAction.create(draft, render.draftPreview(draft))) { openWizardForm(player) }
                }
            }
        } else {
            menu.decoration(50, button(Material.GRAY_DYE, "&#CFD8DC尚不能创建", "&#E63946$validation"))
        }

        registry.open(player, menu)
    }

    // ---- Confirm + execute ---------------------------------------------------------------------

    private fun submitCreateForm(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays())
        if (validation != null) {
            player.sendMessage(Text.color("&#E63946$validation"))
            openWizardForm(player)
        } else {
            openConfirm(player, PendingAction.create(draft, render.draftPreview(draft))) { openWizardForm(player) }
        }
    }

    private fun openConfirm(player: Player, action: PendingAction, onReturn: () -> Unit) {
        val dialog = dialogs
        if (dialog != null) {
            dialog.confirm(player, action.title(), action.consequences(), { executeAction(player, action, onReturn) }, onReturn)
            return
        }
        val menu = Menu(CONFIRM_TITLE, 3)
        fillBorder(menu.inventory)
        val lore = ArrayList<String>()
        lore.add(Text.color("&#FFE066动作: &#FFFFFF${action.title()}"))
        lore.add("")
        for (line in action.consequences()) {
            lore.add(Text.color("&#CFD8DC$line"))
        }
        lore.add("")
        lore.add(Text.color("&#E63946点击右侧签署后将立即生效。"))
        menu.decoration(13, button(Material.PAPER, "&#F4D03F签署确认", *lore.toTypedArray()))
        menu.button(11, button(Material.BARRIER, "&#E63946取消", "&#CFD8DC返回,不执行任何操作")) { onReturn() }
        menu.button(15, button(Material.WRITABLE_BOOK, "&#69DB7C签署执行", "&#CFD8DC点击立即签署并执行此操作", "&#E63946操作不可撤销")) { executeAction(player, action, onReturn) }
        registry.open(player, menu)
    }

    private fun executeAction(player: Player, action: PendingAction, onReturn: () -> Unit) {
        if (action.kind() == PendingAction.Kind.CREATE) {
            val result = executeCreate(player)
            if (result.success()) {
                player.sendMessage(Text.color("&#69DB7C签署成功,操作已完成。"))
                drafts.remove(player.uniqueId)
                val done = result.contract()
                val mineBack = { openHall(player, HallView.OPEN, 1) }
                if (done != null) openDetails(player, done, false, mineBack) else mineBack()
            } else {
                player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
                onReturn()
            }
            return
        }
        val contract = plugin.storage().findByPrefix(action.contractId()).orElse(null)
        if (contract == null) {
            player.sendMessage(plugin.lang().message("not-found"))
            onReturn()
            return
        }
        val result = when (action.kind()) {
            PendingAction.Kind.ACCEPT -> plugin.contracts().accept(player, contract)
            PendingAction.Kind.APPROVE -> plugin.contracts().approve(player, contract)
            PendingAction.Kind.RESOLVE -> plugin.contracts().resolveWager(player, contract, action.arg())
            PendingAction.Kind.MEDIATE -> plugin.contracts().mediate(player, contract, action.arg())
            PendingAction.Kind.CANCEL -> plugin.contracts().cancel(player, contract)
            PendingAction.Kind.ADMIN_PAY -> plugin.contracts().adminPay(contract, player.name)
            PendingAction.Kind.ADMIN_REFUND -> plugin.contracts().adminRefund(contract, player.name)
            PendingAction.Kind.ADMIN_CLOSE -> plugin.contracts().adminClose(contract, player.name)
            PendingAction.Kind.CREATE -> ServiceResult.fail("internal")
        }
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C签署成功,操作已完成。"))
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
        }
        onReturn()
    }

    private fun executeCreate(player: Player): ServiceResult {
        val draft = drafts[player.uniqueId] ?: return ServiceResult.fail("创建草稿已失效,请重新开始")
        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays())
        if (validation != null) {
            return ServiceResult.fail(validation)
        }
        val description = draft.description() ?: ""
        val amount = draft.amount() ?: return ServiceResult.fail("请先填写金额")
        val days = draft.days() ?: return ServiceResult.fail("请先填写期限")
        val title = draft.title() ?: return ServiceResult.fail("请先填写标题")
        return when (draft.type()) {
            ContractType.SERVICE -> plugin.contracts().create(player, amount, days, title, description, emptyToNull(draft.mediator()))
            ContractType.WAGER -> {
                val counterparty = draft.counterparty() ?: return ServiceResult.fail("请先填写对方玩家")
                val mediator = draft.mediator() ?: return ServiceResult.fail("请先填写仲裁者")
                plugin.contracts().createWager(player, counterparty, BigDecimal.valueOf(amount), days, mediator, title, description)
            }
            ContractType.PARTNERSHIP -> {
                val counterparty = draft.counterparty() ?: return ServiceResult.fail("请先填写对方玩家")
                val partnerStake = draft.partnerStake() ?: return ServiceResult.fail("请先填写对方押注")
                plugin.contracts().createPartnership(player, counterparty, BigDecimal.valueOf(amount), BigDecimal.valueOf(partnerStake), days, title, description, emptyToNull(draft.mediator()))
            }
            else -> ServiceResult.fail("不支持的合同类型")
        }
    }

    // ---- Chat-driven inputs --------------------------------------------------------------------

    private fun promptTextField(player: Player, message: String, apply: (String?) -> Unit) {
        input.promptLine(player, Text.color(message), allowClear = false, FIELD_PROMPT_TIMEOUT_MS) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val text = outcome.text.trim()
                apply(if (text.isEmpty()) null else text)
            }
            openWizardForm(player)
        }
    }

    private fun promptNumberField(player: Player, message: String, apply: (Double) -> Unit) {
        input.promptLine(player, Text.color(message), allowClear = false, FIELD_PROMPT_TIMEOUT_MS) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val parsed = parsePositive(outcome.text.trim())
                if (parsed == null) {
                    player.sendMessage(Text.color("&#E63946数字格式不正确,未保存。"))
                } else {
                    apply(parsed)
                }
            }
            openWizardForm(player)
        }
    }

    private fun beginDescriptionPrompt(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val maxLength = plugin.config.getInt("limits.max-description-length", 500)
        input.promptLine(player, plugin.lang().message("description-input-start"), allowClear = true, DESCRIPTION_PROMPT_TIMEOUT_MS) { outcome ->
            when (outcome) {
                is ChatOutcome.Submitted -> {
                    val clean = Text.stripControl(outcome.text)
                    if (clean.length > maxLength) {
                        player.sendMessage(plugin.lang().message("description-input-too-long", mapOf("max" to maxLength.toString())))
                    } else {
                        draft.description(if (clean.isBlank()) null else clean)
                        player.sendMessage(plugin.lang().message("description-input-saved"))
                    }
                }
                ChatOutcome.Cleared -> {
                    draft.description(null)
                    player.sendMessage(plugin.lang().message("description-input-cleared"))
                }
                ChatOutcome.Cancelled -> player.sendMessage(plugin.lang().message("description-input-cancelled"))
                ChatOutcome.TimedOut -> player.sendMessage(plugin.lang().message("description-input-timeout"))
            }
            openWizardForm(player)
        }
    }

    private fun beginDisputePrompt(player: Player, contract: Contract, adminMode: Boolean, back: () -> Unit) {
        val contractId = contract.id()
        input.promptLine(
            player,
            Text.color("&#FFE066请在 60 秒内输入争议原因,或输入 &#E63946cancel &#FFE066取消。"),
            allowClear = false,
            DISPUTE_PROMPT_TIMEOUT_MS,
        ) { outcome ->
            when (outcome) {
                is ChatOutcome.Submitted -> plugin.storage().findByPrefix(contractId).ifPresentOrElse({ current ->
                    val result = plugin.contracts().dispute(player, current, outcome.text)
                    if (result.success()) {
                        player.sendMessage(plugin.lang().message("dispute-success"))
                        openDetails(player, current, adminMode, back)
                    } else {
                        player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
                    }
                }, { player.sendMessage(plugin.lang().message("not-found")) })
                ChatOutcome.Cancelled -> player.sendMessage(Text.color("&#FFE066已取消争议输入。"))
                ChatOutcome.TimedOut -> player.sendMessage(Text.color("&#E63946争议输入超时,已取消。"))
                ChatOutcome.Cleared -> {}
            }
        }
    }

    private fun sendHelp(player: Player) {
        player.closeInventory()
        for (line in plugin.lang().message("help").split(Regex("\\R"))) {
            player.sendMessage(line)
        }
    }

    // ---- Data queries --------------------------------------------------------------------------

    private fun hallContracts(player: Player, view: HallView): List<Contract> = when (view) {
        HallView.OPEN -> plugin.contracts().allContracts().stream()
            .filter { contract -> contract.status() == ContractStatus.OPEN || contract.status() == ContractStatus.PENDING_ACCEPT && contract.relatedTo(player.uniqueId) }
            .toList()
        HallView.ACTIVE -> relatedContracts(player, ContractStatus.IN_PROGRESS)
        HallView.SUBMITTED -> relatedContracts(player, ContractStatus.SUBMITTED)
        HallView.DISPUTED -> relatedContracts(player, ContractStatus.DISPUTED)
        HallView.DONE -> relatedContracts(player, ContractStatus.COMPLETED)
        HallView.CLOSED -> plugin.contracts().allContracts().stream()
            .filter { contract -> contract.relatedTo(player.uniqueId) && (contract.status() == ContractStatus.CANCELLED || contract.status() == ContractStatus.EXPIRED) }
            .toList()
    }

    private fun relatedContracts(player: Player, status: ContractStatus): List<Contract> =
        plugin.contracts().allContracts().stream()
            .filter { contract -> contract.relatedTo(player.uniqueId) && contract.status() == status }
            .toList()

    private fun inboxContracts(player: Player): List<Contract> {
        val result = ArrayList<Contract>()
        for (contract in plugin.contracts().allContracts()) {
            if (inboxLabel(player, contract) != null) {
                result.add(contract)
            }
        }
        return result
    }

    private fun inboxLabel(player: Player, contract: Contract): String? {
        val id = player.uniqueId
        val status = contract.status()
        if (isArbiter(contract, id)) {
            if (!contract.arbiterAccepted()) return "待你接受中间人/仲裁职责"
            if (contract.type() == ContractType.WAGER && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) return "待你裁决对赌"
            if (contract.type() != ContractType.WAGER && status == ContractStatus.DISPUTED) return "待你裁决争议"
        }
        if (status == ContractStatus.PENDING_ACCEPT && id == contract.contractorUuid()) return "待你接受邀请"
        if (contract.type() == ContractType.SERVICE) {
            if (status == ContractStatus.IN_PROGRESS && id == contract.contractorUuid()) return "待你提交完成"
            if (status == ContractStatus.SUBMITTED && id == contract.ownerUuid()) return "待你确认付款"
        }
        if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS && isParty(contract, id)) {
            val approved = contract.metadata.getOrDefault("approved-roles", "")
            val me = contract.participantByUuid(id)
            if (me.isPresent && !approved.contains(me.get().role().name)) return "待你确认合作完成"
        }
        if (status == ContractStatus.DISPUTED && contract.relatedTo(id)) {
            return if (isDisputeInitiator(contract, id)) "你发起的争议 · 可撤销" else "争议处理中"
        }
        return null
    }

    private fun progressLabel(player: Player, contract: Contract): String {
        val id = player.uniqueId
        val status = contract.status()
        val action = inboxLabel(player, contract)
        if (action != null) {
            return action
        }
        if (isArbiter(contract, id) && contract.type() != ContractType.WAGER && contract.arbiterAccepted()) {
            return "中间人已接受,等待争议或双方处理"
        }
        return when (status) {
            ContractStatus.OPEN -> "等待玩家接单"
            ContractStatus.PENDING_ACCEPT -> "等待指定对方接受邀请"
            ContractStatus.IN_PROGRESS -> "执行中,等待接单方完成"
            ContractStatus.SUBMITTED -> "已提交,等待雇主确认"
            ContractStatus.DISPUTED -> "申诉中,等待管理员或中间人处理"
            ContractStatus.COMPLETED -> "已完成并结算"
            ContractStatus.CANCELLED -> "已关闭或撤销"
            ContractStatus.EXPIRED -> "接单超时,已关闭"
        }
    }

    private fun adminContracts(filter: AdminFilter): List<Contract> {
        val result = ArrayList<Contract>()
        for (contract in plugin.contracts().allContracts()) {
            val keep = when (filter) {
                AdminFilter.DISPUTED -> contract.status() == ContractStatus.DISPUTED
                AdminFilter.ACTIVE -> !contract.status().isFinal()
                AdminFilter.ALL -> true
            }
            if (keep) result.add(contract)
        }
        return result
    }

    private fun adminLabel(contract: Contract): String =
        if (contract.status() == ContractStatus.DISPUTED) "争议待处理" else plugin.lang().status(contract.status())

    // ---- Item rendering (menu chrome; contract icons live in ContractRenderer) -----------------

    private fun fieldButton(material: Material, label: String, value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val name = (if (filled) "&#69DB7C" else "&#FFE066") + label
        return button(material, name, "&#CFD8DC当前: &#FFFFFF${if (filled) value else "未填写"}", "&#FFE066点击在聊天输入")
    }

    private fun descriptionButton(value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val name = (if (filled) "&#69DB7C" else "&#FFE066") + "描述"
        return button(Material.BOOK, name, "&#CFD8DC当前: &#FFFFFF${ContractTerms.preview(value)}", "&#FFE066点击后在聊天输入", "&#CFD8DC输入 cancel 取消, clear 清空")
    }

    private fun adminFilterButton(filter: AdminFilter, selected: AdminFilter, material: Material, label: String): ItemStack =
        button(material, (if (filter == selected) "&#69DB7C" else "&#CFD8DC") + label, "&#CFD8DC点击切换分栏")

    private fun viewButton(target: HallView, selected: HallView, material: Material, label: String): ItemStack =
        button(material, (if (target == selected) "&#69DB7C" else "&#CFD8DC") + label, "&#CFD8DC点击切换视图")

    // ---- Predicates / config -------------------------------------------------------------------

    private fun canDispute(contract: Contract): Boolean {
        val status = contract.status()
        return status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED
    }

    private fun canAcceptInvitation(player: Player, contract: Contract): Boolean = player.uniqueId == contract.contractorUuid()

    private fun canCancel(player: Player, contract: Contract): Boolean = contract.participantByUuid(player.uniqueId).isPresent

    private fun canMediate(contract: Contract): Boolean =
        contract.arbiterAccepted() && !contract.status().isFinal() && contract.status() != ContractStatus.OPEN && contract.status() != ContractStatus.PENDING_ACCEPT

    private fun isParty(contract: Contract, uuid: UUID): Boolean =
        contract.participantByUuid(uuid).map { participant -> participant.role() == ParticipantRole.PARTY_A || participant.role() == ParticipantRole.PARTY_B }.orElse(false)

    private fun isArbiter(contract: Contract, uuid: UUID): Boolean = contract.arbiter()?.uuid() == uuid

    private fun isDisputeInitiator(contract: Contract, uuid: UUID): Boolean = contract.metadata["dispute-by"] == uuid.toString()

    private fun pageCount(size: Int): Int = max(1, ceil(size.toDouble() / BOARD_SLOTS.size).toInt())

    private fun clampPage(page: Int, pages: Int): Int = min(max(1, page), pages)

    private fun hallTitle(view: HallView): String = when (view) {
        HallView.OPEN -> BOARD_TITLE
        HallView.ACTIVE -> ACTIVE_TITLE
        HallView.SUBMITTED -> SUBMITTED_TITLE
        HallView.DISPUTED -> DISPUTED_TITLE
        HallView.DONE -> DONE_TITLE
        HallView.CLOSED -> CLOSED_TITLE
    }

    private fun emptyHint(view: HallView): String = when (view) {
        HallView.OPEN -> "暂无可接取合同"
        HallView.ACTIVE -> "暂无进行中的合同"
        HallView.SUBMITTED -> "暂无待确认合同"
        HallView.DISPUTED -> "暂无申诉中的合同"
        HallView.DONE -> "暂无已完成合同"
        HallView.CLOSED -> "暂无已关闭合同"
    }

    private fun minAmount(): Double = plugin.config.getDouble("economy.min-reward", 100.0)

    private fun maxAmount(): Double = plugin.config.getDouble("economy.max-reward", 100000.0)

    private fun minDays(): Int = plugin.config.getInt("limits.min-deadline-days", 1)

    private fun maxDays(): Int = plugin.config.getInt("limits.max-deadline-days", 7)

    enum class HallView { OPEN, ACTIVE, SUBMITTED, DISPUTED, DONE, CLOSED }

    enum class AdminFilter { DISPUTED, ACTIVE, ALL }

    companion object {
        private val BOARD_TITLE = Text.color("&#F4D03F合同 · 开放")
        private val ACTIVE_TITLE = Text.color("&#F4D03F合同 · 进行中")
        private val SUBMITTED_TITLE = Text.color("&#F4D03F合同 · 待确认")
        private val DISPUTED_TITLE = Text.color("&#F4D03F合同 · 申诉中")
        private val DONE_TITLE = Text.color("&#F4D03F合同 · 已完成")
        private val CLOSED_TITLE = Text.color("&#F4D03F合同 · 已关闭")
        private val DETAIL_TITLE_PREFIX = Text.color("&#F4D03F合同详情 ")
        private val WIZARD_TYPE_TITLE = Text.color("&#F4D03F创建合同 · 选择类型")
        private val WIZARD_FORM_TITLE = Text.color("&#F4D03F创建合同 · 填写")
        private val CONFIRM_TITLE = Text.color("&#F4D03F签署确认")
        private val ADMIN_TITLE = Text.color("&#E63946管理工作台")
        private val BOARD_SLOTS = intArrayOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        private const val DISPUTE_PROMPT_TIMEOUT_MS = 60_000L
        private const val DESCRIPTION_PROMPT_TIMEOUT_MS = 120_000L
        private const val FIELD_PROMPT_TIMEOUT_MS = 60_000L

        private fun parsePositive(text: String): Double? =
            try {
                val value = text.toDouble()
                if (value > 0 && value.isFinite()) value else null
            } catch (ex: NumberFormatException) {
                null
            }

        private fun emptyToNull(value: String?): String? = if (value.isNullOrBlank()) null else value
    }
}
