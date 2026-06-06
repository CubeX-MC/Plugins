package org.cubexmc.contract.gui

import net.wesjd.anvilgui.AnvilGUI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.util.Text
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class ContractGui(private val plugin: ContractPlugin) : Listener {
    private val sessions: MutableMap<UUID, Session> = HashMap()
    private val disputePrompts: MutableMap<UUID, DisputePrompt> = ConcurrentHashMap()
    private val descriptionPrompts: MutableMap<UUID, DescriptionPrompt> = ConcurrentHashMap()
    private val drafts: MutableMap<UUID, CreateDraft> = HashMap()
    private val anvilSuppressReopen: MutableSet<UUID> = HashSet()

    fun openHub(player: Player) {
        val inventory = Bukkit.createInventory(null, 54, HUB_TITLE)
        fillBorder(inventory)
        val actionCount = inboxContracts(player).size.toLong()

        inventory.setItem(20, button(Material.WRITABLE_BOOK, "&#69DB7C创建合同", "&#CFD8DC发布委托、对赌或合作", "&#CFD8DC全程 GUI 向导,无需记命令"))
        inventory.setItem(22, button(if (actionCount > 0) Material.BELL else Material.CHEST, "&#FFE066行动收件箱 &#FFFFFF($actionCount)", "&#CFD8DC需要你接受、确认、裁决或处理的合同"))
        inventory.setItem(24, button(Material.EMERALD, "&#69DB7C合同大厅", "&#CFD8DC浏览公开可接取的委托"))
        inventory.setItem(30, button(Material.BOOK, "&#69DB7C我的合同", "&#CFD8DC我发布、接取或参与的全部合同"))
        inventory.setItem(32, button(Material.KNOWLEDGE_BOOK, "&#FFE066帮助", "&#CFD8DC查看命令与资金规则说明"))
        if (player.hasPermission("contract.admin.view")) {
            inventory.setItem(49, button(Material.CRAFTING_TABLE, "&#E63946管理员工作台", "&#CFD8DC处理争议、中断结算与全部合同"))
        }

        sessions[player.uniqueId] = Session.hub()
        player.openInventory(inventory)
    }

    fun openInbox(player: Player, page: Int) {
        val contracts = inboxContracts(player)
        val pages = pageCount(contracts.size)
        val currentPage = clampPage(page, pages)
        val inventory = Bukkit.createInventory(null, 54, INBOX_TITLE)
        fillBorder(inventory)

        val session = Session.list(ViewType.INBOX, currentPage)
        val start = (currentPage - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, contracts.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            val contract = contracts[index]
            inventory.setItem(slot, contractItem(contract, inboxLabel(player, contract)))
            session.slotContracts[slot] = contract.id()
        }
        if (contracts.isEmpty()) {
            inventory.setItem(22, button(Material.LIME_DYE, "&#69DB7C没有待办事项", "&#CFD8DC当前没有需要你处理的合同"))
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 $currentPage/$pages 页"))
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC回到合同工作台"))
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 $currentPage/$pages 页"))

        sessions[player.uniqueId] = session
        player.openInventory(inventory)
    }

    @JvmOverloads
    fun openBoard(player: Player, mode: BoardMode, page: Int, filter: TypeFilter = TypeFilter.ALL) {
        val contracts = contractsFor(player, mode, filter)
        val pages = pageCount(contracts.size)
        val currentPage = clampPage(page, pages)
        val inventory = Bukkit.createInventory(null, 54, if (mode == BoardMode.MINE) MY_TITLE else BOARD_TITLE)
        val session = Session.board(mode, currentPage, filter)

        fillBorder(inventory)
        inventory.setItem(1, filterButton(TypeFilter.ALL, filter, Material.COMPASS, "全部"))
        inventory.setItem(2, filterButton(TypeFilter.SERVICE, filter, Material.PAPER, "委托"))
        inventory.setItem(3, filterButton(TypeFilter.WAGER, filter, Material.TARGET, "对赌"))
        inventory.setItem(4, filterButton(TypeFilter.PARTNERSHIP, filter, Material.AMETHYST_CLUSTER, "合作"))

        val start = (currentPage - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, contracts.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            val contract = contracts[index]
            inventory.setItem(slot, contractItem(contract, null))
            session.slotContracts[slot] = contract.id()
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 $currentPage/$pages 页"))
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC合同数: &#FFFFFF${contracts.size}"))
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 $currentPage/$pages 页"))
        inventory.setItem(47, button(Material.CHEST, "&#69DB7C我的合同", "&#CFD8DC查看我参与的合同"))
        inventory.setItem(51, button(Material.EMERALD, "&#69DB7C公开合同", "&#CFD8DC返回合同大厅"))

        sessions[player.uniqueId] = session
        player.openInventory(inventory)
    }

    fun openAdmin(player: Player, filter: AdminFilter, page: Int) {
        if (!player.hasPermission("contract.admin.view")) {
            player.sendMessage(plugin.lang().message("no-permission"))
            return
        }
        val contracts = adminContracts(filter)
        val pages = pageCount(contracts.size)
        val currentPage = clampPage(page, pages)
        val inventory = Bukkit.createInventory(null, 54, ADMIN_TITLE)
        fillBorder(inventory)
        inventory.setItem(1, adminFilterButton(AdminFilter.DISPUTED, filter, Material.REDSTONE, "争议/中断结算"))
        inventory.setItem(2, adminFilterButton(AdminFilter.ACTIVE, filter, Material.CLOCK, "进行中"))
        inventory.setItem(3, adminFilterButton(AdminFilter.ALL, filter, Material.COMPASS, "全部"))

        val session = Session.admin(filter, currentPage)
        val start = (currentPage - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, contracts.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            val contract = contracts[index]
            inventory.setItem(slot, contractItem(contract, adminLabel(contract)))
            session.slotContracts[slot] = contract.id()
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 $currentPage/$pages 页"))
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC合同数: &#FFFFFF${contracts.size}"))
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 $currentPage/$pages 页"))

        sessions[player.uniqueId] = session
        player.openInventory(inventory)
    }

    fun openDetails(player: Player, contract: Contract, backMode: BoardMode, backPage: Int) {
        openDetails(player, contract, ViewType.BOARD, backMode, backPage, TypeFilter.ALL, false)
    }

    fun openDetails(player: Player, contract: Contract, backMode: BoardMode, backPage: Int, filter: TypeFilter) {
        openDetails(player, contract, ViewType.BOARD, backMode, backPage, filter, false)
    }

    private fun openDetails(
        player: Player,
        contract: Contract,
        origin: ViewType,
        backMode: BoardMode,
        backPage: Int,
        filter: TypeFilter,
        adminMode: Boolean,
    ) {
        val inventory = Bukkit.createInventory(null, 27, DETAIL_TITLE_PREFIX + Text.color("&#FFE066#${contract.shortId()}"))
        fillBorder(inventory)
        inventory.setItem(13, detailItem(contract))

        if (adminMode) {
            renderAdminActions(inventory, contract)
        } else {
            renderParticipantActions(inventory, player, contract)
        }
        inventory.setItem(22, button(Material.ARROW, "&#FFE066返回", "&#CFD8DC回到上一页"))

        sessions[player.uniqueId] = Session.details(origin, backMode, backPage, filter, contract.id(), adminMode)
        player.openInventory(inventory)
    }

    private fun renderParticipantActions(inventory: Inventory, player: Player, contract: Contract) {
        val mediator = isArbiter(contract, player.uniqueId)
        if (contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            inventory.setItem(10, button(Material.LECTERN, "&#69DB7C接受中间人职责", "&#CFD8DC接受后可在争议或失效时裁决"))
        } else if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
            if (contract.type() == ContractType.SERVICE) {
                inventory.setItem(10, button(Material.EMERALD, "&#69DB7C裁定付款", "&#CFD8DC认定合同有效完成并付款"))
                inventory.setItem(11, button(Material.REDSTONE, "&#E63946裁定退款", "&#CFD8DC认定合同失效并退款"))
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC甲方获得争议裁决"))
                inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC乙方获得争议裁决"))
                inventory.setItem(12, button(Material.GOLD_INGOT, "&#FFE066退回双方押注", "&#CFD8DC按失败/失效规则退款"))
            }
        } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接受邀请", "&#CFD8DC将扣除你的押注并进入进行中", "&#FFE066需要铁砧签署确认"))
        } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接下合同", "&#CFD8DC托管奖金: &#69DB7C${plugin.economy().format(contract.reward())}", "&#FFE066需要铁砧签署确认"))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && player.uniqueId == contract.contractorUuid()) {
            inventory.setItem(10, button(Material.DIAMOND, "&#CDE0F5提交完成", "&#CFD8DC等待雇主确认后付款"))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认付款", "&#CFD8DC付款: &#69DB7C${plugin.economy().format(contract.payoutAmount())}", "&#FFE066需要铁砧签署确认"))
        }
        if (contract.type() == ContractType.PARTNERSHIP && contract.status() == ContractStatus.IN_PROGRESS && isParty(contract, player.uniqueId)) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认合作完成", "&#CFD8DC双方确认后按合作规则结算", "&#FFE066需要铁砧签署确认"))
        }
        if (contract.type() == ContractType.WAGER && (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED) && isArbiter(contract, player.uniqueId)) {
            inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC将按对赌规则付款给甲方"))
            inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC将按对赌规则付款给乙方"))
        }
        if (!contract.status().isFinal() && canCancel(player, contract)) {
            inventory.setItem(15, button(Material.BARRIER, "&#E63946取消合同", "&#CFD8DC按规则退款或转入争议", "&#FFE066需要铁砧签署确认"))
        }
        if (canDispute(contract)) {
            inventory.setItem(16, button(Material.REDSTONE_BLOCK, "&#E63946发起争议", "&#CFD8DC点击后在聊天输入原因", "&#CFD8DC60 秒内输入,或输 cancel 取消"))
        }
    }

    private fun renderAdminActions(inventory: Inventory, contract: Contract) {
        if (contract.status().isFinal()) {
            inventory.setItem(13, detailItem(contract))
            inventory.setItem(11, button(Material.GRAY_DYE, "&#CFD8DC合同已结束", "&#CFD8DC无可执行的管理操作"))
            return
        }
        inventory.setItem(10, button(Material.EMERALD, "&#69DB7C强制付款", "&#CFD8DC按成功结算规则付款", "&#FFE066需要铁砧签署确认"))
        inventory.setItem(12, button(Material.REDSTONE, "&#E63946强制退款", "&#CFD8DC按失败规则退回托管", "&#FFE066需要铁砧签署确认"))
        inventory.setItem(14, button(Material.BARRIER, "&#E63946关闭合同", "&#CFD8DC不移动任何资金,需人工核对", "&#FFE066需要铁砧签署确认"))
    }

    fun openWizardType(player: Player) {
        val inventory = Bukkit.createInventory(null, 27, WIZARD_TYPE_TITLE)
        fillBorder(inventory)
        inventory.setItem(11, button(Material.PAPER, "&#69DB7C委托 SERVICE", "&#CFD8DC你出钱托管,公开等待接单者完成", "&#CFD8DC完成后向接单者付款"))
        inventory.setItem(13, button(Material.TARGET, "&#69DB7C对赌 WAGER", "&#CFD8DC双方等额押注,指定仲裁者裁决胜负"))
        inventory.setItem(15, button(Material.AMETHYST_CLUSTER, "&#69DB7C合作 PARTNERSHIP", "&#CFD8DC双方各自押注,双方确认后结算", "&#CFD8DC可选指定中间人"))
        inventory.setItem(22, button(Material.ARROW, "&#FFE066返回工作台", "&#CFD8DC回到合同工作台"))

        sessions[player.uniqueId] = Session.wizardType()
        player.openInventory(inventory)
    }

    fun openWizardForm(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val inventory = Bukkit.createInventory(null, 54, WIZARD_FORM_TITLE)
        fillBorder(inventory)

        inventory.setItem(11, button(Material.NAME_TAG, "&#FFE066类型: &#FFFFFF${plugin.lang().type(draft.type())}", "&#CFD8DC如需更换类型请返回上一步"))
        inventory.setItem(13, fieldButton(Material.WRITABLE_BOOK, "标题", draft.title()))
        inventory.setItem(15, descriptionButton(draft.description()))
        if (draft.needsCounterparty()) {
            inventory.setItem(20, fieldButton(Material.PLAYER_HEAD, "对方玩家", draft.counterparty()))
        }
        inventory.setItem(22, fieldButton(Material.GOLD_INGOT, if (draft.type() == ContractType.SERVICE) "奖金" else "我的押注", draft.amount()?.let { trimNumber(it) }))
        inventory.setItem(24, fieldButton(Material.LECTERN, if (draft.mediatorRequired()) "仲裁者" else "中间人(可选)", draft.mediator()))
        if (draft.needsPartnerStake()) {
            inventory.setItem(29, fieldButton(Material.GOLD_NUGGET, "对方押注", draft.partnerStake()?.let { trimNumber(it) }))
        }
        inventory.setItem(31, fieldButton(Material.CLOCK, "期限(小时)", draft.hours()?.toString()))

        val preview = ArrayList<String>()
        for (line in draftPreview(draft)) {
            preview.add(line)
        }
        inventory.setItem(33, button(Material.MAP, "&#F4D03F条款预览", *preview.toTypedArray()))

        inventory.setItem(48, button(Material.ARROW, "&#FFE066上一步", "&#CFD8DC重新选择合同类型"))
        val validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours())
        if (validation == null) {
            inventory.setItem(50, button(Material.EMERALD_BLOCK, "&#69DB7C预览并签署创建", "&#CFD8DC进入签署确认页", "&#FFE066需要铁砧签署确认"))
        } else {
            inventory.setItem(50, button(Material.GRAY_DYE, "&#CFD8DC尚不能创建", "&#E63946$validation"))
        }

        sessions[player.uniqueId] = Session.wizardForm()
        player.openInventory(inventory)
    }

    private fun openConfirm(player: Player, action: PendingAction, origin: Session) {
        val inventory = Bukkit.createInventory(null, 27, CONFIRM_TITLE)
        fillBorder(inventory)
        val lore = ArrayList<String>()
        lore.add(Text.color("&#FFE066动作: &#FFFFFF${action.title()}"))
        lore.add("")
        for (line in action.consequences()) {
            lore.add(Text.color("&#CFD8DC$line"))
        }
        lore.add("")
        lore.add(Text.color("&#E63946点击签署后将立即生效。"))
        inventory.setItem(13, button(Material.PAPER, "&#F4D03F签署确认", *lore.toTypedArray()))
        inventory.setItem(11, button(Material.BARRIER, "&#E63946取消", "&#CFD8DC返回,不执行任何操作"))
        inventory.setItem(15, button(Material.WRITABLE_BOOK, "&#69DB7C铁砧签署", "&#CFD8DC点击打开铁砧", "&#CFD8DC输入你的名字或 \"同意\" 完成签署"))

        sessions[player.uniqueId] = Session.confirm(action, origin)
        player.openInventory(inventory)
    }

    fun closeSessions() {
        for (playerId in ArrayList(sessions.keys)) {
            val player = Bukkit.getPlayer(playerId)
            if (player != null && isManagedTitle(player.openInventory.title)) {
                player.closeInventory()
            }
        }
        sessions.clear()
        disputePrompts.clear()
        descriptionPrompts.clear()
        drafts.clear()
        anvilSuppressReopen.clear()
    }

    @EventHandler(priority = EventPriority.NORMAL)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!isManagedTitle(event.view.title)) {
            return
        }
        event.isCancelled = true
        val session = sessions[player.uniqueId] ?: return
        val slot = event.rawSlot
        if (slot < 0 || slot >= event.view.topInventory.size) {
            return
        }
        when (session.type) {
            ViewType.HUB -> handleHubClick(player, slot)
            ViewType.INBOX -> handleInboxClick(player, session, slot)
            ViewType.BOARD -> handleBoardClick(player, session, slot)
            ViewType.ADMIN -> handleAdminClick(player, session, slot)
            ViewType.DETAILS -> handleDetailsClick(player, session, slot)
            ViewType.WIZARD_TYPE -> handleWizardTypeClick(player, slot)
            ViewType.WIZARD_FORM -> handleWizardFormClick(player, slot)
            ViewType.CONFIRM -> handleConfirmClick(player, session, slot)
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val playerId = event.player.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(playerId)
            if (player == null || !isManagedTitle(player.openInventory.title)) {
                sessions.remove(playerId)
            }
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        sessions.remove(playerId)
        disputePrompts.remove(playerId)
        descriptionPrompts.remove(playerId)
        drafts.remove(playerId)
        anvilSuppressReopen.remove(playerId)
    }

    private fun handleHubClick(player: Player, slot: Int) {
        when (slot) {
            20 -> openWizardType(player)
            22 -> openInbox(player, 1)
            24 -> openBoard(player, BoardMode.OPEN, 1)
            30 -> openBoard(player, BoardMode.MINE, 1)
            32 -> {
                player.closeInventory()
                for (line in plugin.lang().message("help").split(Regex("\\R"))) {
                    player.sendMessage(line)
                }
            }
            49 -> if (player.hasPermission("contract.admin.view")) openAdmin(player, AdminFilter.DISPUTED, 1)
        }
    }

    private fun handleInboxClick(player: Player, session: Session, slot: Int) {
        if (slot == 45 && session.page > 1) {
            openInbox(player, session.page - 1)
            return
        }
        if (slot == 53) {
            openInbox(player, session.page + 1)
            return
        }
        if (slot == 49) {
            openHub(player)
            return
        }
        val contractId = session.slotContracts[slot] ?: return
        plugin.storage().findByPrefix(contractId).ifPresent { contract ->
            openDetails(player, contract, ViewType.INBOX, BoardMode.MINE, session.page, TypeFilter.ALL, false)
        }
    }

    private fun handleBoardClick(player: Player, session: Session, slot: Int) {
        when (slot) {
            1 -> {
                openBoard(player, session.mode, 1, TypeFilter.ALL)
                return
            }
            2 -> {
                openBoard(player, session.mode, 1, TypeFilter.SERVICE)
                return
            }
            3 -> {
                openBoard(player, session.mode, 1, TypeFilter.WAGER)
                return
            }
            4 -> {
                openBoard(player, session.mode, 1, TypeFilter.PARTNERSHIP)
                return
            }
            45 -> {
                if (session.page > 1) openBoard(player, session.mode, session.page - 1, session.filter)
                return
            }
            53 -> {
                openBoard(player, session.mode, session.page + 1, session.filter)
                return
            }
            47 -> {
                openBoard(player, BoardMode.MINE, 1, session.filter)
                return
            }
            49 -> {
                openHub(player)
                return
            }
            51 -> {
                openBoard(player, BoardMode.OPEN, 1, session.filter)
                return
            }
        }
        val contractId = session.slotContracts[slot] ?: return
        plugin.storage().findByPrefix(contractId).ifPresent { contract ->
            openDetails(player, contract, ViewType.BOARD, session.mode, session.page, session.filter, false)
        }
    }

    private fun handleAdminClick(player: Player, session: Session, slot: Int) {
        if (!player.hasPermission("contract.admin.view")) {
            player.closeInventory()
            return
        }
        when (slot) {
            1 -> {
                openAdmin(player, AdminFilter.DISPUTED, 1)
                return
            }
            2 -> {
                openAdmin(player, AdminFilter.ACTIVE, 1)
                return
            }
            3 -> {
                openAdmin(player, AdminFilter.ALL, 1)
                return
            }
            45 -> {
                if (session.page > 1) openAdmin(player, session.adminFilter, session.page - 1)
                return
            }
            53 -> {
                openAdmin(player, session.adminFilter, session.page + 1)
                return
            }
            49 -> {
                openHub(player)
                return
            }
        }
        val contractId = session.slotContracts[slot] ?: return
        plugin.storage().findByPrefix(contractId).ifPresent { contract ->
            openDetails(player, contract, ViewType.ADMIN, BoardMode.OPEN, session.page, TypeFilter.ALL, true)
        }
    }

    private fun handleWizardTypeClick(player: Player, slot: Int) {
        val type = when (slot) {
            11 -> ContractType.SERVICE
            13 -> ContractType.WAGER
            15 -> ContractType.PARTNERSHIP
            else -> null
        }
        if (slot == 22) {
            openHub(player)
            return
        }
        if (type == null) {
            return
        }
        drafts[player.uniqueId] = CreateDraft(type)
        openWizardForm(player)
    }

    private fun handleWizardFormClick(player: Player, slot: Int) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        when (slot) {
            13 -> openTextAnvil(player, "输入合同标题", draft.title(), Consumer { text -> draft.title(if (text.isBlank()) null else text) })
            15 -> beginDescriptionPrompt(player)
            20 -> if (draft.needsCounterparty()) openTextAnvil(player, "输入对方玩家名", draft.counterparty(), Consumer { text -> draft.counterparty(if (text.isBlank()) null else text) })
            22 -> openNumberAnvil(player, if (draft.type() == ContractType.SERVICE) "输入奖金金额" else "输入我的押注金额", draft.amount(), Consumer { value -> draft.amount(value) })
            24 -> openTextAnvil(player, if (draft.mediatorRequired()) "输入仲裁者玩家名" else "输入中间人玩家名(可留空)", draft.mediator(), Consumer { text -> draft.mediator(if (text.isBlank()) null else text) })
            29 -> if (draft.needsPartnerStake()) openNumberAnvil(player, "输入对方押注金额", draft.partnerStake(), Consumer { value -> draft.partnerStake(value) })
            31 -> openNumberAnvil(player, "输入期限小时数", draft.hours()?.toDouble(), Consumer { value -> draft.hours(Math.round(value).toInt()) })
            48 -> {
                openWizardType(player)
                return
            }
            50 -> {
                val validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours())
                if (validation != null) {
                    player.sendMessage(Text.color("&#E63946$validation"))
                    return
                }
                openConfirm(player, PendingAction.create(draft, draftPreview(draft)), Session.wizardForm())
            }
        }
    }

    private fun handleDetailsClick(player: Player, session: Session, slot: Int) {
        val optional = if (session.contractId == null) Optional.empty() else plugin.storage().findByPrefix(session.contractId)
        if (slot == 22) {
            backFromDetails(player, session)
            return
        }
        if (optional.isEmpty) {
            player.closeInventory()
            player.sendMessage(plugin.lang().message("not-found"))
            return
        }
        val contract = optional.get()
        if (session.adminMode) {
            handleAdminDetailClick(player, session, contract, slot)
            return
        }
        val id = player.uniqueId
        val mediator = isArbiter(contract, id)
        if (slot == 10 && contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            runDirect(player, session, contract, plugin.contracts().acceptMediation(player, contract))
            return
        }
        if (slot == 10 && contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && id == contract.contractorUuid()) {
            runDirect(player, session, contract, plugin.contracts().submit(player, contract))
            return
        }
        if (slot == 16 && canDispute(contract)) {
            beginDisputePrompt(player, contract, session)
            return
        }
        val action = detailAction(player, contract, slot, mediator)
        if (action != null) {
            openConfirm(player, action, session)
        }
    }

    private fun handleAdminDetailClick(player: Player, session: Session, contract: Contract, slot: Int) {
        if (!player.hasPermission("contract.admin.settle")) {
            player.sendMessage(plugin.lang().message("no-permission"))
            return
        }
        if (contract.status().isFinal()) {
            return
        }
        val action = when (slot) {
            10 -> PendingAction.simple(PendingAction.Kind.ADMIN_PAY, contract, null, "管理员强制付款 #${contract.shortId()}", listOf("按合同的成功结算规则向收款方付款。", "系统佣金照常回收。"))
            12 -> PendingAction.simple(PendingAction.Kind.ADMIN_REFUND, contract, null, "管理员强制退款 #${contract.shortId()}", listOf("按失败规则将托管退回发起方。"))
            14 -> PendingAction.simple(PendingAction.Kind.ADMIN_CLOSE, contract, null, "管理员关闭合同 #${contract.shortId()}", listOf("仅关闭合同,不移动任何资金。", "资金需另行用 pay/refund 或人工核对处理。"))
            else -> null
        }
        if (action != null) {
            openConfirm(player, action, session)
        }
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
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, "接受邀请 #${contract.shortId()}", acceptConsequences(contract))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, "接下委托 #${contract.shortId()}", listOf("接单本身不扣款。", "完成并经雇主确认后可获得 ${plugin.economy().format(contract.payoutAmount())}。"))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, "确认付款 #${contract.shortId()}", listOf("向接单者支付 ${plugin.economy().format(contract.payoutAmount())}。", "系统回收佣金 ${plugin.economy().format(contract.commissionAmount())}。"))
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

    private fun handleConfirmClick(player: Player, session: Session, slot: Int) {
        if (slot == 11) {
            backFromConfirm(player, session)
            return
        }
        if (slot == 15) {
            openSignAnvil(player, session)
        }
    }

    private fun openSignAnvil(player: Player, confirmSession: Session) {
        val action = confirmSession.pending ?: return
        anvilSuppressReopen.add(player.uniqueId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            AnvilGUI.Builder()
                .plugin(plugin)
                .title(Text.color("&#F4D03F签署: 输入你的名字"))
                .text("...")
                .itemLeft(ItemStack(Material.WRITABLE_BOOK))
                .onClick { slot, snapshot ->
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return@onClick emptyList()
                    }
                    val input = snapshot.text
                    anvilSuppressReopen.remove(player.uniqueId)
                    if (!Signature.matches(player.name, input)) {
                        Bukkit.getScheduler().runTask(plugin, Runnable {
                            player.sendMessage(Text.color("&#E63946签名不匹配,操作已取消。请输入你的玩家名或 \"同意\"。"))
                            openConfirm(player, action, confirmSession.confirmOrigin ?: Session.wizardForm())
                        })
                        return@onClick listOf(AnvilGUI.ResponseAction.close())
                    }
                    Bukkit.getScheduler().runTask(plugin, Runnable { executeAction(player, action) })
                    listOf(AnvilGUI.ResponseAction.close())
                }
                .onClose {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (anvilSuppressReopen.remove(player.uniqueId)) {
                            player.sendMessage(Text.color("&#FFE066已取消签署。"))
                            openConfirm(player, action, confirmSession.confirmOrigin ?: Session.wizardForm())
                        }
                    })
                }
                .open(player)
        })
    }

    private fun executeAction(player: Player, action: PendingAction) {
        val result: ServiceResult
        var contract: Contract? = null
        if (action.kind() == PendingAction.Kind.CREATE) {
            result = executeCreate(player)
        } else {
            contract = plugin.storage().findByPrefix(action.contractId()).orElse(null)
            if (contract == null) {
                player.sendMessage(plugin.lang().message("not-found"))
                openHub(player)
                return
            }
            result = when (action.kind()) {
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
        }
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C签署成功,操作已完成。"))
            if (action.kind() == PendingAction.Kind.CREATE) {
                drafts.remove(player.uniqueId)
            }
            val done = result.contract() ?: contract
            if (done != null) {
                openDetails(player, done, ViewType.HUB, BoardMode.MINE, 1, TypeFilter.ALL, false)
            } else {
                openHub(player)
            }
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
            openHub(player)
        }
    }

    private fun executeCreate(player: Player): ServiceResult {
        val draft = drafts[player.uniqueId] ?: return ServiceResult.fail("创建草稿已失效,请重新开始")
        val validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours())
        if (validation != null) {
            return ServiceResult.fail(validation)
        }
        val description = draft.description() ?: ""
        val amount = draft.amount() ?: return ServiceResult.fail("请先填写金额")
        val hours = draft.hours() ?: return ServiceResult.fail("请先填写期限")
        val title = draft.title() ?: return ServiceResult.fail("请先填写标题")
        return when (draft.type()) {
            ContractType.SERVICE -> plugin.contracts().create(player, amount, hours, title, description, emptyToNull(draft.mediator()))
            ContractType.WAGER -> {
                val counterparty = draft.counterparty() ?: return ServiceResult.fail("请先填写对方玩家")
                val mediator = draft.mediator() ?: return ServiceResult.fail("请先填写仲裁者")
                plugin.contracts().createWager(player, counterparty, BigDecimal.valueOf(amount), hours, mediator, title, description)
            }
            ContractType.PARTNERSHIP -> {
                val counterparty = draft.counterparty() ?: return ServiceResult.fail("请先填写对方玩家")
                val partnerStake = draft.partnerStake() ?: return ServiceResult.fail("请先填写对方押注")
                plugin.contracts().createPartnership(player, counterparty, BigDecimal.valueOf(amount), BigDecimal.valueOf(partnerStake), hours, title, description, emptyToNull(draft.mediator()))
            }
            else -> ServiceResult.fail("不支持的合同类型")
        }
    }

    private fun beginDescriptionPrompt(player: Player) {
        if (!drafts.containsKey(player.uniqueId)) {
            openWizardType(player)
            return
        }
        descriptionPrompts[player.uniqueId] = DescriptionPrompt(System.currentTimeMillis() + DESCRIPTION_PROMPT_TIMEOUT_MS)
        player.closeInventory()
        player.sendMessage(plugin.lang().message("description-input-start"))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val prompt = descriptionPrompts[player.uniqueId]
            if (prompt != null && System.currentTimeMillis() >= prompt.expiresAt) {
                descriptionPrompts.remove(player.uniqueId)
                val current = Bukkit.getPlayer(player.uniqueId)
                if (current != null) {
                    current.sendMessage(plugin.lang().message("description-input-timeout"))
                    openWizardForm(current)
                }
            }
        }, DESCRIPTION_PROMPT_TIMEOUT_MS / 50L + 1L)
    }

    private fun handleDescriptionInput(player: Player, prompt: DescriptionPrompt, message: String) {
        val playerId = player.uniqueId
        if (System.currentTimeMillis() >= prompt.expiresAt) {
            player.sendMessage(plugin.lang().message("description-input-timeout"))
            openWizardForm(player)
            return
        }
        if (message.equals("cancel", ignoreCase = true)) {
            player.sendMessage(plugin.lang().message("description-input-cancelled"))
            openWizardForm(player)
            return
        }
        val draft = drafts[playerId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        if (message.equals("clear", ignoreCase = true)) {
            draft.description(null)
            player.sendMessage(plugin.lang().message("description-input-cleared"))
            openWizardForm(player)
            return
        }
        val maxLength = plugin.config.getInt("limits.max-description-length", 500)
        val clean = Text.stripControl(message)
        if (clean.length > maxLength) {
            player.sendMessage(plugin.lang().message("description-input-too-long", mapOf("max" to maxLength.toString())))
            openWizardForm(player)
            return
        }
        draft.description(if (clean.isBlank()) null else clean)
        player.sendMessage(plugin.lang().message("description-input-saved"))
        openWizardForm(player)
    }

    private fun openTextAnvil(player: Player, title: String, initial: String?, onText: Consumer<String>) {
        anvilSuppressReopen.add(player.uniqueId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            AnvilGUI.Builder()
                .plugin(plugin)
                .title(Text.color("&#F4D03F$title"))
                .text(if (initial.isNullOrBlank()) "..." else initial)
                .itemLeft(ItemStack(Material.PAPER))
                .onClick { slot, snapshot ->
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return@onClick emptyList()
                    }
                    anvilSuppressReopen.remove(player.uniqueId)
                    var text = snapshot.text?.trim() ?: ""
                    if (text == "...") {
                        text = ""
                    }
                    val finalText = text
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        onText.accept(finalText)
                        openWizardForm(player)
                    })
                    listOf(AnvilGUI.ResponseAction.close())
                }
                .onClose {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (anvilSuppressReopen.remove(player.uniqueId)) {
                            openWizardForm(player)
                        }
                    })
                }
                .open(player)
        })
    }

    private fun openNumberAnvil(player: Player, title: String, initial: Double?, onValue: Consumer<Double>) {
        anvilSuppressReopen.add(player.uniqueId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            AnvilGUI.Builder()
                .plugin(plugin)
                .title(Text.color("&#F4D03F$title"))
                .text(if (initial == null) "0" else trimNumber(initial))
                .itemLeft(ItemStack(Material.GOLD_INGOT))
                .onClick { slot, snapshot ->
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return@onClick emptyList()
                    }
                    anvilSuppressReopen.remove(player.uniqueId)
                    val parsed = parsePositive(snapshot.text?.trim() ?: "")
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (parsed == null) {
                            player.sendMessage(Text.color("&#E63946数字格式不正确,未保存。"))
                        } else {
                            onValue.accept(parsed)
                        }
                        openWizardForm(player)
                    })
                    listOf(AnvilGUI.ResponseAction.close())
                }
                .onClose {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        if (anvilSuppressReopen.remove(player.uniqueId)) {
                            openWizardForm(player)
                        }
                    })
                }
                .open(player)
        })
    }

    private fun canDispute(contract: Contract): Boolean {
        val status = contract.status()
        return status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED
    }

    private fun beginDisputePrompt(player: Player, contract: Contract, session: Session) {
        disputePrompts[player.uniqueId] = DisputePrompt(contract.id(), session.origin, session.mode, session.page, session.filter, session.adminMode, System.currentTimeMillis() + DISPUTE_PROMPT_TIMEOUT_MS)
        player.closeInventory()
        player.sendMessage(Text.color("&#FFE066请在 60 秒内输入争议原因,或输入 &#E63946cancel &#FFE066取消。"))
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val prompt = disputePrompts[player.uniqueId]
            if (prompt != null && prompt.contractId == contract.id() && System.currentTimeMillis() >= prompt.expiresAt) {
                disputePrompts.remove(player.uniqueId)
                val current = Bukkit.getPlayer(player.uniqueId)
                current?.sendMessage(Text.color("&#E63946争议输入超时,已取消。"))
            }
        }, DISPUTE_PROMPT_TIMEOUT_MS / 50L + 1L)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val playerId = event.player.uniqueId
        val descriptionPrompt = descriptionPrompts[playerId]
        if (descriptionPrompt != null) {
            event.isCancelled = true
            val message = event.message
            descriptionPrompts.remove(playerId)
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val player = Bukkit.getPlayer(playerId)
                if (player != null) {
                    handleDescriptionInput(player, descriptionPrompt, message)
                }
            })
            return
        }
        val prompt = disputePrompts[playerId] ?: return
        event.isCancelled = true
        val message = event.message
        disputePrompts.remove(playerId)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(playerId) ?: return@Runnable
            if (System.currentTimeMillis() >= prompt.expiresAt) {
                player.sendMessage(Text.color("&#E63946争议输入超时,已取消。"))
                return@Runnable
            }
            if (message.equals("cancel", ignoreCase = true)) {
                player.sendMessage(Text.color("&#FFE066已取消争议输入。"))
                return@Runnable
            }
            plugin.storage().findByPrefix(prompt.contractId).ifPresentOrElse({ contract ->
                val result = plugin.contracts().dispute(player, contract, message)
                if (result.success()) {
                    player.sendMessage(plugin.lang().message("dispute-success"))
                    openDetails(player, contract, prompt.origin, prompt.mode, prompt.page, prompt.filter, prompt.adminMode)
                } else {
                    player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
                }
            }, { player.sendMessage(plugin.lang().message("not-found")) })
        })
    }

    private fun backFromDetails(player: Player, session: Session) {
        when (session.origin) {
            ViewType.HUB -> openHub(player)
            ViewType.INBOX -> openInbox(player, session.page)
            ViewType.ADMIN -> openAdmin(player, AdminFilter.DISPUTED, session.page)
            else -> openBoard(player, session.mode, session.page, session.filter)
        }
    }

    private fun backFromConfirm(player: Player, session: Session) {
        val origin = session.confirmOrigin
        if (origin == null || origin.type == ViewType.WIZARD_FORM) {
            openWizardForm(player)
            return
        }
        val contract = if (origin.contractId == null) Optional.empty() else plugin.storage().findByPrefix(origin.contractId)
        if (contract.isEmpty) {
            openHub(player)
            return
        }
        openDetails(player, contract.get(), origin.origin, origin.mode, origin.page, origin.filter, origin.adminMode)
    }

    private fun runDirect(player: Player, session: Session, contract: Contract, result: ServiceResult) {
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C操作成功。"))
            val done = result.contract() ?: contract
            openDetails(player, done, session.origin, session.mode, session.page, session.filter, session.adminMode)
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
        }
    }

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
        if (status == ContractStatus.DISPUTED && contract.relatedTo(id)) return "争议处理中"
        return null
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

    private fun contractsFor(player: Player, mode: BoardMode, filter: TypeFilter): List<Contract> {
        val stream =
            if (mode == BoardMode.MINE) plugin.contracts().allContracts().stream().filter { contract -> contract.relatedTo(player.uniqueId) }
            else plugin.contracts().openContracts().stream()
        return stream.filter { contract -> filter.matches(contract) }.toList()
    }

    private fun acceptConsequences(contract: Contract): List<String> {
        val partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null)
        val stake = partyB?.moneyStake() ?: BigDecimal.ZERO
        return listOf("签署后立即从你的余额扣除押注 ${plugin.economy().format(stake)} 托管到服务器。", "资金不会交给对方或中间人,由服务器托管。")
    }

    private fun draftPreview(draft: CreateDraft): List<String> {
        val lines = ArrayList<String>()
        lines.add("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(draft.type())}")
        lines.add("&#CFD8DC标题: &#FFFFFF${valueOr(draft.title())}")
        lines.add("&#CFD8DC描述: &#FFFFFF${ContractTerms.preview(draft.description())}")
        if (draft.needsCounterparty()) lines.add("&#CFD8DC对方: &#FFFFFF${valueOr(draft.counterparty())}")
        lines.add("&#CFD8DC${if (draft.mediatorRequired()) "仲裁者" else "中间人"}: &#FFFFFF${valueOr(draft.mediator())}")
        lines.add("&#CFD8DC期限: &#FFFFFF${draft.hours()?.let { "$it 小时" } ?: "未填"}")
        if (draft.type() == ContractType.SERVICE) {
            val fee = plugin.config.getDouble("economy.creation-fee", 20.0)
            lines.add("&#CFD8DC奖金托管: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC创建费: &#FFE066${trimNumber(fee)} &#CFD8DC(普通取消不退)")
            val amount = draft.amount()
            if (amount != null) lines.add("&#CFD8DC签署后共扣除: &#E63946${trimNumber(amount + fee)}")
        } else if (draft.type() == ContractType.WAGER) {
            lines.add("&#CFD8DC我的押注: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC对方需匹配等额押注。")
            lines.add("&#CFD8DC签署后立即扣除: &#E63946${valueOr(num(draft.amount()))}")
        } else {
            lines.add("&#CFD8DC我的押注: &#69DB7C${valueOr(num(draft.amount()))}")
            lines.add("&#CFD8DC对方押注: &#69DB7C${valueOr(num(draft.partnerStake()))}")
            lines.add("&#CFD8DC签署后立即扣除我的押注: &#E63946${valueOr(num(draft.amount()))}")
        }
        lines.add("&#CFD8DC资金由服务器托管,不交给对方或中间人。")
        return lines
    }

    private fun contractItem(contract: Contract, actionLabel: String?): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DCID: &#FFE066#${contract.shortId()}"))
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(contract.type())}"))
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF${plugin.lang().status(contract.status())}"))
        lore.add(Text.color("&#CFD8DC发起: &#FFFFFF${contract.ownerName()}"))
        lore.add(Text.color("&#CFD8DC对方: &#FFFFFF${contract.contractorName() ?: "无"}"))
        lore.add(Text.color("&#CFD8DC金额: &#69DB7C${plugin.economy().format(contract.reward())}"))
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF${DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))}"))
        lore.add("")
        if (actionLabel != null) lore.add(Text.color("&#E63946▶ $actionLabel"))
        lore.add(Text.color("&#FFE066点击查看详情"))
        val item = ItemStack(materialFor(contract.type(), contract.status()))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F${contract.title()}"))
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }

    private fun detailItem(contract: Contract): ItemStack {
        val lore = ArrayList<String>()
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF${plugin.lang().type(contract.type())}"))
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF${plugin.lang().status(contract.status())}"))
        for (participant in contract.participants()) {
            lore.add(Text.color("&#CFD8DC${plugin.lang().role(participant.role())}: &#FFFFFF${participant.displayName() ?: "无"} &#69DB7C${plugin.economy().format(participant.moneyStake())}"))
        }
        val arbiter = contract.arbiter()
        if (arbiter != null) {
            lore.add(Text.color("&#CFD8DC仲裁者: &#FFFFFF${arbiter.displayName()} &#CFD8DC(${if (contract.arbiterAccepted()) "已接受" else "待接受"})"))
        }
        lore.add(Text.color("&#CFD8DC佣金率: &#FFE066${contract.commissionPercent().toPlainString()}%"))
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF${DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))}"))
        lore.add("")
        lore.add(Text.color("&#F1F5F9${contract.description()}"))
        val disputeReason = contract.disputeReason()
        if (!disputeReason.isNullOrBlank()) {
            lore.add("")
            lore.add(Text.color("&#E63946争议: &#F1F5F9$disputeReason"))
        }
        val item = ItemStack(materialFor(contract.type(), contract.status()))
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F${contract.title()}"))
            meta.lore = lore
            item.itemMeta = meta
        }
        return item
    }

    private fun fieldButton(material: Material, label: String, value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val name = (if (filled) "&#69DB7C" else "&#FFE066") + label
        return button(material, name, "&#CFD8DC当前: &#FFFFFF${if (filled) value else "未填写"}", "&#FFE066点击用铁砧输入")
    }

    private fun descriptionButton(value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val name = (if (filled) "&#69DB7C" else "&#FFE066") + "描述"
        return button(Material.BOOK, name, "&#CFD8DC当前: &#FFFFFF${ContractTerms.preview(value)}", "&#FFE066点击后在聊天输入", "&#CFD8DC输入 cancel 取消, clear 清空")
    }

    private fun button(material: Material, name: String, vararg lore: String): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(Text.color(name))
            val coloredLore = ArrayList<String>()
            for (line in lore) coloredLore.add(Text.color(line))
            meta.lore = coloredLore
            item.itemMeta = meta
        }
        return item
    }

    private fun fillBorder(inventory: Inventory) {
        val pane = button(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (index in 0 until inventory.size) {
            val row = index / 9
            val col = index % 9
            if (row == 0 || row == inventory.size / 9 - 1 || col == 0 || col == 8) {
                inventory.setItem(index, pane)
            }
        }
    }

    private fun filterButton(filter: TypeFilter, selected: TypeFilter, material: Material, label: String): ItemStack =
        button(material, (if (filter == selected) "&#69DB7C" else "&#CFD8DC") + label, "&#CFD8DC点击筛选此类型")

    private fun adminFilterButton(filter: AdminFilter, selected: AdminFilter, material: Material, label: String): ItemStack =
        button(material, (if (filter == selected) "&#69DB7C" else "&#CFD8DC") + label, "&#CFD8DC点击切换分栏")

    private fun materialFor(type: ContractType, status: ContractStatus): Material =
        when (status) {
            ContractStatus.COMPLETED -> Material.EMERALD
            ContractStatus.CANCELLED -> Material.BARRIER
            ContractStatus.EXPIRED -> Material.CLOCK
            ContractStatus.DISPUTED -> Material.REDSTONE
            ContractStatus.PENDING_ACCEPT -> Material.YELLOW_BANNER
            else -> when (type) {
                ContractType.SERVICE -> Material.PAPER
                ContractType.WAGER -> Material.TARGET
                ContractType.PARTNERSHIP -> Material.AMETHYST_CLUSTER
                ContractType.ALLIANCE -> Material.SHIELD
                ContractType.BOUNTY -> Material.CROSSBOW
                ContractType.SALE -> Material.CHEST
                ContractType.LOAN -> Material.GOLD_INGOT
            }
        }

    private fun canAcceptInvitation(player: Player, contract: Contract): Boolean = player.uniqueId == contract.contractorUuid()

    private fun canCancel(player: Player, contract: Contract): Boolean = contract.participantByUuid(player.uniqueId).isPresent

    private fun canMediate(contract: Contract): Boolean =
        contract.arbiterAccepted() && !contract.status().isFinal() && contract.status() != ContractStatus.OPEN && contract.status() != ContractStatus.PENDING_ACCEPT

    private fun isParty(contract: Contract, uuid: UUID): Boolean =
        contract.participantByUuid(uuid).map { participant -> participant.role() == ParticipantRole.PARTY_A || participant.role() == ParticipantRole.PARTY_B }.orElse(false)

    private fun isArbiter(contract: Contract, uuid: UUID): Boolean = contract.arbiter()?.uuid() == uuid

    private fun isManagedTitle(title: String): Boolean =
        title == HUB_TITLE || title == INBOX_TITLE || title == BOARD_TITLE || title == MY_TITLE || title == WIZARD_TYPE_TITLE || title == WIZARD_FORM_TITLE || title == CONFIRM_TITLE || title == ADMIN_TITLE || title.startsWith(DETAIL_TITLE_PREFIX)

    private fun pageCount(size: Int): Int = max(1, ceil(size.toDouble() / BOARD_SLOTS.size).toInt())

    private fun clampPage(page: Int, pages: Int): Int = min(max(1, page), pages)

    private fun minAmount(): Double = plugin.config.getDouble("economy.min-reward", 100.0)

    private fun maxAmount(): Double = plugin.config.getDouble("economy.max-reward", 100000.0)

    private fun minHours(): Int = plugin.config.getInt("limits.min-deadline-hours", 1)

    private fun maxHours(): Int = plugin.config.getInt("limits.max-deadline-hours", 168)

    enum class BoardMode { OPEN, MINE }

    enum class TypeFilter(private val type: ContractType?) {
        ALL(null),
        SERVICE(ContractType.SERVICE),
        WAGER(ContractType.WAGER),
        PARTNERSHIP(ContractType.PARTNERSHIP);

        fun matches(contract: Contract): Boolean = type == null || contract.type() == type
    }

    enum class AdminFilter { DISPUTED, ACTIVE, ALL }

    private enum class ViewType { HUB, INBOX, BOARD, ADMIN, DETAILS, WIZARD_TYPE, WIZARD_FORM, CONFIRM }

    private class DescriptionPrompt(val expiresAt: Long)

    private class DisputePrompt(
        val contractId: String,
        val origin: ViewType,
        val mode: BoardMode,
        val page: Int,
        val filter: TypeFilter,
        val adminMode: Boolean,
        val expiresAt: Long,
    )

    private class PendingAction(
        private val kind: Kind,
        private val contractId: String?,
        private val arg: String?,
        private val title: String,
        private val consequences: List<String>,
    ) {
        enum class Kind { CREATE, ACCEPT, APPROVE, RESOLVE, MEDIATE, CANCEL, ADMIN_PAY, ADMIN_REFUND, ADMIN_CLOSE }

        fun kind(): Kind = kind
        fun contractId(): String = contractId ?: throw NullPointerException("contractId")
        fun arg(): String = arg ?: throw NullPointerException("arg")
        fun title(): String = title
        fun consequences(): List<String> = consequences

        companion object {
            fun simple(kind: Kind, contract: Contract, arg: String?, title: String, consequences: List<String>): PendingAction =
                PendingAction(kind, contract.id(), arg, title, consequences)

            fun create(draft: CreateDraft, preview: List<String>): PendingAction {
                val lines = ArrayList<String>()
                lines.add("即将创建一份${draft.type().name}合同。")
                lines.addAll(preview)
                return PendingAction(Kind.CREATE, null, null, "创建合同", lines)
            }
        }
    }

    private class Session(
        val type: ViewType,
        val mode: BoardMode,
        val page: Int,
        val filter: TypeFilter,
        val adminFilter: AdminFilter,
        val contractId: String?,
        val origin: ViewType,
        val adminMode: Boolean,
        val pending: PendingAction?,
        val confirmOrigin: Session?,
    ) {
        val slotContracts: MutableMap<Int, String> = HashMap()

        companion object {
            fun hub(): Session = Session(ViewType.HUB, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED, null, ViewType.HUB, false, null, null)
            fun list(type: ViewType, page: Int): Session = Session(type, BoardMode.MINE, page, TypeFilter.ALL, AdminFilter.DISPUTED, null, ViewType.HUB, false, null, null)
            fun board(mode: BoardMode, page: Int, filter: TypeFilter): Session = Session(ViewType.BOARD, mode, page, filter, AdminFilter.DISPUTED, null, ViewType.HUB, false, null, null)
            fun admin(filter: AdminFilter, page: Int): Session = Session(ViewType.ADMIN, BoardMode.OPEN, page, TypeFilter.ALL, filter, null, ViewType.HUB, false, null, null)
            fun details(origin: ViewType, mode: BoardMode, page: Int, filter: TypeFilter, contractId: String, adminMode: Boolean): Session =
                Session(ViewType.DETAILS, mode, page, filter, AdminFilter.DISPUTED, contractId, origin, adminMode, null, null)
            fun wizardType(): Session = Session(ViewType.WIZARD_TYPE, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED, null, ViewType.HUB, false, null, null)
            fun wizardForm(): Session = Session(ViewType.WIZARD_FORM, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED, null, ViewType.WIZARD_TYPE, false, null, null)
            fun confirm(action: PendingAction, origin: Session): Session =
                Session(ViewType.CONFIRM, origin.mode, origin.page, origin.filter, origin.adminFilter, origin.contractId, origin.origin, origin.adminMode, action, origin)
        }
    }

    companion object {
        private val HUB_TITLE = Text.color("&#F4D03F合同工作台")
        private val INBOX_TITLE = Text.color("&#F4D03F行动收件箱")
        private val BOARD_TITLE = Text.color("&#F4D03F合同大厅")
        private val MY_TITLE = Text.color("&#F4D03F我的合同")
        private val DETAIL_TITLE_PREFIX = Text.color("&#F4D03F合同详情 ")
        private val WIZARD_TYPE_TITLE = Text.color("&#F4D03F创建合同 · 选择类型")
        private val WIZARD_FORM_TITLE = Text.color("&#F4D03F创建合同 · 填写")
        private val CONFIRM_TITLE = Text.color("&#F4D03F签署确认")
        private val ADMIN_TITLE = Text.color("&#E63946管理员工作台")
        private val BOARD_SLOTS = intArrayOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
        private const val DISPUTE_PROMPT_TIMEOUT_MS = 60_000L
        private const val DESCRIPTION_PROMPT_TIMEOUT_MS = 120_000L

        private fun parsePositive(text: String): Double? =
            try {
                val value = text.toDouble()
                if (value > 0 && value.isFinite()) value else null
            } catch (ex: NumberFormatException) {
                null
            }

        private fun emptyToNull(value: String?): String? = if (value.isNullOrBlank()) null else value
        private fun valueOr(value: String?): String = if (value.isNullOrBlank()) "未填" else value
        private fun num(value: Double?): String? = value?.let { trimNumber(it) }
        private fun trimNumber(value: Double): String =
            if (value == Math.rint(value)) value.toLong().toString() else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }
}
