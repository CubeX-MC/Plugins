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
import org.cubexmc.contract.model.BatchSummary
import org.cubexmc.contract.model.ContractTemplate
import org.cubexmc.contract.model.TemplateVisibility
import org.cubexmc.contract.model.BatchRepeatPolicy
import org.cubexmc.contract.model.ContractObjective
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.ObjectiveType
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.service.BatchQueryService
import org.cubexmc.core.Terminable
import java.math.BigDecimal
import java.util.UUID
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Drives the contract GUI. The hall (`openHall`) is the landing screen; contracts are grouped by
 * their workflow state so players can answer "what needs attention now?" before opening details.
 * Navigation runs through the [framework.Menu]/[framework.MenuRegistry] button framework rather than
 * a central slot switch; text/number entry runs through [ChatInputService].
 */
class ContractGui(private val plugin: ContractPlugin) : Listener, Terminable {
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

    /**
     * Drops every open menu, in-flight draft and pending chat prompt. Called on disable via
     * [Terminable], and as a reload stage so nobody keeps clicking a menu built from stale config.
     */
    override fun close() {
        registry.closeAll()
        drafts.clear()
        input.cancelAll()
    }

    /** Named alias for [close]; reads better at the reload call sites. */
    fun closeSessions() = close()

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drafts.remove(event.player.uniqueId)
    }

    // ---- Hall (public board / my contracts / inbox) --------------------------------------------

    fun openHall(player: Player, view: HallView, page: Int) {
        val entries = hallEntries(player, view)
        val pages = pageCount(entries.size)
        val current = clampPage(page, pages)
        val menu = Menu(hallTitle(view), 6)
        fillBorder(menu.inventory)

        menu.button(1, viewButton(HallView.OPEN, view, Material.EMERALD, ui("hall-view-open"))) { openHall(player, HallView.OPEN, 1) }
        menu.button(2, viewButton(HallView.ACTIVE, view, Material.CLOCK, ui("hall-view-active"))) { openHall(player, HallView.ACTIVE, 1) }
        menu.button(3, viewButton(HallView.SUBMITTED, view, Material.DIAMOND, ui("hall-view-submitted"))) { openHall(player, HallView.SUBMITTED, 1) }
        menu.button(4, viewButton(HallView.DISPUTED, view, Material.REDSTONE, ui("hall-view-disputed"))) { openHall(player, HallView.DISPUTED, 1) }
        menu.button(5, viewButton(HallView.DONE, view, Material.NETHER_STAR, ui("hall-view-done"))) { openHall(player, HallView.DONE, 1) }
        menu.button(6, viewButton(HallView.CLOSED, view, GuiIcons.INACTIVE, ui("hall-view-closed"))) { openHall(player, HallView.CLOSED, 1) }

        val back = { openHall(player, view, current) }
        val start = (current - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, entries.size)
        for (index in start until end) {
            val slot = BOARD_SLOTS[index - start]
            when (val entry = entries[index]) {
                is HallEntry.Single -> {
                    val contract = entry.contract
                    val contractId = contract.id()
                    menu.button(slot, render.contractItem(contract, inboxLabel(player, contract), progressLabel(player, contract))) {
                        plugin.storage().findByPrefix(contractId).ifPresent { found -> openDetails(player, found, false, back) }
                    }
                }
                is HallEntry.Batch -> {
                    val summary = entry.summary
                    menu.button(slot, render.batchItem(summary, plugin.lang().ui(if (summary.available > 0) "batch-pool-action" else "batch-progress-action"))) {
                        openBatchDetails(player, summary.batchId, 1, back)
                    }
                }
            }
        }
        if (entries.isEmpty()) {
            menu.decoration(22, button(Material.LIME_DYE, emptyHint(view), ui("hall-empty-hint")))
        }

        menu.button(45, button(Material.ARROW, ui("nav-prev"), pageLore(current, pages))) { if (current > 1) openHall(player, view, current - 1) }
        if (player.hasPermission(TEMPLATE_USE_PERMISSION)) {
            menu.button(48, button(Material.CHEST, ui("template-pool"), ui("template-pool-detail"))) { openTemplateLibrary(player, 1) }
        }
        menu.button(49, button(Material.WRITABLE_BOOK, ui("hall-create"), ui("hall-create-detail"))) { openWizardType(player) }
        val inboxCount = inboxContracts(player).size
        menu.button(
            50,
            button(
                Material.BELL,
                ui("hall-refresh"),
                ui("hall-refresh-count", mapOf("count" to entries.size.toString())),
                ui("hall-refresh-inbox", mapOf("count" to inboxCount.toString())),
                ui("hall-refresh-hint"),
            ),
        ) { openHall(player, view, current) }
        if (player.hasPermission("contract.admin.view")) {
            menu.button(51, button(Material.CRAFTING_TABLE, ui("hall-admin"), ui("hall-admin-detail"))) { openAdmin(player, AdminFilter.DISPUTED, 1) }
        }
        menu.button(52, button(Material.BOOK, ui("hall-help"), ui("hall-help-detail"))) { sendHelp(player) }
        menu.button(53, button(Material.ARROW, ui("nav-next"), pageLore(current, pages))) { openHall(player, view, current + 1) }

        registry.open(player, menu)
    }

    private fun openBatchDetails(player: Player, batchId: String, page: Int, back: () -> Unit) {
        val summary = BatchQueryService.summaries(plugin.contracts().allContracts())[batchId]
        if (summary == null) {
            player.sendMessage(ui("batch-missing"))
            back()
            return
        }
        val pages = pageCount(summary.children.size)
        val current = clampPage(page, pages)
        val menu = Menu(ui("batch-title"), 6)
        fillBorder(menu.inventory)
        menu.decoration(4, render.batchItem(summary, "#${batchId.take(8)}"))
        if (summary.available > 0 && summary.representative.ownerUuid() != player.uniqueId) {
            menu.button(7, button(Material.EMERALD_BLOCK, ui("batch-accept"), ui("batch-accept-detail"), ui("batch-decrement"), ui("sign-required"))) {
                val action = PendingAction.simple(
                    PendingAction.Kind.ACCEPT_BATCH,
                    summary.representative,
                    batchId,
                    ui("batch-confirm"),
                    listOf(ui("batch-confirm-select"), ui("batch-confirm-one")),
                )
                openConfirm(player, action) { openBatchDetails(player, batchId, current, back) }
            }
        }
        val start = (current - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, summary.children.size)
        for (index in start until end) {
            val child = summary.children[index]
            val childId = child.id()
            menu.button(BOARD_SLOTS[index - start], render.contractItem(child, null, progressLabel(player, child))) {
                plugin.storage().findByPrefix(childId).ifPresent { openDetails(player, it, false) { openBatchDetails(player, batchId, current, back) } }
            }
        }
        menu.button(45, button(Material.ARROW, ui("nav-prev"), pageLore(current, pages))) { if (current > 1) openBatchDetails(player, batchId, current - 1, back) }
        menu.button(49, button(Material.ARROW, ui("nav-back"), ui("nav-back-hall"))) { back() }
        menu.button(53, button(Material.ARROW, ui("nav-next"), pageLore(current, pages))) { if (current < pages) openBatchDetails(player, batchId, current + 1, back) }
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
        val menu = Menu(ui("admin-title"), 6)
        fillBorder(menu.inventory)

        menu.button(1, adminFilterButton(AdminFilter.DISPUTED, filter, Material.REDSTONE, ui("admin-filter-disputed"))) { openAdmin(player, AdminFilter.DISPUTED, 1) }
        menu.button(2, adminFilterButton(AdminFilter.ACTIVE, filter, Material.CLOCK, ui("admin-filter-active"))) { openAdmin(player, AdminFilter.ACTIVE, 1) }
        menu.button(3, adminFilterButton(AdminFilter.ALL, filter, Material.COMPASS, ui("admin-filter-all"))) { openAdmin(player, AdminFilter.ALL, 1) }

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

        menu.button(45, button(Material.ARROW, ui("nav-prev"), pageLore(current, pages))) { if (current > 1) openAdmin(player, filter, current - 1) }
        menu.button(49, button(Material.EMERALD, ui("nav-back-hall-button"), ui("admin-contract-count", mapOf("count" to contracts.size.toString())))) { open(player) }
        menu.button(53, button(Material.ARROW, ui("nav-next"), pageLore(current, pages))) { openAdmin(player, filter, current + 1) }

        registry.open(player, menu)
    }

    // ---- Details -------------------------------------------------------------------------------

    private fun openDetails(player: Player, contract: Contract, adminMode: Boolean, back: () -> Unit) {
        val menu = Menu(ui("detail-title", mapOf("id" to contract.shortId())), 3)
        fillBorder(menu.inventory)
        menu.decoration(13, render.detailItem(contract))

        if (adminMode) {
            renderAdminActions(menu, player, contract, back)
        } else {
            renderParticipantActions(menu, player, contract, back)
        }
        menu.button(22, button(Material.ARROW, ui("nav-back"), ui("nav-back-detail"))) { back() }

        registry.open(player, menu)
    }

    private fun renderParticipantActions(menu: Menu, player: Player, contract: Contract, back: () -> Unit) {
        val contractId = contract.id()
        fun action(slot: Int, icon: ItemStack) = menu.button(slot, icon, participantHandler(player, contractId, slot, back))

        val mediator = isArbiter(contract, player.uniqueId)
        if (contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            action(10, button(Material.LECTERN, ui("action-mediate-accept"), ui("action-mediate-accept-detail")))
        } else if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
            if (contract.type() == ContractType.SERVICE) {
                action(10, button(Material.EMERALD, ui("action-mediate-pay"), ui("action-mediate-pay-detail")))
                action(11, button(Material.REDSTONE, ui("action-mediate-refund"), ui("action-mediate-refund-detail")))
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                action(10, button(Material.LIME_WOOL, ui("action-mediate-a"), ui("action-mediate-a-detail")))
                action(11, button(Material.RED_WOOL, ui("action-mediate-b"), ui("action-mediate-b-detail")))
                action(12, button(Material.GOLD_INGOT, ui("action-mediate-return"), ui("action-mediate-return-detail")))
            }
        } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
            action(10, button(Material.EMERALD_BLOCK, ui("action-accept-invite"), ui("action-accept-invite-detail"), ui("sign-required")))
        } else if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
            action(10, button(Material.EMERALD_BLOCK, ui("action-accept"), ui("action-accept-escrow", mapOf("value" to rewardSummary(contract))), ui("sign-required")))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.IN_PROGRESS && player.uniqueId == contract.contractorUuid()) {
            val objective = contract.objective()
            if (objective == null) {
                action(10, button(Material.DIAMOND, ui("action-submit"), ui("action-submit-detail")))
            } else if (objective.type() == ObjectiveType.DELIVER_ITEM) {
                action(
                    10,
                    button(
                        Material.CHEST,
                        ui("action-deliver-item"),
                        ui("action-deliver-item-need", mapOf("target" to objective.target(), "amount" to objective.remaining().toString())),
                        ui("action-deliver-item-store"),
                        ui("action-objective-auto"),
                    ),
                )
            } else if (objective.type() == ObjectiveType.DELIVER_MONEY) {
                action(
                    10,
                    button(
                        Material.GOLD_INGOT,
                        ui("action-deliver-money"),
                        ui("action-deliver-money-need", mapOf("value" to plugin.economy().format(BigDecimal.valueOf(objective.remaining().toLong())))),
                        ui("action-deliver-money-auto"),
                    ),
                )
            } else {
                menu.decoration(
                    10,
                    button(
                        Material.COMPASS,
                        ui("action-objective-tracking"),
                        ui("action-objective-target", mapOf("target" to objective.target(), "progress" to objective.progressText())),
                        ui("action-objective-auto"),
                    ),
                )
            }
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.COMPLETED && player.uniqueId == contract.ownerUuid() && contract.hasDeliveryItems()) {
            action(10, button(Material.CHEST, ui("action-claim-delivery"), ui("action-claim-stored", mapOf("count" to contract.deliveryItemCount().toString())), ui("action-claim-space")))
        }
        if (contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.COMPLETED && player.uniqueId == contract.contractorUuid() && contract.hasRewardItems()) {
            action(10, button(Material.CHEST, ui("action-claim-reward"), ui("action-claim-stored", mapOf("count" to contract.rewardItemCount().toString())), ui("action-claim-space")))
        }
        if (contract.type() == ContractType.SERVICE &&
            (contract.status() == ContractStatus.CANCELLED || contract.status() == ContractStatus.EXPIRED) &&
            player.uniqueId == contract.ownerUuid() &&
            contract.hasRewardItems()
        ) {
            action(10, button(Material.CHEST, ui("action-reclaim-reward"), ui("action-claim-stored", mapOf("count" to contract.rewardItemCount().toString())), ui("action-claim-space")))
        }
        if (contract.type() == ContractType.SERVICE && !contract.systemVerifiedService() && contract.status() == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
            action(10, button(Material.EMERALD, ui("action-approve"), ui("action-approve-amount", mapOf("value" to plugin.economy().format(contract.payoutAmount()))), ui("sign-required")))
        }
        if (contract.type() == ContractType.SERVICE && !contract.systemVerifiedService() && contract.status() == ContractStatus.IN_PROGRESS && player.uniqueId == contract.ownerUuid()) {
            action(
                10,
                button(
                    Material.EMERALD,
                    ui("action-approve-early"),
                    ui("action-approve-early-warn"),
                    ui("action-approve-amount", mapOf("value" to plugin.economy().format(contract.payoutAmount()))),
                    ui("sign-required"),
                ),
            )
        }
        if (contract.type() == ContractType.PARTNERSHIP && contract.status() == ContractStatus.IN_PROGRESS && isParty(contract, player.uniqueId)) {
            action(10, button(Material.EMERALD, ui("action-partner-approve"), ui("action-partner-approve-detail"), ui("sign-required")))
        }
        if (contract.type() == ContractType.WAGER && (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED) && isArbiter(contract, player.uniqueId)) {
            action(10, button(Material.LIME_WOOL, ui("action-resolve-a"), ui("action-resolve-a-detail")))
            action(11, button(Material.RED_WOOL, ui("action-resolve-b"), ui("action-resolve-b-detail")))
        }
        if (!contract.status().isFinal() && canCancel(player, contract)) {
            action(15, button(GuiIcons.DESTRUCTIVE, ui("action-cancel"), ui("action-cancel-detail"), ui("sign-required")))
        }
        if (canDispute(contract)) {
            action(16, button(Material.REDSTONE_BLOCK, ui("action-dispute"), ui("action-dispute-detail"), ui("action-dispute-timeout")))
        }
        if (contract.status() == ContractStatus.DISPUTED && isDisputeInitiator(contract, player.uniqueId)) {
            action(16, button(Material.WRITABLE_BOOK, ui("action-withdraw"), ui("action-withdraw-detail"), ui("action-withdraw-owner")))
        }
    }

    private fun renderAdminActions(menu: Menu, player: Player, contract: Contract, back: () -> Unit) {
        if (contract.status().isFinal()) {
            menu.decoration(11, button(Material.GRAY_DYE, ui("admin-final"), ui("admin-final-detail")))
            return
        }
        val contractId = contract.id()
        menu.button(10, button(Material.EMERALD, ui("admin-pay"), ui("admin-pay-detail"), ui("sign-required")), adminHandler(player, contractId, 10, back))
        menu.button(12, button(Material.REDSTONE, ui("admin-refund"), ui("admin-refund-detail"), ui("sign-required")), adminHandler(player, contractId, 12, back))
        menu.button(14, button(GuiIcons.DESTRUCTIVE, ui("admin-close"), ui("admin-close-detail"), ui("sign-required")), adminHandler(player, contractId, 14, back))
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
                slot == 10 && contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.COMPLETED && id == contract.ownerUuid() && contract.hasDeliveryItems() ->
                    runDirect(player, plugin.contracts().claimDeliveryItems(player, contract), contract, false, back)
                slot == 10 && contract.type() == ContractType.SERVICE && contract.status() == ContractStatus.COMPLETED && id == contract.contractorUuid() && contract.hasRewardItems() ->
                    runDirect(player, plugin.contracts().claimDeliveryItems(player, contract), contract, false, back)
                slot == 10 && contract.type() == ContractType.SERVICE &&
                    (contract.status() == ContractStatus.CANCELLED || contract.status() == ContractStatus.EXPIRED) &&
                    id == contract.ownerUuid() && contract.hasRewardItems() ->
                    runDirect(player, plugin.contracts().claimDeliveryItems(player, contract), contract, false, back)
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
                val id = mapOf("id" to contract.shortId())
                val action = when (slot) {
                    10 -> PendingAction.simple(PendingAction.Kind.ADMIN_PAY, contract, null, ui("confirm-admin-pay-title", id), listOf(ui("confirm-admin-pay-1"), ui("confirm-admin-pay-2")))
                    12 -> PendingAction.simple(PendingAction.Kind.ADMIN_REFUND, contract, null, ui("confirm-admin-refund-title", id), listOf(ui("confirm-admin-refund-1")))
                    14 -> PendingAction.simple(PendingAction.Kind.ADMIN_CLOSE, contract, null, ui("confirm-admin-close-title", id), listOf(ui("confirm-admin-close-1"), ui("confirm-admin-close-2")))
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
        val id = mapOf("id" to contract.shortId())
        val payout = mapOf("value" to plugin.economy().format(contract.payoutAmount()))
        val commission = mapOf("value" to plugin.economy().format(contract.commissionAmount()))
        if (slot == 10) {
            if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
                if (contract.type() == ContractType.SERVICE) {
                    return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "pay", ui("confirm-mediate-pay-title", id), listOf(ui("confirm-mediate-pay-1"), ui("confirm-mediate-pay-2", payout)))
                }
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "a", ui("confirm-mediate-a-title", id), listOf(ui("confirm-mediate-a-1")))
            }
            if (status == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, ui("confirm-accept-invite-title", id), render.acceptConsequences(contract))
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.OPEN && contract.ownerUuid() != player.uniqueId && !mediator) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null, ui("confirm-accept-title", id), listOf(ui("confirm-accept-1"), ui("confirm-accept-2", mapOf("value" to rewardSummary(contract)))))
            }
            if (contract.type() == ContractType.SERVICE && !contract.systemVerifiedService() && status == ContractStatus.SUBMITTED && player.uniqueId == contract.ownerUuid()) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, ui("confirm-approve-title", id), listOf(ui("confirm-approve-1", payout), ui("confirm-approve-2", commission)))
            }
            if (contract.type() == ContractType.SERVICE && !contract.systemVerifiedService() && status == ContractStatus.IN_PROGRESS && player.uniqueId == contract.ownerUuid()) {
                return PendingAction.simple(
                    PendingAction.Kind.APPROVE,
                    contract,
                    null,
                    ui("confirm-approve-early-title", id),
                    listOf(ui("confirm-approve-early-1"), ui("confirm-approve-1", payout), ui("confirm-approve-2", commission)),
                )
            }
            if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS && isParty(contract, player.uniqueId)) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null, ui("confirm-partner-title", id), listOf(ui("confirm-partner-1"), ui("confirm-partner-2")))
            }
            if (contract.type() == ContractType.WAGER && isArbiter(contract, player.uniqueId) && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "a", ui("confirm-resolve-a-title", id), listOf(ui("confirm-resolve-a-1")))
            }
        }
        if (slot == 11 && isArbiter(contract, player.uniqueId)) {
            if (contract.type() == ContractType.WAGER) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "b", ui("confirm-resolve-b-title", id), listOf(ui("confirm-resolve-b-1")))
            }
            if (contract.type() == ContractType.SERVICE && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund", ui("confirm-mediate-refund-title", id), listOf(ui("confirm-mediate-refund-1")))
            }
            if (contract.type() == ContractType.PARTNERSHIP && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "b", ui("confirm-mediate-b-title", id), listOf(ui("confirm-mediate-b-1")))
            }
        }
        if (slot == 12 && contract.type() == ContractType.PARTNERSHIP && isArbiter(contract, player.uniqueId) && canMediate(contract)) {
            return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund", ui("confirm-mediate-return-title", id), listOf(ui("confirm-mediate-return-1")))
        }
        if (slot == 15 && !contract.status().isFinal() && canCancel(player, contract)) {
            val consequences = if (contract.status() == ContractStatus.SCHEDULED) {
                listOf(ui("schedule-cancel-one"), ui("schedule-cancel-two"))
            } else {
                listOf(ui("confirm-cancel-1"), ui("confirm-cancel-2"))
            }
            return PendingAction.simple(PendingAction.Kind.CANCEL, contract, null, ui("confirm-cancel-title", id), consequences)
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
            player.sendMessage(ui("result-ok"))
            openDetails(player, result.contract() ?: fallback, adminMode, back)
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
        }
    }

    // ---- Template library ---------------------------------------------------------------------

    private fun openTemplateLibrary(player: Player, page: Int) {
        if (!player.hasPermission(TEMPLATE_USE_PERMISSION)) {
            player.sendMessage(plugin.lang().message("no-permission"))
            return
        }
        val templates = plugin.templateService().visibleTo(player.uniqueId, player.hasPermission(TEMPLATE_MANAGE_PERMISSION))
        val pages = pageCount(templates.size)
        val current = clampPage(page, pages)
        val menu = Menu(ui("template-title"), 6)
        fillBorder(menu.inventory)
        val start = (current - 1) * BOARD_SLOTS.size
        val end = min(start + BOARD_SLOTS.size, templates.size)
        for (index in start until end) {
            val template = templates[index]
            menu.button(BOARD_SLOTS[index - start], templateItem(template)) { openTemplateDetails(player, template.id, current) }
        }
        if (templates.isEmpty()) menu.decoration(22, button(Material.CHEST, ui("template-empty"), ui("template-empty-detail")))
        menu.button(45, button(Material.ARROW, ui("nav-prev"), pageLore(current, pages))) { if (current > 1) openTemplateLibrary(player, current - 1) }
        menu.button(49, button(Material.EMERALD, ui("nav-back-hall-button"), ui("template-count", mapOf("count" to templates.size.toString())))) { open(player) }
        menu.button(53, button(Material.ARROW, ui("nav-next"), pageLore(current, pages))) { if (current < pages) openTemplateLibrary(player, current + 1) }
        registry.open(player, menu)
    }

    private fun openTemplateDetails(player: Player, templateId: String, page: Int) {
        val template = plugin.templates().find(templateId)
        if (template == null) {
            player.sendMessage(ui("template-missing"))
            openTemplateLibrary(player, page)
            return
        }
        val menu = Menu(ui("template-title"), 3)
        fillBorder(menu.inventory)
        menu.decoration(13, templateItem(template))
        menu.button(10, button(Material.WRITABLE_BOOK, ui("template-load"), ui("template-load-edit"), ui("template-load-safe"))) {
            drafts[player.uniqueId] = CreateDraft.fromSpec(template.spec)
            openWizardForm(player)
        }
        if (player.hasPermission(TEMPLATE_MANAGE_PERMISSION)) {
            val next = ui(if (template.visibility == TemplateVisibility.PRIVATE) "template-server" else "template-private")
            menu.button(14, button(Material.ENDER_EYE, next, ui("template-scope-detail"))) {
                val result = plugin.templateService().toggleVisibility(player.uniqueId, template.id, true)
                player.sendMessage(if (result.success) ui("template-scope-updated") else ui(result.reason))
                openTemplateDetails(player, template.id, page)
            }
        }
        if (template.ownerUuid == player.uniqueId || player.hasPermission(TEMPLATE_MANAGE_PERMISSION)) {
            menu.button(16, button(GuiIcons.DESTRUCTIVE, ui("template-delete"), ui("template-delete-detail"), ui("template-delete-confirm"))) {
                openTemplateDeleteConfirm(player, template, page)
            }
        }
        menu.button(22, button(Material.ARROW, ui("template-back"))) { openTemplateLibrary(player, page) }
        registry.open(player, menu)
    }

    private fun openTemplateDeleteConfirm(player: Player, template: ContractTemplate, page: Int) {
        val menu = Menu(ui("confirm-title"), 3)
        fillBorder(menu.inventory)
        menu.decoration(13, button(GuiIcons.DESTRUCTIVE, ui("template-delete-title"), ui("template-name-line", mapOf("name" to template.name)), ui("template-delete-safe")))
        menu.button(11, button(Material.ARROW, ui("nav-back"), ui("template-keep"))) { openTemplateDetails(player, template.id, page) }
        menu.button(15, button(Material.REDSTONE_BLOCK, ui("template-delete-permanent"), ui("template-delete-irreversible"))) {
            val result = plugin.templateService().delete(player.uniqueId, template.id, player.hasPermission(TEMPLATE_MANAGE_PERMISSION))
            player.sendMessage(if (result.success) ui("template-deleted") else ui(result.reason))
            openTemplateLibrary(player, page)
        }
        registry.open(player, menu)
    }

    private fun templateItem(template: ContractTemplate): ItemStack = button(
        when (template.spec.type) {
            ContractType.SERVICE -> Material.PAPER
            ContractType.WAGER -> Material.TARGET
            ContractType.PARTNERSHIP -> Material.AMETHYST_CLUSTER
            else -> Material.BOOK
        },
        ui("item-title", mapOf("value" to template.name)),
        ui("template-item-type", mapOf("value" to plugin.lang().type(template.spec.type))),
        ui("template-item-title", mapOf("value" to (template.spec.title ?: ui("field-empty")))),
        ui("template-item-scope", mapOf("value" to ui(if (template.visibility == TemplateVisibility.SERVER) "template-range-server" else "template-range-private"))),
        ui("template-item-owner", mapOf("value" to template.ownerName)),
        ui("template-item-click"),
    )

    private fun saveDraftAsTemplate(player: Player, draft: CreateDraft) {
        input.promptLine(player, ui("template-name-prompt"), false, FIELD_PROMPT_TIMEOUT_MS) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val result = plugin.templateService().save(player.uniqueId, player.name, outcome.text, draft.toSpec())
                player.sendMessage(if (result.success) ui("template-saved") else ui(result.reason, mapOf("limit" to plugin.config.getInt("limits.max-templates-per-player", 32).toString())))
            }
            openWizardForm(player)
        }
    }

    // ---- Create wizard -------------------------------------------------------------------------

    fun openWizardType(player: Player) {
        val menu = Menu(ui("wizard-type-title"), 3)
        fillBorder(menu.inventory)
        menu.button(11, button(Material.PAPER, ui("wizard-type-service"), ui("wizard-type-service-1"), ui("wizard-type-service-2"))) { startDraft(player, ContractType.SERVICE) }
        menu.button(13, button(Material.TARGET, ui("wizard-type-wager"), ui("wizard-type-wager-1"))) { startDraft(player, ContractType.WAGER) }
        menu.button(15, button(Material.AMETHYST_CLUSTER, ui("wizard-type-partnership"), ui("wizard-type-partnership-1"), ui("wizard-type-partnership-2"))) { startDraft(player, ContractType.PARTNERSHIP) }
        menu.button(22, button(Material.ARROW, ui("nav-back-hall-button"), ui("nav-back-hall"))) { cancelDraft(player) }
        registry.open(player, menu)
    }

    private fun startDraft(player: Player, type: ContractType) {
        drafts[player.uniqueId] = CreateDraft(type)
        openWizardForm(player)
    }

    private fun cancelDraft(player: Player) {
        drafts.remove(player.uniqueId)
        player.sendMessage(ui("draft-abandoned"))
        open(player)
    }

    fun openWizardForm(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val dialog = dialogs
        if (dialog != null && draft.type() != ContractType.SERVICE) {
            dialog.createForm(player, draft, { submitCreateForm(player) }, { cancelDraft(player) })
            return
        }
        val menu = Menu(ui("wizard-form-title"), 6)
        fillBorder(menu.inventory)

        menu.decoration(11, button(Material.NAME_TAG, ui("wizard-type-label", mapOf("value" to plugin.lang().type(draft.type()))), ui("wizard-type-label-detail")))
        menu.button(13, fieldButton(Material.WRITABLE_BOOK, ui("field-title"), draft.title())) { promptTextField(player, ui("prompt-title")) { draft.title(it) } }
        menu.button(15, descriptionButton(draft.description())) { beginDescriptionPrompt(player) }
        if (draft.needsCounterparty()) {
            menu.button(20, fieldButton(Material.PLAYER_HEAD, ui("field-counterparty"), draft.counterparty())) { promptTextField(player, ui("prompt-counterparty")) { draft.counterparty(it) } }
        }
        if (draft.type() == ContractType.SERVICE) {
            menu.button(20, rewardModeButton(draft)) {
                draft.itemReward(!draft.itemReward())
                openWizardForm(player)
            }
        }
        if (draft.type() == ContractType.SERVICE && draft.itemReward()) {
            menu.decoration(22, button(Material.CHEST, ui("reward-item-title"), ui("reward-item-1"), ui("reward-item-2")))
        } else {
            val service = draft.type() == ContractType.SERVICE
            menu.button(22, fieldButton(Material.GOLD_INGOT, ui(if (service) "field-reward" else "field-my-stake"), draft.amount()?.let { render.trimNumber(it) })) {
                promptNumberField(player, ui(if (service) "prompt-reward" else "prompt-my-stake")) { draft.amount(it) }
            }
        }
        val mediatorRequired = draft.mediatorRequired()
        menu.button(24, fieldButton(Material.LECTERN, ui(if (mediatorRequired) "field-arbiter" else "field-mediator"), draft.mediator())) {
            promptTextField(player, ui(if (mediatorRequired) "prompt-arbiter" else "prompt-mediator")) { draft.mediator(it) }
        }
        if (draft.needsPartnerStake()) {
            menu.button(29, fieldButton(Material.GOLD_NUGGET, ui("field-partner-stake"), draft.partnerStake()?.let { render.trimNumber(it) })) { promptNumberField(player, ui("prompt-partner-stake")) { draft.partnerStake(it) } }
        }
        if (draft.type() == ContractType.SERVICE) {
            menu.button(29, verificationButton(draft)) { toggleVerification(player, draft) }
            if (player.hasPermission(BATCH_CREATE_PERMISSION)) {
                menu.button(30, fieldButton(Material.PAPER, ui("field-count"), draft.contractCount().toString())) {
                    promptNumberField(player, ui("prompt-count")) { draft.contractCount(Math.round(it).toInt()) }
                }
                if (draft.contractCount() > 1) {
                    menu.button(32, batchRepeatPolicyButton(draft)) { cycleBatchRepeatPolicy(player, draft) }
                    if (draft.repeatPolicy() == BatchRepeatPolicy.COOLDOWN) {
                        menu.button(39, fieldButton(Material.CLOCK, ui("field-cooldown"), draft.repeatCooldownHours().toString())) {
                            promptNumberField(player, ui("prompt-cooldown")) {
                                draft.repeatCooldownHours(Math.round(it).toInt())
                            }
                        }
                    }
                }
            } else {
                draft.contractCount(1)
            }
            if (draft.systemVerified()) {
                menu.button(38, objectiveTypeButton(draft)) { cycleObjectiveType(player, draft) }
                val objectiveType = draft.objectiveType()
                if (objectiveType == ObjectiveType.DELIVER_MONEY) {
                    draft.objectiveTarget("MONEY")
                    menu.decoration(40, defaultMoneyTargetButton())
                } else {
                    menu.button(40, fieldButton(objectiveTargetMaterial(objectiveType), plugin.lang().objectiveTarget(objectiveType), draft.objectiveTarget())) {
                        promptTextField(player, plugin.lang().objectivePrompt(objectiveType)) { draft.objectiveTarget(it) }
                    }
                    if (canUseHandAsObjectiveTarget(objectiveType)) {
                        menu.button(41, handTargetButton()) { setObjectiveTargetFromHand(player, draft) }
                    }
                }
                menu.button(42, fieldButton(Material.TARGET, ui("field-objective-amount"), draft.objectiveRequired()?.toString())) {
                    promptNumberField(player, ui("prompt-objective-amount")) { draft.objectiveRequired(Math.round(it).toInt()) }
                }
            }
        }
        menu.button(31, fieldButton(Material.CLOCK, ui("field-days"), draft.days()?.toString())) { promptNumberField(player, ui("prompt-days")) { draft.days(Math.round(it).toInt()) } }

        menu.decoration(33, button(Material.MAP, ui("preview-title"), *render.draftPreview(draft).toTypedArray()))
        if (draft.type() == ContractType.SERVICE && player.hasPermission(SCHEDULE_CREATE_PERMISSION)) {
            menu.button(43, scheduleButton(draft)) { promptSchedule(player, draft) }
        }
        menu.button(46, button(GuiIcons.DESTRUCTIVE, ui("wizard-abandon"), ui("wizard-abandon-detail"))) { cancelDraft(player) }
        if (player.hasPermission(TEMPLATE_USE_PERMISSION)) {
            menu.button(47, button(Material.CHEST, ui("template-save"), ui("template-save-detail"))) { saveDraftAsTemplate(player, draft) }
        }
        menu.button(48, button(Material.ARROW, ui("wizard-prev"), ui("wizard-prev-detail"))) { openWizardType(player) }

        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays(), maxBatchContracts(), maxRepeatCooldownHours())
        if (validation == null) {
            menu.button(50, button(Material.EMERALD_BLOCK, ui("wizard-sign"), ui("wizard-sign-detail"), ui("sign-required"))) {
                val recheck = draft.validate(minAmount(), maxAmount(), minDays(), maxDays(), maxBatchContracts(), maxRepeatCooldownHours())
                if (recheck != null) {
                    player.sendMessage(problemText(recheck))
                } else {
                    openConfirm(player, createAction(draft)) { openWizardForm(player) }
                }
            }
        } else {
            menu.decoration(50, button(Material.GRAY_DYE, ui("wizard-blocked"), problemText(validation)))
        }

        registry.open(player, menu)
    }

    /** Wraps the draft preview in a localized confirmation page. */
    private fun createAction(draft: CreateDraft): PendingAction =
        PendingAction.create(ui("confirm-create-title"), ui("confirm-create-lead", mapOf("type" to plugin.lang().type(draft.type()))), render.draftPreview(draft))

    // ---- Confirm + execute ---------------------------------------------------------------------

    private fun submitCreateForm(player: Player) {
        val draft = drafts[player.uniqueId]
        if (draft == null) {
            openWizardType(player)
            return
        }
        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays(), maxBatchContracts(), maxRepeatCooldownHours())
        if (validation != null) {
            player.sendMessage(problemText(validation))
            openWizardForm(player)
        } else {
            openConfirm(player, createAction(draft)) { openWizardForm(player) }
        }
    }

    private fun openConfirm(player: Player, action: PendingAction, onReturn: () -> Unit) {
        val dialog = dialogs
        if (dialog != null) {
            dialog.confirm(player, action.title(), action.consequences(), { executeAction(player, action, onReturn) }, onReturn)
            return
        }
        val menu = Menu(ui("confirm-title"), 3)
        fillBorder(menu.inventory)
        val lore = ArrayList<String>()
        lore.add(ui("confirm-action-line", mapOf("value" to action.title())))
        lore.add("")
        for (line in action.consequences()) {
            lore.add(ui("confirm-consequence", mapOf("value" to line)))
        }
        lore.add("")
        lore.add(ui("confirm-note"))
        menu.decoration(13, button(Material.PAPER, ui("confirm-title"), *lore.toTypedArray()))
        menu.button(11, button(GuiIcons.DESTRUCTIVE, ui("confirm-cancel"), ui("confirm-cancel-detail"))) { onReturn() }
        menu.button(15, button(Material.WRITABLE_BOOK, ui("confirm-sign"), ui("confirm-sign-detail"), ui("confirm-sign-warn"))) { executeAction(player, action, onReturn) }
        registry.open(player, menu)
    }

    private fun executeAction(player: Player, action: PendingAction, onReturn: () -> Unit) {
        if (action.kind() == PendingAction.Kind.CREATE) {
            val activeDraft = drafts[player.uniqueId]
            val contractCount = activeDraft?.contractCount() ?: 1
            val scheduled = activeDraft?.publishAt() != null
            val result = executeCreate(player)
            if (result.success()) {
                val key = if (scheduled) "schedule-created" else "result-created"
                player.sendMessage(ui(key, mapOf("count" to contractCount.toString())))
                drafts.remove(player.uniqueId)
                val done = result.contract()
                val mineBack = { openHall(player, if (scheduled) HallView.ACTIVE else HallView.OPEN, 1) }
                if (done != null) openDetails(player, done, false, mineBack) else mineBack()
            } else {
                player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
                onReturn()
            }
            return
        }
        if (action.kind() == PendingAction.Kind.ACCEPT_BATCH) {
            val result = plugin.contracts().acceptOneFromBatch(player, action.arg())
            if (result.success()) {
                player.sendMessage(ui("result-signed"))
            } else {
                player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
            }
            onReturn()
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
            PendingAction.Kind.ACCEPT_BATCH -> ServiceResult.fail("internal")
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
            player.sendMessage(ui("result-signed"))
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
        }
        onReturn()
    }

    private fun executeCreate(player: Player): ServiceResult {
        val draft = drafts[player.uniqueId] ?: return ServiceResult.fail(ui("draft-expired"))
        val validation = draft.validate(minAmount(), maxAmount(), minDays(), maxDays(), maxBatchContracts(), maxRepeatCooldownHours())
        if (validation != null) {
            return ServiceResult.fail(ui(validation.key, validation.placeholders))
        }
        val description = draft.description() ?: ""
        val days = draft.days() ?: return ServiceResult.fail(ui("draft-need-days"))
        val title = draft.title() ?: return ServiceResult.fail(ui("draft-need-title"))
        return when (draft.type()) {
            ContractType.SERVICE -> {
                if (draft.itemReward()) {
                    plugin.contracts().createWithItemReward(
                        player,
                        days,
                        title,
                        description,
                        emptyToNull(draft.mediator()),
                        objectiveFromDraft(draft),
                        draft.contractCount(),
                        draft.repeatPolicy(),
                        draft.repeatCooldownHours(),
                        draft.publishAt(),
                    )
                } else {
                    val amount = draft.amount() ?: return ServiceResult.fail(ui("draft-need-amount"))
                    plugin.contracts().create(
                        player,
                        amount,
                        days,
                        title,
                        description,
                        emptyToNull(draft.mediator()),
                        objectiveFromDraft(draft),
                        draft.contractCount(),
                        draft.repeatPolicy(),
                        draft.repeatCooldownHours(),
                        draft.publishAt(),
                    )
                }
            }
            ContractType.WAGER -> {
                val amount = draft.amount() ?: return ServiceResult.fail(ui("draft-need-amount"))
                val counterparty = draft.counterparty() ?: return ServiceResult.fail(ui("draft-need-counterparty"))
                val mediator = draft.mediator() ?: return ServiceResult.fail(ui("draft-need-arbiter"))
                plugin.contracts().createWager(player, counterparty, BigDecimal.valueOf(amount), days, mediator, title, description)
            }
            ContractType.PARTNERSHIP -> {
                val amount = draft.amount() ?: return ServiceResult.fail(ui("draft-need-amount"))
                val counterparty = draft.counterparty() ?: return ServiceResult.fail(ui("draft-need-counterparty"))
                val partnerStake = draft.partnerStake() ?: return ServiceResult.fail(ui("draft-need-partner-stake"))
                plugin.contracts().createPartnership(player, counterparty, BigDecimal.valueOf(amount), BigDecimal.valueOf(partnerStake), days, title, description, emptyToNull(draft.mediator()))
            }
            else -> ServiceResult.fail(ui("draft-unsupported-type"))
        }
    }

    // ---- Chat-driven inputs --------------------------------------------------------------------

    private fun promptTextField(player: Player, message: String, apply: (String?) -> Unit) {
        input.promptLine(player, message, allowClear = false, FIELD_PROMPT_TIMEOUT_MS) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val text = outcome.text.trim()
                apply(if (text.isEmpty()) null else text)
            }
            openWizardForm(player)
        }
    }

    private fun promptNumberField(player: Player, message: String, apply: (Double) -> Unit) {
        input.promptLine(player, message, allowClear = false, FIELD_PROMPT_TIMEOUT_MS) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val parsed = parseNonNegative(outcome.text.trim())
                if (parsed == null) {
                    player.sendMessage(ui("prompt-number-invalid"))
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
                    val clean = plugin.text().stripControl(outcome.text)
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

    private fun toggleVerification(player: Player, draft: CreateDraft) {
        if (draft.systemVerified()) {
            draft.objectiveType(null)
        } else {
            draft.objectiveType(ObjectiveType.KILL_ENTITY)
            draft.objectiveTarget("ZOMBIE")
            draft.objectiveRequired(1)
        }
        openWizardForm(player)
    }

    private fun cycleObjectiveType(player: Player, draft: CreateDraft) {
        val current = draft.objectiveType() ?: ObjectiveType.KILL_ENTITY
        val types = ObjectiveType.entries
        val next = types[(types.indexOf(current) + 1) % types.size]
        draft.objectiveType(next)
        draft.objectiveTarget(defaultObjectiveTarget(next))
        if (draft.objectiveRequired() == null || (draft.objectiveRequired() ?: 0) <= 0) {
            draft.objectiveRequired(1)
        }
        openWizardForm(player)
    }

    private fun cycleBatchRepeatPolicy(player: Player, draft: CreateDraft) {
        val policies = BatchRepeatPolicy.entries
        val current = draft.repeatPolicy()
        draft.repeatPolicy(policies[(policies.indexOf(current) + 1) % policies.size])
        openWizardForm(player)
    }

    private fun objectiveFromDraft(draft: CreateDraft): ContractObjective? {
        val type = draft.objectiveType() ?: return null
        val required = draft.objectiveRequired() ?: return null
        val target = if (type == ObjectiveType.DELIVER_MONEY) "MONEY" else draft.objectiveTarget()
        return ContractObjective.of(type, target, required)
    }

    private fun setObjectiveTargetFromHand(player: Player, draft: CreateDraft) {
        val type = draft.objectiveType()
        if (!canUseHandAsObjectiveTarget(type)) {
            player.sendMessage(ui("objective-hand-unsupported"))
            openWizardForm(player)
            return
        }
        val hand = player.inventory.itemInMainHand
        if (hand.type == Material.AIR) {
            player.sendMessage(ui("objective-hand-empty"))
            openWizardForm(player)
            return
        }
        draft.objectiveTarget(hand.type.name)
        if (type == ObjectiveType.DELIVER_ITEM) {
            draft.objectiveRequired(hand.amount.coerceAtLeast(1))
        } else if (draft.objectiveRequired() == null || (draft.objectiveRequired() ?: 0) <= 0) {
            draft.objectiveRequired(1)
        }
        player.sendMessage(ui("objective-hand-set", mapOf("target" to hand.type.name)))
        openWizardForm(player)
    }

    private fun defaultObjectiveTarget(type: ObjectiveType): String =
        when (type) {
            ObjectiveType.CRAFT_ITEM -> "BREAD"
            ObjectiveType.BLOCK_BREAK -> "STONE"
            ObjectiveType.FISH -> "ANY"
            ObjectiveType.BLOCK_PLACE -> "TORCH"
            ObjectiveType.KILL_ENTITY -> "ZOMBIE"
            ObjectiveType.KILL_PLAYER -> "ANY"
            ObjectiveType.CONSUME_ITEM -> "BREAD"
            ObjectiveType.DELIVER_ITEM -> "DIAMOND"
            ObjectiveType.ENCHANT_ITEM -> "DIAMOND_SWORD"
            ObjectiveType.SHEAR -> "SHEEP"
            ObjectiveType.BREED -> "COW"
            ObjectiveType.TAME -> "WOLF"
            ObjectiveType.CHAT -> "ANY"
            ObjectiveType.BLOCK_INTERACT -> "ANY"
            ObjectiveType.RUN_COMMAND -> "spawn"
            ObjectiveType.USE_ITEM -> "ENDER_PEARL"
            ObjectiveType.DELIVER_MONEY -> "MONEY"
        }

    private fun beginDisputePrompt(player: Player, contract: Contract, adminMode: Boolean, back: () -> Unit) {
        val contractId = contract.id()
        input.promptLine(
            player,
            ui("dispute-prompt"),
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
                ChatOutcome.Cancelled -> player.sendMessage(ui("dispute-cancelled"))
                ChatOutcome.TimedOut -> player.sendMessage(ui("dispute-timeout"))
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

    private fun hallEntries(player: Player, view: HallView): List<HallEntry> {
        val all = plugin.contracts().allContracts()
        val summaries = BatchQueryService.summaries(all)
        val seenBatches = HashSet<String>()
        return hallContracts(player, view).mapNotNull { contract ->
            val batchId = contract.metadata["batch-id"]
            if (batchId.isNullOrBlank()) {
                HallEntry.Single(contract)
            } else if (seenBatches.add(batchId)) {
                summaries[batchId]?.let(HallEntry::Batch)
            } else {
                null
            }
        }
    }

    private fun promptSchedule(player: Player, draft: CreateDraft) {
        input.promptLine(
            player,
            ui("schedule-prompt", mapOf("zone" to ZoneId.systemDefault().toString())),
            false,
            FIELD_PROMPT_TIMEOUT_MS,
        ) { outcome ->
            if (outcome is ChatOutcome.Submitted) {
                val value = outcome.text.trim()
                if (value.equals("now", true) || value == ui("schedule-now-keyword")) {
                    draft.publishAt(null)
                    player.sendMessage(ui("schedule-now-set"))
                } else {
                    try {
                        val instant = LocalDateTime.parse(value, SCHEDULE_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        if (instant <= System.currentTimeMillis()) {
                            player.sendMessage(ui("schedule-past"))
                        } else {
                            draft.publishAt(instant)
                            player.sendMessage(ui("schedule-future-set"))
                        }
                    } catch (_: DateTimeParseException) {
                        player.sendMessage(ui("schedule-format-error"))
                    }
                }
            }
            openWizardForm(player)
        }
    }

    private fun hallContracts(player: Player, view: HallView): List<Contract> = when (view) {
        HallView.OPEN -> plugin.contracts().allContracts().stream()
            .filter { contract -> contract.status() == ContractStatus.OPEN || contract.status() == ContractStatus.PENDING_ACCEPT && contract.relatedTo(player.uniqueId) }
            .toList()
        HallView.ACTIVE -> plugin.contracts().allContracts().filter {
            it.relatedTo(player.uniqueId) && (it.status() == ContractStatus.SCHEDULED || it.status() == ContractStatus.IN_PROGRESS)
        }
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
            if (!contract.arbiterAccepted()) return ui("inbox-mediator-pending")
            if (contract.type() == ContractType.WAGER && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) return ui("inbox-wager-resolve")
            if (contract.type() != ContractType.WAGER && status == ContractStatus.DISPUTED) return ui("inbox-dispute-resolve")
        }
        if (status == ContractStatus.PENDING_ACCEPT && id == contract.contractorUuid()) return ui("inbox-accept-invite")
        if (contract.type() == ContractType.SERVICE) {
            if (status == ContractStatus.COMPLETED && id == contract.ownerUuid() && contract.hasDeliveryItems()) return ui("inbox-claim-delivery")
            if (status == ContractStatus.COMPLETED && id == contract.contractorUuid() && contract.hasRewardItems()) return ui("inbox-claim-reward")
            if ((status == ContractStatus.CANCELLED || status == ContractStatus.EXPIRED) && id == contract.ownerUuid() && contract.hasRewardItems()) return ui("inbox-reclaim-reward")
            if (status == ContractStatus.IN_PROGRESS && id == contract.contractorUuid()) {
                val objective = contract.objective()
                if (objective == null) return ui("inbox-submit")
                return when (objective.type()) {
                    ObjectiveType.DELIVER_ITEM -> ui("inbox-deliver-item")
                    ObjectiveType.DELIVER_MONEY -> ui("inbox-deliver-money")
                    else -> ui("inbox-objective", mapOf("progress" to objective.progressText()))
                }
            }
            if (!contract.systemVerifiedService() && status == ContractStatus.SUBMITTED && id == contract.ownerUuid()) return ui("inbox-approve")
        }
        if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS && isParty(contract, id)) {
            val approved = contract.metadata.getOrDefault("approved-roles", "")
            val me = contract.participantByUuid(id)
            if (me.isPresent && !approved.contains(me.get().role().name)) return ui("inbox-partner-approve")
        }
        if (status == ContractStatus.DISPUTED && contract.relatedTo(id)) {
            return if (isDisputeInitiator(contract, id)) ui("inbox-dispute-own") else ui("inbox-dispute-other")
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
            return ui("progress-mediator-accepted")
        }
        val objective = contract.objective()
        if (objective != null && status == ContractStatus.IN_PROGRESS) {
            return ui("inbox-objective", mapOf("progress" to objective.progressText()))
        }
        return when (status) {
            ContractStatus.SCHEDULED -> ui("schedule-state")
            ContractStatus.OPEN -> ui("progress-open")
            ContractStatus.PENDING_ACCEPT -> ui("progress-pending-accept")
            ContractStatus.IN_PROGRESS -> ui("progress-in-progress")
            ContractStatus.SUBMITTED -> ui("progress-submitted")
            ContractStatus.DISPUTED -> ui("progress-disputed")
            ContractStatus.COMPLETED -> ui("progress-completed")
            ContractStatus.CANCELLED -> ui("progress-cancelled")
            ContractStatus.EXPIRED -> ui("progress-expired")
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
        if (contract.status() == ContractStatus.DISPUTED) ui("admin-label-disputed") else plugin.lang().status(contract.status())

    private fun rewardSummary(contract: Contract): String {
        val parts = ArrayList<String>()
        if (contract.reward().signum() > 0) {
            parts.add(plugin.economy().format(contract.reward()))
        }
        if (contract.hasRewardItems()) {
            parts.add(ui("item-count", mapOf("count" to contract.rewardItemCount().toString())))
        }
        return if (parts.isEmpty()) plugin.economy().format(BigDecimal.ZERO) else parts.joinToString(" + ")
    }

    // ---- Item rendering (menu chrome; contract icons live in ContractRenderer) -----------------

    private fun fieldButton(material: Material, label: String, value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val current = if (filled) value!! else ui("field-empty")
        val name = ui(if (filled) "field-name-filled" else "field-name-empty", mapOf("label" to label))
        return button(material, name, ui("field-current", mapOf("value" to current)), ui("field-click"))
    }

    private fun defaultMoneyTargetButton(): ItemStack =
        button(Material.GOLD_INGOT, ui("money-target"), ui("money-target-1"), ui("money-target-2"))

    private fun handTargetButton(): ItemStack =
        button(Material.ITEM_FRAME, ui("hand-target"), ui("hand-target-1"), ui("hand-target-2"))

    private fun rewardModeButton(draft: CreateDraft): ItemStack =
        if (draft.itemReward()) {
            button(Material.CHEST, ui("reward-mode-item"), ui("reward-mode-item-1"), ui("reward-mode-item-2"))
        } else {
            button(Material.GOLD_INGOT, ui("reward-mode-money"), ui("reward-mode-money-1"), ui("reward-mode-money-2"))
        }

    private fun scheduleButton(draft: CreateDraft): ItemStack {
        val publishAt = draft.publishAt()
        return if (publishAt == null) {
            button(Material.CLOCK, ui("schedule-button-now"), ui("schedule-button-set"), ui("schedule-button-escrow"))
        } else {
            button(
                Material.CLOCK,
                ui("schedule-button-time", mapOf("time" to SCHEDULE_FORMAT.format(java.time.Instant.ofEpochMilli(publishAt).atZone(ZoneId.systemDefault())))),
                ui("schedule-button-once"),
                ui("schedule-button-change"),
            )
        }
    }

    private fun verificationButton(draft: CreateDraft): ItemStack =
        if (draft.systemVerified()) {
            button(Material.COMPARATOR, ui("verify-system"), ui("verify-system-1"), ui("verify-system-2"))
        } else {
            button(Material.PLAYER_HEAD, ui("verify-manual"), ui("verify-manual-1"), ui("verify-manual-2"))
        }

    private fun batchRepeatPolicyButton(draft: CreateDraft): ItemStack =
        when (draft.repeatPolicy()) {
            BatchRepeatPolicy.UNLIMITED -> button(Material.REPEATER, ui("repeat-unlimited"), ui("repeat-unlimited-1"), ui("repeat-cycle"))
            BatchRepeatPolicy.ONCE -> button(Material.IRON_DOOR, ui("repeat-once"), ui("repeat-once-1"), ui("repeat-cycle"))
            BatchRepeatPolicy.COOLDOWN -> button(Material.CLOCK, ui("repeat-cooldown"), ui("repeat-cooldown-1"), ui("repeat-cycle"))
        }

    private fun objectiveTypeButton(draft: CreateDraft): ItemStack {
        val type = draft.objectiveType() ?: ObjectiveType.KILL_ENTITY
        return button(objectiveTargetMaterial(type), ui("objective-type-button", mapOf("value" to plugin.lang().objective(type))), ui("objective-type-cycle"))
    }

    private fun objectiveTargetMaterial(type: ObjectiveType?): Material =
        when (type) {
            ObjectiveType.CRAFT_ITEM -> Material.CRAFTING_TABLE
            ObjectiveType.BLOCK_BREAK -> Material.IRON_PICKAXE
            ObjectiveType.FISH -> Material.FISHING_ROD
            ObjectiveType.BLOCK_PLACE -> Material.OAK_PLANKS
            ObjectiveType.KILL_ENTITY -> Material.ROTTEN_FLESH
            ObjectiveType.KILL_PLAYER -> Material.IRON_SWORD
            ObjectiveType.CONSUME_ITEM -> Material.BREAD
            ObjectiveType.DELIVER_ITEM -> Material.CHEST
            ObjectiveType.ENCHANT_ITEM -> Material.ENCHANTING_TABLE
            ObjectiveType.SHEAR -> Material.SHEARS
            ObjectiveType.BREED -> Material.WHEAT
            ObjectiveType.TAME -> Material.BONE
            ObjectiveType.CHAT -> Material.WRITABLE_BOOK
            ObjectiveType.BLOCK_INTERACT -> Material.OAK_BUTTON
            ObjectiveType.RUN_COMMAND -> Material.LEVER
            ObjectiveType.USE_ITEM -> Material.ENDER_PEARL
            ObjectiveType.DELIVER_MONEY -> Material.GOLD_INGOT
            null -> Material.COMPASS
        }

    private fun canUseHandAsObjectiveTarget(type: ObjectiveType?): Boolean =
        when (type) {
            ObjectiveType.CRAFT_ITEM,
            ObjectiveType.BLOCK_BREAK,
            ObjectiveType.FISH,
            ObjectiveType.BLOCK_PLACE,
            ObjectiveType.CONSUME_ITEM,
            ObjectiveType.DELIVER_ITEM,
            ObjectiveType.ENCHANT_ITEM,
            ObjectiveType.BLOCK_INTERACT,
            ObjectiveType.USE_ITEM,
            -> true
            else -> false
        }

    private fun descriptionButton(value: String?): ItemStack {
        val filled = !value.isNullOrBlank()
        val name = ui(if (filled) "field-name-filled" else "field-name-empty", mapOf("label" to ui("field-description")))
        return button(
            Material.BOOK,
            name,
            ui("field-current", mapOf("value" to ContractTerms.preview(value, ui("field-empty")))),
            ui("field-chat-click"),
            ui("field-description-hint"),
        )
    }

    private fun adminFilterButton(filter: AdminFilter, selected: AdminFilter, material: Material, label: String): ItemStack =
        button(material, tabLabel(filter == selected, label), ui("admin-filter-switch"))

    private fun viewButton(target: HallView, selected: HallView, material: Material, label: String): ItemStack =
        button(material, tabLabel(target == selected, label), ui("hall-view-switch"))

    /** Tab labels differ only by highlight colour, which lives in the locale file like every other style. */
    private fun tabLabel(selected: Boolean, label: String): String =
        ui(if (selected) "tab-selected" else "tab-unselected", mapOf("label" to label))

    // ---- Predicates / config -------------------------------------------------------------------

    private fun canDispute(contract: Contract): Boolean {
        val status = contract.status()
        return status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED
    }

    private fun canAcceptInvitation(player: Player, contract: Contract): Boolean = player.uniqueId == contract.contractorUuid()

    private fun canCancel(player: Player, contract: Contract): Boolean = contract.participantByUuid(player.uniqueId).isPresent

    private fun canMediate(contract: Contract): Boolean =
        contract.arbiterAccepted() && !contract.status().isFinal() && contract.status() != ContractStatus.SCHEDULED && contract.status() != ContractStatus.OPEN && contract.status() != ContractStatus.PENDING_ACCEPT

    private fun isParty(contract: Contract, uuid: UUID): Boolean =
        contract.participantByUuid(uuid).map { participant -> participant.role() == ParticipantRole.PARTY_A || participant.role() == ParticipantRole.PARTY_B }.orElse(false)

    private fun isArbiter(contract: Contract, uuid: UUID): Boolean = contract.arbiter()?.uuid() == uuid

    private fun isDisputeInitiator(contract: Contract, uuid: UUID): Boolean = contract.metadata["dispute-by"] == uuid.toString()

    private fun pageCount(size: Int): Int = max(1, ceil(size.toDouble() / BOARD_SLOTS.size).toInt())

    private fun clampPage(page: Int, pages: Int): Int = min(max(1, page), pages)

    /** Shorthand for a `ui.*` language lookup; resolved per call so `/contract admin reload` applies. */
    private fun ui(key: String, placeholders: Map<String, String> = emptyMap()): String = plugin.lang().ui(key, placeholders)

    private fun pageLore(current: Int, pages: Int): String =
        ui("nav-page", mapOf("page" to current.toString(), "pages" to pages.toString()))

    private fun problemText(problem: DraftProblem): String =
        ui("problem-line", mapOf("value" to ui(problem.key, problem.placeholders)))

    private fun hallTitle(view: HallView): String = when (view) {
        HallView.OPEN -> ui("hall-title-open")
        HallView.ACTIVE -> ui("hall-title-active")
        HallView.SUBMITTED -> ui("hall-title-submitted")
        HallView.DISPUTED -> ui("hall-title-disputed")
        HallView.DONE -> ui("hall-title-done")
        HallView.CLOSED -> ui("hall-title-closed")
    }

    private fun emptyHint(view: HallView): String = when (view) {
        HallView.OPEN -> ui("hall-empty-open")
        HallView.ACTIVE -> ui("hall-empty-active")
        HallView.SUBMITTED -> ui("hall-empty-submitted")
        HallView.DISPUTED -> ui("hall-empty-disputed")
        HallView.DONE -> ui("hall-empty-done")
        HallView.CLOSED -> ui("hall-empty-closed")
    }

    private fun minAmount(): Double = plugin.config.getDouble("economy.min-reward", 100.0)

    private fun maxAmount(): Double = plugin.config.getDouble("economy.max-reward", 100000.0)

    private fun minDays(): Int = plugin.config.getInt("limits.min-deadline-days", 1)

    private fun maxDays(): Int = plugin.config.getInt("limits.max-deadline-days", 7)

    private fun maxBatchContracts(): Int = plugin.config.getInt("limits.max-batch-contracts", 64).coerceAtLeast(1)

    private fun maxRepeatCooldownHours(): Int =
        plugin.config.getInt("limits.max-repeat-cooldown-hours", 8760).coerceAtLeast(1)

    enum class HallView { OPEN, ACTIVE, SUBMITTED, DISPUTED, DONE, CLOSED }

    enum class AdminFilter { DISPUTED, ACTIVE, ALL }

    private sealed interface HallEntry {
        data class Single(val contract: Contract) : HallEntry
        data class Batch(val summary: BatchSummary) : HallEntry
    }

    companion object {
        private val BOARD_SLOTS = intArrayOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)
        private const val DISPUTE_PROMPT_TIMEOUT_MS = 60_000L
        private const val DESCRIPTION_PROMPT_TIMEOUT_MS = 120_000L
        private const val FIELD_PROMPT_TIMEOUT_MS = 60_000L
        private const val BATCH_CREATE_PERMISSION = "contract.create.batch"
        private const val TEMPLATE_USE_PERMISSION = "contract.template.use"
        private const val TEMPLATE_MANAGE_PERMISSION = "contract.template.manage"
        private const val SCHEDULE_CREATE_PERMISSION = "contract.schedule.create"
        private val SCHEDULE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun parseNonNegative(text: String): Double? =
            try {
                val value = text.toDouble()
                if (value >= 0 && value.isFinite()) value else null
            } catch (ex: NumberFormatException) {
                null
            }

        private fun emptyToNull(value: String?): String? = if (value.isNullOrBlank()) null else value
    }
}
