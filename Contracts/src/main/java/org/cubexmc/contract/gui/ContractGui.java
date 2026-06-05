package org.cubexmc.contract.gui;

import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.Participant;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.service.ServiceResult;
import org.cubexmc.contract.util.Text;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ContractGui implements Listener {
    private static final String HUB_TITLE = Text.color("&#F4D03F合同工作台");
    private static final String INBOX_TITLE = Text.color("&#F4D03F行动收件箱");
    private static final String BOARD_TITLE = Text.color("&#F4D03F合同大厅");
    private static final String MY_TITLE = Text.color("&#F4D03F我的合同");
    private static final String DETAIL_TITLE_PREFIX = Text.color("&#F4D03F合同详情 ");
    private static final String WIZARD_TYPE_TITLE = Text.color("&#F4D03F创建合同 · 选择类型");
    private static final String WIZARD_FORM_TITLE = Text.color("&#F4D03F创建合同 · 填写");
    private static final String CONFIRM_TITLE = Text.color("&#F4D03F签署确认");
    private static final String ADMIN_TITLE = Text.color("&#E63946管理员工作台");

    private static final int[] BOARD_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private static final long DISPUTE_PROMPT_TIMEOUT_MS = 60_000L;
    private static final long DESCRIPTION_PROMPT_TIMEOUT_MS = 120_000L;

    private final ContractPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    // disputePrompts/descriptionPrompts are read and mutated from the async chat thread (onChat) as well as the
    // main thread, so they must be concurrent. The rest are main-thread only.
    private final Map<UUID, DisputePrompt> disputePrompts = new ConcurrentHashMap<>();
    private final Map<UUID, DescriptionPrompt> descriptionPrompts = new ConcurrentHashMap<>();
    private final Map<UUID, CreateDraft> drafts = new HashMap<>();
    private final Set<UUID> anvilSuppressReopen = new HashSet<>();

    public ContractGui(ContractPlugin plugin) {
        this.plugin = plugin;
    }

    // === Hub ===

    public void openHub(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 54, HUB_TITLE);
        fillBorder(inventory);
        long actionCount = inboxContracts(player).size();

        inventory.setItem(20, button(Material.WRITABLE_BOOK, "&#69DB7C创建合同",
            "&#CFD8DC发布委托、对赌或合作",
            "&#CFD8DC全程 GUI 向导,无需记命令"));
        inventory.setItem(22, button(actionCount > 0 ? Material.BELL : Material.CHEST,
            "&#FFE066行动收件箱 &#FFFFFF(" + actionCount + ")",
            "&#CFD8DC需要你接受、确认、裁决或处理的合同"));
        inventory.setItem(24, button(Material.EMERALD, "&#69DB7C合同大厅",
            "&#CFD8DC浏览公开可接取的委托"));
        inventory.setItem(30, button(Material.BOOK, "&#69DB7C我的合同",
            "&#CFD8DC我发布、接取或参与的全部合同"));
        inventory.setItem(32, button(Material.KNOWLEDGE_BOOK, "&#FFE066帮助",
            "&#CFD8DC查看命令与资金规则说明"));
        if (player.hasPermission("contract.admin.view")) {
            inventory.setItem(49, button(Material.CRAFTING_TABLE, "&#E63946管理员工作台",
                "&#CFD8DC处理争议、中断结算与全部合同"));
        }

        sessions.put(player.getUniqueId(), Session.hub());
        player.openInventory(inventory);
    }

    // === Action inbox ===

    public void openInbox(Player player, int page) {
        List<Contract> contracts = inboxContracts(player);
        int pages = pageCount(contracts.size());
        int currentPage = clampPage(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 54, INBOX_TITLE);
        fillBorder(inventory);

        Session session = Session.list(ViewType.INBOX, currentPage);
        int start = (currentPage - 1) * BOARD_SLOTS.length;
        int end = Math.min(start + BOARD_SLOTS.length, contracts.size());
        for (int index = start; index < end; index++) {
            int slot = BOARD_SLOTS[index - start];
            Contract contract = contracts.get(index);
            inventory.setItem(slot, contractItem(contract, inboxLabel(player, contract)));
            session.slotContracts.put(slot, contract.id());
        }
        if (contracts.isEmpty()) {
            inventory.setItem(22, button(Material.LIME_DYE, "&#69DB7C没有待办事项",
                "&#CFD8DC当前没有需要你处理的合同"));
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC回到合同工作台"));
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    // === Board ===

    public void openBoard(Player player, BoardMode mode, int page) {
        openBoard(player, mode, page, TypeFilter.ALL);
    }

    public void openBoard(Player player, BoardMode mode, int page, TypeFilter filter) {
        List<Contract> contracts = contractsFor(player, mode, filter);
        int pages = pageCount(contracts.size());
        int currentPage = clampPage(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 54, mode == BoardMode.MINE ? MY_TITLE : BOARD_TITLE);
        Session session = Session.board(mode, currentPage, filter);

        fillBorder(inventory);
        inventory.setItem(1, filterButton(TypeFilter.ALL, filter, Material.COMPASS, "全部"));
        inventory.setItem(2, filterButton(TypeFilter.SERVICE, filter, Material.PAPER, "委托"));
        inventory.setItem(3, filterButton(TypeFilter.WAGER, filter, Material.TARGET, "对赌"));
        inventory.setItem(4, filterButton(TypeFilter.PARTNERSHIP, filter, Material.AMETHYST_CLUSTER, "合作"));

        int start = (currentPage - 1) * BOARD_SLOTS.length;
        int end = Math.min(start + BOARD_SLOTS.length, contracts.size());
        for (int index = start; index < end; index++) {
            int slot = BOARD_SLOTS[index - start];
            Contract contract = contracts.get(index);
            inventory.setItem(slot, contractItem(contract, null));
            session.slotContracts.put(slot, contract.id());
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC合同数: &#FFFFFF" + contracts.size()));
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(47, button(Material.CHEST, "&#69DB7C我的合同", "&#CFD8DC查看我参与的合同"));
        inventory.setItem(51, button(Material.EMERALD, "&#69DB7C公开合同", "&#CFD8DC返回合同大厅"));

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    // === Admin workbench ===

    public void openAdmin(Player player, AdminFilter filter, int page) {
        if (!player.hasPermission("contract.admin.view")) {
            player.sendMessage(plugin.lang().message("no-permission"));
            return;
        }
        List<Contract> contracts = adminContracts(filter);
        int pages = pageCount(contracts.size());
        int currentPage = clampPage(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 54, ADMIN_TITLE);
        fillBorder(inventory);
        inventory.setItem(1, adminFilterButton(AdminFilter.DISPUTED, filter, Material.REDSTONE, "争议/中断结算"));
        inventory.setItem(2, adminFilterButton(AdminFilter.ACTIVE, filter, Material.CLOCK, "进行中"));
        inventory.setItem(3, adminFilterButton(AdminFilter.ALL, filter, Material.COMPASS, "全部"));

        Session session = Session.admin(filter, currentPage);
        int start = (currentPage - 1) * BOARD_SLOTS.length;
        int end = Math.min(start + BOARD_SLOTS.length, contracts.size());
        for (int index = start; index < end; index++) {
            int slot = BOARD_SLOTS[index - start];
            Contract contract = contracts.get(index);
            inventory.setItem(slot, contractItem(contract, adminLabel(contract)));
            session.slotContracts.put(slot, contract.id());
        }

        inventory.setItem(45, button(Material.ARROW, "&#FFE066上一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));
        inventory.setItem(49, button(Material.NETHER_STAR, "&#F4D03F返回工作台", "&#CFD8DC合同数: &#FFFFFF" + contracts.size()));
        inventory.setItem(53, button(Material.ARROW, "&#FFE066下一页", "&#CFD8DC第 " + currentPage + "/" + pages + " 页"));

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    // === Contract details ===

    public void openDetails(Player player, Contract contract, BoardMode backMode, int backPage) {
        openDetails(player, contract, ViewType.BOARD, backMode, backPage, TypeFilter.ALL, false);
    }

    public void openDetails(Player player, Contract contract, BoardMode backMode, int backPage, TypeFilter filter) {
        openDetails(player, contract, ViewType.BOARD, backMode, backPage, filter, false);
    }

    private void openDetails(Player player, Contract contract, ViewType origin, BoardMode backMode,
                             int backPage, TypeFilter filter, boolean adminMode) {
        Inventory inventory = Bukkit.createInventory(null, 27,
            DETAIL_TITLE_PREFIX + Text.color("&#FFE066#" + contract.shortId()));
        fillBorder(inventory);
        inventory.setItem(13, detailItem(contract));

        if (adminMode) {
            renderAdminActions(inventory, contract);
        } else {
            renderParticipantActions(inventory, player, contract);
        }
        inventory.setItem(22, button(Material.ARROW, "&#FFE066返回", "&#CFD8DC回到上一页"));

        Session session = Session.details(origin, backMode, backPage, filter, contract.id(), adminMode);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    private void renderParticipantActions(Inventory inventory, Player player, Contract contract) {
        boolean mediator = isArbiter(contract, player.getUniqueId());
        if (contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            inventory.setItem(10, button(Material.LECTERN, "&#69DB7C接受中间人职责",
                "&#CFD8DC接受后可在争议或失效时裁决"));
        } else if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
            if (contract.type() == ContractType.SERVICE) {
                inventory.setItem(10, button(Material.EMERALD, "&#69DB7C裁定付款", "&#CFD8DC认定合同有效完成并付款"));
                inventory.setItem(11, button(Material.REDSTONE, "&#E63946裁定退款", "&#CFD8DC认定合同失效并退款"));
            } else if (contract.type() == ContractType.PARTNERSHIP) {
                inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC甲方获得争议裁决"));
                inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC乙方获得争议裁决"));
                inventory.setItem(12, button(Material.GOLD_INGOT, "&#FFE066退回双方押注", "&#CFD8DC按失败/失效规则退款"));
            }
        } else if (contract.status() == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接受邀请",
                "&#CFD8DC将扣除你的押注并进入进行中",
                "&#FFE066需要铁砧签署确认"));
        } else if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.OPEN
            && !contract.ownerUuid().equals(player.getUniqueId())
            && !mediator) {
            inventory.setItem(10, button(Material.EMERALD_BLOCK, "&#69DB7C接下合同",
                "&#CFD8DC托管奖金: &#69DB7C" + plugin.economy().format(contract.reward()),
                "&#FFE066需要铁砧签署确认"));
        }
        if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.IN_PROGRESS
            && player.getUniqueId().equals(contract.contractorUuid())) {
            inventory.setItem(10, button(Material.DIAMOND, "&#CDE0F5提交完成", "&#CFD8DC等待雇主确认后付款"));
        }
        if (contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.SUBMITTED
            && player.getUniqueId().equals(contract.ownerUuid())) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认付款",
                "&#CFD8DC付款: &#69DB7C" + plugin.economy().format(contract.payoutAmount()),
                "&#FFE066需要铁砧签署确认"));
        }
        if (contract.type() == ContractType.PARTNERSHIP
            && contract.status() == ContractStatus.IN_PROGRESS
            && isParty(contract, player.getUniqueId())) {
            inventory.setItem(10, button(Material.EMERALD, "&#69DB7C确认合作完成",
                "&#CFD8DC双方确认后按合作规则结算",
                "&#FFE066需要铁砧签署确认"));
        }
        if (contract.type() == ContractType.WAGER
            && (contract.status() == ContractStatus.IN_PROGRESS || contract.status() == ContractStatus.SUBMITTED)
            && isArbiter(contract, player.getUniqueId())) {
            inventory.setItem(10, button(Material.LIME_WOOL, "&#69DB7C裁定 A 胜", "&#CFD8DC将按对赌规则付款给甲方"));
            inventory.setItem(11, button(Material.RED_WOOL, "&#E63946裁定 B 胜", "&#CFD8DC将按对赌规则付款给乙方"));
        }
        if (!contract.status().isFinal() && canCancel(player, contract)) {
            inventory.setItem(15, button(Material.BARRIER, "&#E63946取消合同",
                "&#CFD8DC按规则退款或转入争议",
                "&#FFE066需要铁砧签署确认"));
        }
        if (canDispute(contract)) {
            inventory.setItem(16, button(Material.REDSTONE_BLOCK, "&#E63946发起争议", "&#CFD8DC点击后在聊天输入原因",
                "&#CFD8DC60 秒内输入,或输 cancel 取消"));
        }
    }

    private void renderAdminActions(Inventory inventory, Contract contract) {
        if (contract.status().isFinal()) {
            inventory.setItem(13, detailItem(contract));
            inventory.setItem(11, button(Material.GRAY_DYE, "&#CFD8DC合同已结束", "&#CFD8DC无可执行的管理操作"));
            return;
        }
        inventory.setItem(10, button(Material.EMERALD, "&#69DB7C强制付款",
            "&#CFD8DC按成功结算规则付款",
            "&#FFE066需要铁砧签署确认"));
        inventory.setItem(12, button(Material.REDSTONE, "&#E63946强制退款",
            "&#CFD8DC按失败规则退回托管",
            "&#FFE066需要铁砧签署确认"));
        inventory.setItem(14, button(Material.BARRIER, "&#E63946关闭合同",
            "&#CFD8DC不移动任何资金,需人工核对",
            "&#FFE066需要铁砧签署确认"));
    }

    // === Creation wizard ===

    public void openWizardType(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, WIZARD_TYPE_TITLE);
        fillBorder(inventory);
        inventory.setItem(11, button(Material.PAPER, "&#69DB7C委托 SERVICE",
            "&#CFD8DC你出钱托管,公开等待接单者完成",
            "&#CFD8DC完成后向接单者付款"));
        inventory.setItem(13, button(Material.TARGET, "&#69DB7C对赌 WAGER",
            "&#CFD8DC双方等额押注,指定仲裁者裁决胜负"));
        inventory.setItem(15, button(Material.AMETHYST_CLUSTER, "&#69DB7C合作 PARTNERSHIP",
            "&#CFD8DC双方各自押注,双方确认后结算",
            "&#CFD8DC可选指定中间人"));
        inventory.setItem(22, button(Material.ARROW, "&#FFE066返回工作台", "&#CFD8DC回到合同工作台"));

        sessions.put(player.getUniqueId(), Session.wizardType());
        player.openInventory(inventory);
    }

    public void openWizardForm(Player player) {
        CreateDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openWizardType(player);
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 54, WIZARD_FORM_TITLE);
        fillBorder(inventory);

        inventory.setItem(11, button(Material.NAME_TAG, "&#FFE066类型: &#FFFFFF" + plugin.lang().type(draft.type()),
            "&#CFD8DC如需更换类型请返回上一步"));
        inventory.setItem(13, fieldButton(Material.WRITABLE_BOOK, "标题", draft.title()));
        inventory.setItem(15, descriptionButton(draft.description()));
        if (draft.needsCounterparty()) {
            inventory.setItem(20, fieldButton(Material.PLAYER_HEAD, "对方玩家", draft.counterparty()));
        }
        inventory.setItem(22, fieldButton(Material.GOLD_INGOT,
            draft.type() == ContractType.SERVICE ? "奖金" : "我的押注",
            draft.amount() == null ? null : trimNumber(draft.amount())));
        inventory.setItem(24, fieldButton(Material.LECTERN,
            draft.mediatorRequired() ? "仲裁者" : "中间人(可选)", draft.mediator()));
        if (draft.needsPartnerStake()) {
            inventory.setItem(29, fieldButton(Material.GOLD_NUGGET, "对方押注",
                draft.partnerStake() == null ? null : trimNumber(draft.partnerStake())));
        }
        inventory.setItem(31, fieldButton(Material.CLOCK, "期限(小时)",
            draft.hours() == null ? null : String.valueOf(draft.hours())));

        List<String> preview = new ArrayList<>();
        for (String line : draftPreview(draft)) {
            preview.add(line);
        }
        inventory.setItem(33, button(Material.MAP, "&#F4D03F条款预览", preview.toArray(new String[0])));

        inventory.setItem(48, button(Material.ARROW, "&#FFE066上一步", "&#CFD8DC重新选择合同类型"));
        String validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours());
        if (validation == null) {
            inventory.setItem(50, button(Material.EMERALD_BLOCK, "&#69DB7C预览并签署创建",
                "&#CFD8DC进入签署确认页",
                "&#FFE066需要铁砧签署确认"));
        } else {
            inventory.setItem(50, button(Material.GRAY_DYE, "&#CFD8DC尚不能创建", "&#E63946" + validation));
        }

        sessions.put(player.getUniqueId(), Session.wizardForm());
        player.openInventory(inventory);
    }

    // === Confirmation page ===

    private void openConfirm(Player player, PendingAction action, Session origin) {
        Inventory inventory = Bukkit.createInventory(null, 27, CONFIRM_TITLE);
        fillBorder(inventory);

        List<String> lore = new ArrayList<>();
        lore.add(Text.color("&#FFE066动作: &#FFFFFF" + action.title()));
        lore.add("");
        for (String line : action.consequences()) {
            lore.add(Text.color("&#CFD8DC" + line));
        }
        lore.add("");
        lore.add(Text.color("&#E63946点击签署后将立即生效。"));
        inventory.setItem(13, button(Material.PAPER, "&#F4D03F签署确认", lore.toArray(new String[0])));

        inventory.setItem(11, button(Material.BARRIER, "&#E63946取消", "&#CFD8DC返回,不执行任何操作"));
        inventory.setItem(15, button(Material.WRITABLE_BOOK, "&#69DB7C铁砧签署",
            "&#CFD8DC点击打开铁砧",
            "&#CFD8DC输入你的名字或 \"同意\" 完成签署"));

        Session session = Session.confirm(action, origin);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    public void closeSessions() {
        for (UUID playerId : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && isManagedTitle(player.getOpenInventory().getTitle())) {
                player.closeInventory();
            }
        }
        sessions.clear();
        disputePrompts.clear();
        descriptionPrompts.clear();
        drafts.clear();
        anvilSuppressReopen.clear();
    }

    // === Event handling ===

    @EventHandler(priority = EventPriority.NORMAL)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!isManagedTitle(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        switch (session.type) {
            case HUB -> handleHubClick(player, slot);
            case INBOX -> handleInboxClick(player, session, slot);
            case BOARD -> handleBoardClick(player, session, slot);
            case ADMIN -> handleAdminClick(player, session, slot);
            case DETAILS -> handleDetailsClick(player, session, slot);
            case WIZARD_TYPE -> handleWizardTypeClick(player, slot);
            case WIZARD_FORM -> handleWizardFormClick(player, slot);
            case CONFIRM -> handleConfirmClick(player, session, slot);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !isManagedTitle(player.getOpenInventory().getTitle())) {
                sessions.remove(playerId);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        disputePrompts.remove(playerId);
        descriptionPrompts.remove(playerId);
        drafts.remove(playerId);
        anvilSuppressReopen.remove(playerId);
    }

    private void handleHubClick(Player player, int slot) {
        switch (slot) {
            case 20 -> openWizardType(player);
            case 22 -> openInbox(player, 1);
            case 24 -> openBoard(player, BoardMode.OPEN, 1);
            case 30 -> openBoard(player, BoardMode.MINE, 1);
            case 32 -> {
                player.closeInventory();
                for (String line : plugin.lang().message("help").split("\\R")) {
                    player.sendMessage(line);
                }
            }
            case 49 -> {
                if (player.hasPermission("contract.admin.view")) {
                    openAdmin(player, AdminFilter.DISPUTED, 1);
                }
            }
            default -> {
            }
        }
    }

    private void handleInboxClick(Player player, Session session, int slot) {
        if (slot == 45 && session.page > 1) {
            openInbox(player, session.page - 1);
            return;
        }
        if (slot == 53) {
            openInbox(player, session.page + 1);
            return;
        }
        if (slot == 49) {
            openHub(player);
            return;
        }
        String contractId = session.slotContracts.get(slot);
        if (contractId == null) {
            return;
        }
        plugin.storage().findByPrefix(contractId).ifPresent(contract ->
            openDetails(player, contract, ViewType.INBOX, BoardMode.MINE, session.page, TypeFilter.ALL, false));
    }

    private void handleBoardClick(Player player, Session session, int slot) {
        switch (slot) {
            case 1 -> {
                openBoard(player, session.mode, 1, TypeFilter.ALL);
                return;
            }
            case 2 -> {
                openBoard(player, session.mode, 1, TypeFilter.SERVICE);
                return;
            }
            case 3 -> {
                openBoard(player, session.mode, 1, TypeFilter.WAGER);
                return;
            }
            case 4 -> {
                openBoard(player, session.mode, 1, TypeFilter.PARTNERSHIP);
                return;
            }
            case 45 -> {
                if (session.page > 1) {
                    openBoard(player, session.mode, session.page - 1, session.filter);
                }
                return;
            }
            case 53 -> {
                openBoard(player, session.mode, session.page + 1, session.filter);
                return;
            }
            case 47 -> {
                openBoard(player, BoardMode.MINE, 1, session.filter);
                return;
            }
            case 49 -> {
                openHub(player);
                return;
            }
            case 51 -> {
                openBoard(player, BoardMode.OPEN, 1, session.filter);
                return;
            }
            default -> {
            }
        }
        String contractId = session.slotContracts.get(slot);
        if (contractId == null) {
            return;
        }
        plugin.storage().findByPrefix(contractId).ifPresent(contract ->
            openDetails(player, contract, ViewType.BOARD, session.mode, session.page, session.filter, false));
    }

    private void handleAdminClick(Player player, Session session, int slot) {
        if (!player.hasPermission("contract.admin.view")) {
            player.closeInventory();
            return;
        }
        switch (slot) {
            case 1 -> {
                openAdmin(player, AdminFilter.DISPUTED, 1);
                return;
            }
            case 2 -> {
                openAdmin(player, AdminFilter.ACTIVE, 1);
                return;
            }
            case 3 -> {
                openAdmin(player, AdminFilter.ALL, 1);
                return;
            }
            case 45 -> {
                if (session.page > 1) {
                    openAdmin(player, session.adminFilter, session.page - 1);
                }
                return;
            }
            case 53 -> {
                openAdmin(player, session.adminFilter, session.page + 1);
                return;
            }
            case 49 -> {
                openHub(player);
                return;
            }
            default -> {
            }
        }
        String contractId = session.slotContracts.get(slot);
        if (contractId == null) {
            return;
        }
        plugin.storage().findByPrefix(contractId).ifPresent(contract ->
            openDetails(player, contract, ViewType.ADMIN, BoardMode.OPEN, session.page,
                TypeFilter.ALL, true));
    }

    private void handleWizardTypeClick(Player player, int slot) {
        ContractType type = switch (slot) {
            case 11 -> ContractType.SERVICE;
            case 13 -> ContractType.WAGER;
            case 15 -> ContractType.PARTNERSHIP;
            default -> null;
        };
        if (slot == 22) {
            openHub(player);
            return;
        }
        if (type == null) {
            return;
        }
        drafts.put(player.getUniqueId(), new CreateDraft(type));
        openWizardForm(player);
    }

    private void handleWizardFormClick(Player player, int slot) {
        CreateDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            openWizardType(player);
            return;
        }
        switch (slot) {
            case 13 -> openTextAnvil(player, "输入合同标题", draft.title(),
                text -> draft.title(text.isBlank() ? null : text));
            case 15 -> beginDescriptionPrompt(player);
            case 20 -> {
                if (draft.needsCounterparty()) {
                    openTextAnvil(player, "输入对方玩家名", draft.counterparty(),
                        text -> draft.counterparty(text.isBlank() ? null : text));
                }
            }
            case 22 -> openNumberAnvil(player,
                draft.type() == ContractType.SERVICE ? "输入奖金金额" : "输入我的押注金额",
                draft.amount(), value -> draft.amount(value));
            case 24 -> openTextAnvil(player, draft.mediatorRequired() ? "输入仲裁者玩家名" : "输入中间人玩家名(可留空)",
                draft.mediator(), text -> draft.mediator(text.isBlank() ? null : text));
            case 29 -> {
                if (draft.needsPartnerStake()) {
                    openNumberAnvil(player, "输入对方押注金额", draft.partnerStake(),
                        value -> draft.partnerStake(value));
                }
            }
            case 31 -> openNumberAnvil(player, "输入期限小时数",
                draft.hours() == null ? null : (double) draft.hours(),
                value -> draft.hours((int) Math.round(value)));
            case 48 -> {
                openWizardType(player);
                return;
            }
            case 50 -> {
                String validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours());
                if (validation != null) {
                    player.sendMessage(Text.color("&#E63946" + validation));
                    return;
                }
                openConfirm(player, PendingAction.create(draft, draftPreview(draft)), Session.wizardForm());
            }
            default -> {
            }
        }
    }

    private void handleDetailsClick(Player player, Session session, int slot) {
        Optional<Contract> optional = session.contractId == null
            ? Optional.empty()
            : plugin.storage().findByPrefix(session.contractId);
        if (slot == 22) {
            backFromDetails(player, session);
            return;
        }
        if (optional.isEmpty()) {
            player.closeInventory();
            player.sendMessage(plugin.lang().message("not-found"));
            return;
        }
        Contract contract = optional.get();
        if (session.adminMode) {
            handleAdminDetailClick(player, session, contract, slot);
            return;
        }
        UUID id = player.getUniqueId();
        boolean mediator = isArbiter(contract, id);

        // Direct (non-money) actions: accept mediator duty, submit.
        if (slot == 10 && contract.type() != ContractType.WAGER && mediator && !contract.arbiterAccepted()) {
            runDirect(player, session, contract, plugin.contracts().acceptMediation(player, contract));
            return;
        }
        if (slot == 10 && contract.type() == ContractType.SERVICE
            && contract.status() == ContractStatus.IN_PROGRESS && id.equals(contract.contractorUuid())) {
            runDirect(player, session, contract, plugin.contracts().submit(player, contract));
            return;
        }
        if (slot == 16 && canDispute(contract)) {
            beginDisputePrompt(player, contract, session);
            return;
        }

        PendingAction action = detailAction(player, contract, slot, mediator);
        if (action != null) {
            openConfirm(player, action, session);
        }
    }

    private void handleAdminDetailClick(Player player, Session session, Contract contract, int slot) {
        if (!player.hasPermission("contract.admin.settle")) {
            player.sendMessage(plugin.lang().message("no-permission"));
            return;
        }
        if (contract.status().isFinal()) {
            return;
        }
        PendingAction action = switch (slot) {
            case 10 -> PendingAction.simple(PendingAction.Kind.ADMIN_PAY, contract, null,
                "管理员强制付款 #" + contract.shortId(),
                List.of("按合同的成功结算规则向收款方付款。", "系统佣金照常回收。"));
            case 12 -> PendingAction.simple(PendingAction.Kind.ADMIN_REFUND, contract, null,
                "管理员强制退款 #" + contract.shortId(),
                List.of("按失败规则将托管退回发起方。"));
            case 14 -> PendingAction.simple(PendingAction.Kind.ADMIN_CLOSE, contract, null,
                "管理员关闭合同 #" + contract.shortId(),
                List.of("仅关闭合同,不移动任何资金。", "资金需另行用 pay/refund 或人工核对处理。"));
            default -> null;
        };
        if (action != null) {
            openConfirm(player, action, session);
        }
    }

    private PendingAction detailAction(Player player, Contract contract, int slot, boolean mediator) {
        ContractStatus status = contract.status();
        if (slot == 10) {
            if (contract.type() != ContractType.WAGER && mediator && canMediate(contract)) {
                if (contract.type() == ContractType.SERVICE) {
                    return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "pay",
                        "中间人裁定付款 #" + contract.shortId(),
                        List.of("认定委托有效完成。", "接单者获得 " + plugin.economy().format(contract.payoutAmount()) + "。"));
                }
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "a",
                    "中间人裁定甲方胜 #" + contract.shortId(),
                    List.of("甲方获得争议裁决,取得双方押注。"));
            }
            if (status == ContractStatus.PENDING_ACCEPT && canAcceptInvitation(player, contract)) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null,
                    "接受邀请 #" + contract.shortId(), acceptConsequences(contract));
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.OPEN
                && !contract.ownerUuid().equals(player.getUniqueId()) && !mediator) {
                return PendingAction.simple(PendingAction.Kind.ACCEPT, contract, null,
                    "接下委托 #" + contract.shortId(),
                    List.of("接单本身不扣款。", "完成并经雇主确认后可获得 " + plugin.economy().format(contract.payoutAmount()) + "。"));
            }
            if (contract.type() == ContractType.SERVICE && status == ContractStatus.SUBMITTED
                && player.getUniqueId().equals(contract.ownerUuid())) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null,
                    "确认付款 #" + contract.shortId(),
                    List.of("向接单者支付 " + plugin.economy().format(contract.payoutAmount()) + "。",
                        "系统回收佣金 " + plugin.economy().format(contract.commissionAmount()) + "。"));
            }
            if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS
                && isParty(contract, player.getUniqueId())) {
                return PendingAction.simple(PendingAction.Kind.APPROVE, contract, null,
                    "确认合作完成 #" + contract.shortId(),
                    List.of("记录你的确认。", "双方都确认后各自取回押注(扣除佣金)。"));
            }
            if (contract.type() == ContractType.WAGER && isArbiter(contract, player.getUniqueId())
                && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "a",
                    "裁定甲方胜 #" + contract.shortId(),
                    List.of("甲方获得双方押注,扣除系统佣金。"));
            }
        }
        if (slot == 11 && isArbiter(contract, player.getUniqueId())) {
            if (contract.type() == ContractType.WAGER) {
                return PendingAction.simple(PendingAction.Kind.RESOLVE, contract, "b",
                    "裁定乙方胜 #" + contract.shortId(),
                    List.of("乙方获得双方押注,扣除系统佣金。"));
            }
            if (contract.type() == ContractType.SERVICE && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund",
                    "中间人裁定退款 #" + contract.shortId(),
                    List.of("认定委托失效,雇主取回托管。"));
            }
            if (contract.type() == ContractType.PARTNERSHIP && canMediate(contract)) {
                return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "b",
                    "中间人裁定乙方胜 #" + contract.shortId(),
                    List.of("乙方获得争议裁决,取得双方押注。"));
            }
        }
        if (slot == 12 && contract.type() == ContractType.PARTNERSHIP
            && isArbiter(contract, player.getUniqueId()) && canMediate(contract)) {
            return PendingAction.simple(PendingAction.Kind.MEDIATE, contract, "refund",
                "中间人退回双方押注 #" + contract.shortId(),
                List.of("按失败规则各自退回押注。"));
        }
        if (slot == 15 && !contract.status().isFinal() && canCancel(player, contract)) {
            return PendingAction.simple(PendingAction.Kind.CANCEL, contract, null,
                "取消合同 #" + contract.shortId(),
                List.of("按当前状态退款或转入争议。", "创建费在普通取消时通常不退还。"));
        }
        return null;
    }

    private void handleConfirmClick(Player player, Session session, int slot) {
        if (slot == 11) {
            backFromConfirm(player, session);
            return;
        }
        if (slot == 15) {
            openSignAnvil(player, session);
        }
    }

    // === Signature anvil ===

    private void openSignAnvil(Player player, Session confirmSession) {
        PendingAction action = confirmSession.pending;
        anvilSuppressReopen.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.color("&#F4D03F签署: 输入你的名字"))
            .text("...")
            .itemLeft(new ItemStack(Material.WRITABLE_BOOK))
            .onClick((slot, snapshot) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) {
                    return Collections.emptyList();
                }
                String input = snapshot.getText();
                anvilSuppressReopen.remove(player.getUniqueId());
                if (!Signature.matches(player.getName(), input)) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(Text.color("&#E63946签名不匹配,操作已取消。请输入你的玩家名或 \"同意\"。"));
                        openConfirm(player, action, confirmSession.confirmOrigin);
                    });
                    return List.of(AnvilGUI.ResponseAction.close());
                }
                Bukkit.getScheduler().runTask(plugin, () -> executeAction(player, action));
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .onClose(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (anvilSuppressReopen.remove(player.getUniqueId())) {
                    player.sendMessage(Text.color("&#FFE066已取消签署。"));
                    openConfirm(player, action, confirmSession.confirmOrigin);
                }
            }))
            .open(player));
    }

    private void executeAction(Player player, PendingAction action) {
        ServiceResult result;
        Contract contract = null;
        if (action.kind() == PendingAction.Kind.CREATE) {
            result = executeCreate(player);
        } else {
            contract = plugin.storage().findByPrefix(action.contractId()).orElse(null);
            if (contract == null) {
                player.sendMessage(plugin.lang().message("not-found"));
                openHub(player);
                return;
            }
            result = switch (action.kind()) {
                case ACCEPT -> plugin.contracts().accept(player, contract);
                case APPROVE -> plugin.contracts().approve(player, contract);
                case RESOLVE -> plugin.contracts().resolveWager(player, contract, action.arg());
                case MEDIATE -> plugin.contracts().mediate(player, contract, action.arg());
                case CANCEL -> plugin.contracts().cancel(player, contract);
                case ADMIN_PAY -> plugin.contracts().adminPay(contract, player.getName());
                case ADMIN_REFUND -> plugin.contracts().adminRefund(contract, player.getName());
                case ADMIN_CLOSE -> plugin.contracts().adminClose(contract, player.getName());
                case CREATE -> ServiceResult.fail("internal");
            };
        }
        if (result != null && result.success()) {
            player.sendMessage(Text.color("&#69DB7C签署成功,操作已完成。"));
            if (action.kind() == PendingAction.Kind.CREATE) {
                drafts.remove(player.getUniqueId());
            }
            Contract done = result.contract() != null ? result.contract() : contract;
            if (done != null) {
                openDetails(player, done, ViewType.HUB, BoardMode.MINE, 1, TypeFilter.ALL, false);
            } else {
                openHub(player);
            }
        } else {
            String reason = result == null ? "未知错误" : result.reason();
            player.sendMessage(plugin.lang().message("operation-failed", Map.of("reason", reason)));
            openHub(player);
        }
    }

    private ServiceResult executeCreate(Player player) {
        CreateDraft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            return ServiceResult.fail("创建草稿已失效,请重新开始");
        }
        String validation = draft.validate(minAmount(), maxAmount(), minHours(), maxHours());
        if (validation != null) {
            return ServiceResult.fail(validation);
        }
        String description = draft.description() == null ? "" : draft.description();
        return switch (draft.type()) {
            case SERVICE -> plugin.contracts().create(player, draft.amount(), draft.hours(),
                draft.title(), description, emptyToNull(draft.mediator()));
            case WAGER -> plugin.contracts().createWager(player, draft.counterparty(),
                BigDecimal.valueOf(draft.amount()), draft.hours(), draft.mediator(),
                draft.title(), description);
            case PARTNERSHIP -> plugin.contracts().createPartnership(player, draft.counterparty(),
                BigDecimal.valueOf(draft.amount()), BigDecimal.valueOf(draft.partnerStake()),
                draft.hours(), draft.title(), description, emptyToNull(draft.mediator()));
            default -> ServiceResult.fail("不支持的合同类型");
        };
    }

    // === Description chat input ===

    private void beginDescriptionPrompt(Player player) {
        if (!drafts.containsKey(player.getUniqueId())) {
            openWizardType(player);
            return;
        }
        descriptionPrompts.put(player.getUniqueId(),
            new DescriptionPrompt(System.currentTimeMillis() + DESCRIPTION_PROMPT_TIMEOUT_MS));
        player.closeInventory();
        player.sendMessage(plugin.lang().message("description-input-start"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DescriptionPrompt prompt = descriptionPrompts.get(player.getUniqueId());
            if (prompt != null && System.currentTimeMillis() >= prompt.expiresAt) {
                descriptionPrompts.remove(player.getUniqueId());
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null) {
                    current.sendMessage(plugin.lang().message("description-input-timeout"));
                    openWizardForm(current);
                }
            }
        }, DESCRIPTION_PROMPT_TIMEOUT_MS / 50L + 1L);
    }

    private void handleDescriptionInput(Player player, DescriptionPrompt prompt, String message) {
        UUID playerId = player.getUniqueId();
        if (System.currentTimeMillis() >= prompt.expiresAt) {
            player.sendMessage(plugin.lang().message("description-input-timeout"));
            openWizardForm(player);
            return;
        }
        if (message.equalsIgnoreCase("cancel")) {
            player.sendMessage(plugin.lang().message("description-input-cancelled"));
            openWizardForm(player);
            return;
        }
        CreateDraft draft = drafts.get(playerId);
        if (draft == null) {
            openWizardType(player);
            return;
        }
        if (message.equalsIgnoreCase("clear")) {
            draft.description(null);
            player.sendMessage(plugin.lang().message("description-input-cleared"));
            openWizardForm(player);
            return;
        }
        int maxLength = plugin.getConfig().getInt("limits.max-description-length", 500);
        String clean = Text.stripControl(message);
        if (clean.length() > maxLength) {
            player.sendMessage(plugin.lang().message("description-input-too-long",
                Map.of("max", String.valueOf(maxLength))));
            openWizardForm(player);
            return;
        }
        draft.description(clean.isBlank() ? null : clean);
        player.sendMessage(plugin.lang().message("description-input-saved"));
        openWizardForm(player);
    }

    // === Anvil text/number input ===

    private void openTextAnvil(Player player, String title, String initial, Consumer<String> onText) {
        anvilSuppressReopen.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.color("&#F4D03F" + title))
            .text(initial == null || initial.isBlank() ? "..." : initial)
            .itemLeft(new ItemStack(Material.PAPER))
            .onClick((slot, snapshot) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) {
                    return Collections.emptyList();
                }
                anvilSuppressReopen.remove(player.getUniqueId());
                String text = snapshot.getText() == null ? "" : snapshot.getText().trim();
                if (text.equals("...")) {
                    text = "";
                }
                String finalText = text;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    onText.accept(finalText);
                    openWizardForm(player);
                });
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .onClose(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (anvilSuppressReopen.remove(player.getUniqueId())) {
                    openWizardForm(player);
                }
            }))
            .open(player));
    }

    private void openNumberAnvil(Player player, String title, Double initial, Consumer<Double> onValue) {
        anvilSuppressReopen.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> new AnvilGUI.Builder()
            .plugin(plugin)
            .title(Text.color("&#F4D03F" + title))
            .text(initial == null ? "0" : trimNumber(initial))
            .itemLeft(new ItemStack(Material.GOLD_INGOT))
            .onClick((slot, snapshot) -> {
                if (slot != AnvilGUI.Slot.OUTPUT) {
                    return Collections.emptyList();
                }
                anvilSuppressReopen.remove(player.getUniqueId());
                String text = snapshot.getText() == null ? "" : snapshot.getText().trim();
                Double parsed = parsePositive(text);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (parsed == null) {
                        player.sendMessage(Text.color("&#E63946数字格式不正确,未保存。"));
                    } else {
                        onValue.accept(parsed);
                    }
                    openWizardForm(player);
                });
                return List.of(AnvilGUI.ResponseAction.close());
            })
            .onClose(snapshot -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (anvilSuppressReopen.remove(player.getUniqueId())) {
                    openWizardForm(player);
                }
            }))
            .open(player));
    }

    private static Double parsePositive(String text) {
        try {
            double value = Double.parseDouble(text);
            // Must be positive AND finite — Infinity passes "> 0" but later crashes BigDecimal.valueOf.
            return value > 0 && Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // === Dispute chat prompt (unchanged mechanism) ===

    private boolean canDispute(Contract contract) {
        ContractStatus status = contract.status();
        return status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED;
    }

    private void beginDisputePrompt(Player player, Contract contract, Session session) {
        disputePrompts.put(player.getUniqueId(), new DisputePrompt(
            contract.id(),
            session.origin,
            session.mode,
            session.page,
            session.filter,
            session.adminMode,
            System.currentTimeMillis() + DISPUTE_PROMPT_TIMEOUT_MS
        ));
        player.closeInventory();
        player.sendMessage(Text.color("&#FFE066请在 60 秒内输入争议原因,或输入 &#E63946cancel &#FFE066取消。"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            DisputePrompt prompt = disputePrompts.get(player.getUniqueId());
            if (prompt != null && prompt.contractId.equals(contract.id())
                && System.currentTimeMillis() >= prompt.expiresAt) {
                disputePrompts.remove(player.getUniqueId());
                Player current = Bukkit.getPlayer(player.getUniqueId());
                if (current != null) {
                    current.sendMessage(Text.color("&#E63946争议输入超时,已取消。"));
                }
            }
        }, DISPUTE_PROMPT_TIMEOUT_MS / 50L + 1L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        DescriptionPrompt descriptionPrompt = descriptionPrompts.get(playerId);
        if (descriptionPrompt != null) {
            event.setCancelled(true);
            String message = event.getMessage();
            descriptionPrompts.remove(playerId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    handleDescriptionInput(player, descriptionPrompt, message);
                }
            });
            return;
        }
        DisputePrompt prompt = disputePrompts.get(playerId);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        disputePrompts.remove(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return;
            }
            if (System.currentTimeMillis() >= prompt.expiresAt) {
                player.sendMessage(Text.color("&#E63946争议输入超时,已取消。"));
                return;
            }
            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(Text.color("&#FFE066已取消争议输入。"));
                return;
            }
            plugin.storage().findByPrefix(prompt.contractId).ifPresentOrElse(contract -> {
                ServiceResult result = plugin.contracts().dispute(player, contract, message);
                if (result.success()) {
                    player.sendMessage(plugin.lang().message("dispute-success"));
                    openDetails(player, contract, prompt.origin, prompt.mode, prompt.page,
                        prompt.filter, prompt.adminMode);
                } else {
                    player.sendMessage(plugin.lang().message("operation-failed", Map.of("reason", result.reason())));
                }
            }, () -> player.sendMessage(plugin.lang().message("not-found")));
        });
    }

    // === Navigation helpers ===

    private void backFromDetails(Player player, Session session) {
        switch (session.origin) {
            case HUB -> openHub(player);
            case INBOX -> openInbox(player, session.page);
            case ADMIN -> openAdmin(player, AdminFilter.DISPUTED, session.page);
            default -> openBoard(player, session.mode, session.page, session.filter);
        }
    }

    private void backFromConfirm(Player player, Session session) {
        Session origin = session.confirmOrigin;
        if (origin == null || origin.type == ViewType.WIZARD_FORM) {
            openWizardForm(player);
            return;
        }
        Optional<Contract> contract = origin.contractId == null
            ? Optional.empty()
            : plugin.storage().findByPrefix(origin.contractId);
        if (contract.isEmpty()) {
            openHub(player);
            return;
        }
        openDetails(player, contract.get(), origin.origin, origin.mode, origin.page,
            origin.filter, origin.adminMode);
    }

    private void runDirect(Player player, Session session, Contract contract, ServiceResult result) {
        if (result.success()) {
            player.sendMessage(Text.color("&#69DB7C操作成功。"));
            Contract done = result.contract() != null ? result.contract() : contract;
            openDetails(player, done, session.origin, session.mode, session.page,
                session.filter, session.adminMode);
        } else {
            player.sendMessage(plugin.lang().message("operation-failed", Map.of("reason", result.reason())));
        }
    }

    // === Data helpers ===

    private List<Contract> inboxContracts(Player player) {
        List<Contract> result = new ArrayList<>();
        for (Contract contract : plugin.contracts().allContracts()) {
            if (inboxLabel(player, contract) != null) {
                result.add(contract);
            }
        }
        return result;
    }

    /** Returns a short label describing what the player must do, or null if nothing. */
    private String inboxLabel(Player player, Contract contract) {
        UUID id = player.getUniqueId();
        ContractStatus status = contract.status();
        if (isArbiter(contract, id)) {
            if (!contract.arbiterAccepted()) {
                return "待你接受中间人/仲裁职责";
            }
            if (contract.type() == ContractType.WAGER
                && (status == ContractStatus.IN_PROGRESS || status == ContractStatus.SUBMITTED)) {
                return "待你裁决对赌";
            }
            if (contract.type() != ContractType.WAGER && status == ContractStatus.DISPUTED) {
                return "待你裁决争议";
            }
        }
        if (status == ContractStatus.PENDING_ACCEPT && id.equals(contract.contractorUuid())) {
            return "待你接受邀请";
        }
        if (contract.type() == ContractType.SERVICE) {
            if (status == ContractStatus.IN_PROGRESS && id.equals(contract.contractorUuid())) {
                return "待你提交完成";
            }
            if (status == ContractStatus.SUBMITTED && id.equals(contract.ownerUuid())) {
                return "待你确认付款";
            }
        }
        if (contract.type() == ContractType.PARTNERSHIP && status == ContractStatus.IN_PROGRESS
            && isParty(contract, id)) {
            String approved = contract.metadata.getOrDefault("approved-roles", "");
            Optional<Participant> me = contract.participantByUuid(id);
            if (me.isPresent() && !approved.contains(me.get().role().name())) {
                return "待你确认合作完成";
            }
        }
        if (status == ContractStatus.DISPUTED && contract.relatedTo(id)) {
            return "争议处理中";
        }
        return null;
    }

    private List<Contract> adminContracts(AdminFilter filter) {
        List<Contract> result = new ArrayList<>();
        for (Contract contract : plugin.contracts().allContracts()) {
            boolean keep = switch (filter) {
                case DISPUTED -> contract.status() == ContractStatus.DISPUTED;
                case ACTIVE -> !contract.status().isFinal();
                case ALL -> true;
            };
            if (keep) {
                result.add(contract);
            }
        }
        return result;
    }

    private String adminLabel(Contract contract) {
        if (contract.status() == ContractStatus.DISPUTED) {
            return "争议待处理";
        }
        return plugin.lang().status(contract.status());
    }

    private List<Contract> contractsFor(Player player, BoardMode mode, TypeFilter filter) {
        java.util.stream.Stream<Contract> stream;
        if (mode == BoardMode.MINE) {
            stream = plugin.contracts().allContracts().stream()
                .filter(contract -> contract.relatedTo(player.getUniqueId()));
        } else {
            stream = plugin.contracts().openContracts().stream();
        }
        return stream.filter(filter::matches).toList();
    }

    private List<String> acceptConsequences(Contract contract) {
        Participant partyB = contract.participant(ParticipantRole.PARTY_B).orElse(null);
        BigDecimal stake = partyB == null ? BigDecimal.ZERO : partyB.moneyStake();
        return List.of(
            "签署后立即从你的余额扣除押注 " + plugin.economy().format(stake) + " 托管到服务器。",
            "资金不会交给对方或中间人,由服务器托管。");
    }

    private List<String> draftPreview(CreateDraft draft) {
        List<String> lines = new ArrayList<>();
        lines.add("&#CFD8DC类型: &#FFFFFF" + plugin.lang().type(draft.type()));
        lines.add("&#CFD8DC标题: &#FFFFFF" + valueOr(draft.title()));
        lines.add("&#CFD8DC描述: &#FFFFFF" + ContractTerms.preview(draft.description()));
        if (draft.needsCounterparty()) {
            lines.add("&#CFD8DC对方: &#FFFFFF" + valueOr(draft.counterparty()));
        }
        lines.add("&#CFD8DC" + (draft.mediatorRequired() ? "仲裁者" : "中间人")
            + ": &#FFFFFF" + valueOr(draft.mediator()));
        lines.add("&#CFD8DC期限: &#FFFFFF" + (draft.hours() == null ? "未填" : draft.hours() + " 小时"));
        if (draft.type() == ContractType.SERVICE) {
            double fee = plugin.getConfig().getDouble("economy.creation-fee", 20.0);
            lines.add("&#CFD8DC奖金托管: &#69DB7C" + valueOr(num(draft.amount())));
            lines.add("&#CFD8DC创建费: &#FFE066" + trimNumber(fee) + " &#CFD8DC(普通取消不退)");
            if (draft.amount() != null) {
                lines.add("&#CFD8DC签署后共扣除: &#E63946" + trimNumber(draft.amount() + fee));
            }
        } else if (draft.type() == ContractType.WAGER) {
            lines.add("&#CFD8DC我的押注: &#69DB7C" + valueOr(num(draft.amount())));
            lines.add("&#CFD8DC对方需匹配等额押注。");
            lines.add("&#CFD8DC签署后立即扣除: &#E63946" + valueOr(num(draft.amount())));
        } else {
            lines.add("&#CFD8DC我的押注: &#69DB7C" + valueOr(num(draft.amount())));
            lines.add("&#CFD8DC对方押注: &#69DB7C" + valueOr(num(draft.partnerStake())));
            lines.add("&#CFD8DC签署后立即扣除我的押注: &#E63946" + valueOr(num(draft.amount())));
        }
        lines.add("&#CFD8DC资金由服务器托管,不交给对方或中间人。");
        return lines;
    }

    // === Item builders ===

    private ItemStack contractItem(Contract contract, String actionLabel) {
        List<String> lore = new ArrayList<>();
        lore.add(Text.color("&#CFD8DCID: &#FFE066#" + contract.shortId()));
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF" + plugin.lang().type(contract.type())));
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF" + plugin.lang().status(contract.status())));
        lore.add(Text.color("&#CFD8DC发起: &#FFFFFF" + contract.ownerName()));
        lore.add(Text.color("&#CFD8DC对方: &#FFFFFF" + (contract.contractorName() == null ? "无" : contract.contractorName())));
        lore.add(Text.color("&#CFD8DC金额: &#69DB7C" + plugin.economy().format(contract.reward())));
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF" + DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))));
        lore.add("");
        if (actionLabel != null) {
            lore.add(Text.color("&#E63946▶ " + actionLabel));
        }
        lore.add(Text.color("&#FFE066点击查看详情"));
        ItemStack item = new ItemStack(materialFor(contract.type(), contract.status()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F" + contract.title()));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack detailItem(Contract contract) {
        List<String> lore = new ArrayList<>();
        lore.add(Text.color("&#CFD8DC类型: &#FFFFFF" + plugin.lang().type(contract.type())));
        lore.add(Text.color("&#CFD8DC状态: &#FFFFFF" + plugin.lang().status(contract.status())));
        for (Participant participant : contract.participants()) {
            lore.add(Text.color("&#CFD8DC" + plugin.lang().role(participant.role()) + ": &#FFFFFF"
                + (participant.displayName() == null ? "无" : participant.displayName())
                + " &#69DB7C" + plugin.economy().format(participant.moneyStake())));
        }
        if (contract.arbiter() != null) {
            lore.add(Text.color("&#CFD8DC仲裁者: &#FFFFFF" + contract.arbiter().displayName()
                + " &#CFD8DC(" + (contract.arbiterAccepted() ? "已接受" : "待接受") + ")"));
        }
        lore.add(Text.color("&#CFD8DC佣金率: &#FFE066" + contract.commissionPercent().toPlainString() + "%"));
        lore.add(Text.color("&#CFD8DC截止: &#FFFFFF" + DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))));
        lore.add("");
        lore.add(Text.color("&#F1F5F9" + contract.description()));
        if (contract.disputeReason() != null && !contract.disputeReason().isBlank()) {
            lore.add("");
            lore.add(Text.color("&#E63946争议: &#F1F5F9" + contract.disputeReason()));
        }
        ItemStack item = new ItemStack(materialFor(contract.type(), contract.status()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color("&#F4D03F" + contract.title()));
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack fieldButton(Material material, String label, String value) {
        boolean filled = value != null && !value.isBlank();
        String name = (filled ? "&#69DB7C" : "&#FFE066") + label;
        return button(material, name,
            "&#CFD8DC当前: &#FFFFFF" + (filled ? value : "未填写"),
            "&#FFE066点击用铁砧输入");
    }

    private ItemStack descriptionButton(String value) {
        boolean filled = value != null && !value.isBlank();
        String name = (filled ? "&#69DB7C" : "&#FFE066") + "描述";
        return button(Material.BOOK, name,
            "&#CFD8DC当前: &#FFFFFF" + ContractTerms.preview(value),
            "&#FFE066点击后在聊天输入",
            "&#CFD8DC输入 cancel 取消, clear 清空");
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            List<String> coloredLore = new ArrayList<>();
            for (String line : lore) {
                coloredLore.add(Text.color(line));
            }
            meta.setLore(coloredLore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBorder(Inventory inventory) {
        ItemStack pane = button(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int index = 0; index < inventory.getSize(); index++) {
            int row = index / 9;
            int col = index % 9;
            if (row == 0 || row == inventory.getSize() / 9 - 1 || col == 0 || col == 8) {
                inventory.setItem(index, pane);
            }
        }
    }

    private ItemStack filterButton(TypeFilter filter, TypeFilter selected, Material material, String label) {
        String prefix = filter == selected ? "&#69DB7C" : "&#CFD8DC";
        return button(material, prefix + label, "&#CFD8DC点击筛选此类型");
    }

    private ItemStack adminFilterButton(AdminFilter filter, AdminFilter selected, Material material, String label) {
        String prefix = filter == selected ? "&#69DB7C" : "&#CFD8DC";
        return button(material, prefix + label, "&#CFD8DC点击切换分栏");
    }

    private Material materialFor(ContractType type, ContractStatus status) {
        return switch (status) {
            case COMPLETED -> Material.EMERALD;
            case CANCELLED -> Material.BARRIER;
            case EXPIRED -> Material.CLOCK;
            case DISPUTED -> Material.REDSTONE;
            case PENDING_ACCEPT -> Material.YELLOW_BANNER;
            default -> switch (type) {
                case SERVICE -> Material.PAPER;
                case WAGER -> Material.TARGET;
                case PARTNERSHIP -> Material.AMETHYST_CLUSTER;
                case ALLIANCE -> Material.SHIELD;
                case BOUNTY -> Material.CROSSBOW;
                case SALE -> Material.CHEST;
                case LOAN -> Material.GOLD_INGOT;
            };
        };
    }

    private boolean canAcceptInvitation(Player player, Contract contract) {
        return player.getUniqueId().equals(contract.contractorUuid());
    }

    private boolean canCancel(Player player, Contract contract) {
        return contract.participantByUuid(player.getUniqueId()).isPresent();
    }

    private boolean canMediate(Contract contract) {
        return contract.arbiterAccepted()
            && !contract.status().isFinal()
            && contract.status() != ContractStatus.OPEN
            && contract.status() != ContractStatus.PENDING_ACCEPT;
    }

    private boolean isParty(Contract contract, UUID uuid) {
        return contract.participantByUuid(uuid)
            .map(participant -> participant.role() == ParticipantRole.PARTY_A
                || participant.role() == ParticipantRole.PARTY_B)
            .orElse(false);
    }

    private boolean isArbiter(Contract contract, UUID uuid) {
        return contract.arbiter() != null && uuid.equals(contract.arbiter().uuid());
    }

    private boolean isManagedTitle(String title) {
        return title.equals(HUB_TITLE)
            || title.equals(INBOX_TITLE)
            || title.equals(BOARD_TITLE)
            || title.equals(MY_TITLE)
            || title.equals(WIZARD_TYPE_TITLE)
            || title.equals(WIZARD_FORM_TITLE)
            || title.equals(CONFIRM_TITLE)
            || title.equals(ADMIN_TITLE)
            || title.startsWith(DETAIL_TITLE_PREFIX);
    }

    private int pageCount(int size) {
        return Math.max(1, (int) Math.ceil((double) size / BOARD_SLOTS.length));
    }

    private int clampPage(int page, int pages) {
        return Math.min(Math.max(1, page), pages);
    }

    private double minAmount() {
        return plugin.getConfig().getDouble("economy.min-reward", 100.0);
    }

    private double maxAmount() {
        return plugin.getConfig().getDouble("economy.max-reward", 100000.0);
    }

    private int minHours() {
        return plugin.getConfig().getInt("limits.min-deadline-hours", 1);
    }

    private int maxHours() {
        return plugin.getConfig().getInt("limits.max-deadline-hours", 168);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String valueOr(String value) {
        return value == null || value.isBlank() ? "未填" : value;
    }

    private static String num(Double value) {
        return value == null ? null : trimNumber(value);
    }

    private static String trimNumber(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // === Enums and session records ===

    public enum BoardMode {
        OPEN,
        MINE
    }

    public enum TypeFilter {
        ALL(null),
        SERVICE(ContractType.SERVICE),
        WAGER(ContractType.WAGER),
        PARTNERSHIP(ContractType.PARTNERSHIP);

        private final ContractType type;

        TypeFilter(ContractType type) {
            this.type = type;
        }

        private boolean matches(Contract contract) {
            return type == null || contract.type() == type;
        }
    }

    public enum AdminFilter {
        DISPUTED,
        ACTIVE,
        ALL
    }

    private enum ViewType {
        HUB,
        INBOX,
        BOARD,
        ADMIN,
        DETAILS,
        WIZARD_TYPE,
        WIZARD_FORM,
        CONFIRM
    }

    private static final class DescriptionPrompt {
        private final long expiresAt;

        private DescriptionPrompt(long expiresAt) {
            this.expiresAt = expiresAt;
        }
    }

    private static final class DisputePrompt {
        private final String contractId;
        private final ViewType origin;
        private final BoardMode mode;
        private final int page;
        private final TypeFilter filter;
        private final boolean adminMode;
        private final long expiresAt;

        private DisputePrompt(String contractId, ViewType origin, BoardMode mode, int page,
                              TypeFilter filter, boolean adminMode, long expiresAt) {
            this.contractId = contractId;
            this.origin = origin;
            this.mode = mode;
            this.page = page;
            this.filter = filter;
            this.adminMode = adminMode;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PendingAction {
        private enum Kind {
            CREATE, ACCEPT, APPROVE, RESOLVE, MEDIATE, CANCEL, ADMIN_PAY, ADMIN_REFUND, ADMIN_CLOSE
        }

        private final Kind kind;
        private final String contractId;
        private final String arg;
        private final String title;
        private final List<String> consequences;

        private PendingAction(Kind kind, String contractId, String arg, String title,
                              List<String> consequences) {
            this.kind = kind;
            this.contractId = contractId;
            this.arg = arg;
            this.title = title;
            this.consequences = consequences;
        }

        private static PendingAction simple(Kind kind, Contract contract, String arg, String title,
                                            List<String> consequences) {
            return new PendingAction(kind, contract.id(), arg, title, consequences);
        }

        private static PendingAction create(CreateDraft draft, List<String> preview) {
            List<String> lines = new ArrayList<>();
            lines.add("即将创建一份" + draft.type().name() + "合同。");
            for (String line : preview) {
                lines.add(line);
            }
            return new PendingAction(Kind.CREATE, null, null, "创建合同", lines);
        }

        private Kind kind() {
            return kind;
        }

        private String contractId() {
            return contractId;
        }

        private String arg() {
            return arg;
        }

        private String title() {
            return title;
        }

        private List<String> consequences() {
            return consequences;
        }
    }

    private static final class Session {
        private final ViewType type;
        private final BoardMode mode;
        private final int page;
        private final TypeFilter filter;
        private final AdminFilter adminFilter;
        private final String contractId;
        private final ViewType origin;
        private final boolean adminMode;
        private final PendingAction pending;
        private final Session confirmOrigin;
        private final Map<Integer, String> slotContracts = new HashMap<>();

        private Session(ViewType type, BoardMode mode, int page, TypeFilter filter, AdminFilter adminFilter,
                        String contractId, ViewType origin, boolean adminMode, PendingAction pending,
                        Session confirmOrigin) {
            this.type = type;
            this.mode = mode;
            this.page = page;
            this.filter = filter;
            this.adminFilter = adminFilter;
            this.contractId = contractId;
            this.origin = origin;
            this.adminMode = adminMode;
            this.pending = pending;
            this.confirmOrigin = confirmOrigin;
        }

        private static Session hub() {
            return new Session(ViewType.HUB, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED,
                null, ViewType.HUB, false, null, null);
        }

        private static Session list(ViewType type, int page) {
            return new Session(type, BoardMode.MINE, page, TypeFilter.ALL, AdminFilter.DISPUTED,
                null, ViewType.HUB, false, null, null);
        }

        private static Session board(BoardMode mode, int page, TypeFilter filter) {
            return new Session(ViewType.BOARD, mode, page, filter, AdminFilter.DISPUTED,
                null, ViewType.HUB, false, null, null);
        }

        private static Session admin(AdminFilter filter, int page) {
            return new Session(ViewType.ADMIN, BoardMode.OPEN, page, TypeFilter.ALL, filter,
                null, ViewType.HUB, false, null, null);
        }

        private static Session details(ViewType origin, BoardMode mode, int page, TypeFilter filter,
                                        String contractId, boolean adminMode) {
            return new Session(ViewType.DETAILS, mode, page, filter, AdminFilter.DISPUTED,
                contractId, origin, adminMode, null, null);
        }

        private static Session wizardType() {
            return new Session(ViewType.WIZARD_TYPE, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED,
                null, ViewType.HUB, false, null, null);
        }

        private static Session wizardForm() {
            return new Session(ViewType.WIZARD_FORM, BoardMode.OPEN, 1, TypeFilter.ALL, AdminFilter.DISPUTED,
                null, ViewType.WIZARD_TYPE, false, null, null);
        }

        private static Session confirm(PendingAction action, Session origin) {
            return new Session(ViewType.CONFIRM, origin.mode, origin.page, origin.filter, origin.adminFilter,
                origin.contractId, origin.origin, origin.adminMode, action, origin);
        }
    }
}
