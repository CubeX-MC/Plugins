package org.cubexmc.ecobalancer.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.policies.TaxPolicy;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;

import java.util.*;

public class GuiManager implements Listener {
    private final EcoBalancer plugin;

    // Track which policy the player is viewing details for
    private final Map<UUID, String> viewingPolicyDetails = new HashMap<>();
    private final Map<UUID, Integer> viewingPolicyPage = new HashMap<>();
    private final Map<UUID, java.util.function.Consumer<String>> pendingChatInputs = new HashMap<>();

    public GuiManager(EcoBalancer plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // --- Menus ---

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 45, tr("messages.gui.main_menu_title", "&6EcoBalancer Menu"));

        // Header
        TaxPolicy active = plugin.getPolicyManager().getActivePolicy();
        String activePolicyName = active != null ? active.getName() : tr("messages.gui.none", "None");
        String scheduleInfo = active != null ? active.getScheduleType() + " @ " + active.getCheckTime() : tr("messages.gui.na", "N/A");

        inv.setItem(4, createItem(Material.BEACON, tr("messages.gui.item_header_name", "&b&lEcoBalancer"),
                tr("messages.gui.item_header_lore_1", "&7Economy management system"),
                "",
                tr("messages.gui.item_header_active", "&7Active Policy: &a") + activePolicyName,
                tr("messages.gui.item_header_schedule", "&7Schedule: &f") + scheduleInfo));

        // Dashboard
        inv.setItem(20, createItem(Material.PAPER, tr("messages.gui.item_dashboard_name", "&eEconomic Dashboard"),
                tr("messages.gui.item_dashboard_lore_1", "&7View real-time economic statistics"),
                "",
                tr("messages.gui.item_dashboard_lore_2", "&7• Gini Coefficient"),
                tr("messages.gui.item_dashboard_lore_3", "&7• Total Money Supply"),
                tr("messages.gui.item_dashboard_lore_4", "&7• Player Statistics"),
                "",
                tr("messages.gui.click_to_open", "&aClick to open")));

        // Tax Policies
        inv.setItem(22, createItem(Material.GOLD_INGOT, tr("messages.gui.item_policies_name", "&eTax Policies"),
                tr("messages.gui.item_policies_lore_1", "&7Manage and execute tax policies"),
                "",
                tr("messages.gui.item_policies_current", "&7Current: &a") + activePolicyName,
                "",
                tr("messages.gui.click_to_manage", "&aClick to manage"),
                tr("messages.gui.requires_admin", "&cRequires: ecobalancer.gui.admin")));

        // Quick Execute
        if (player.hasPermission("ecobalancer.gui.admin")) {
            inv.setItem(24, createItem(Material.REDSTONE, tr("messages.gui.item_execute_name", "&c&lExecute Now"),
                    tr("messages.gui.item_execute_lore_1", "&7Execute active policy immediately"),
                    "",
                    tr("messages.gui.item_execute_policy", "&7Policy: &e") + activePolicyName,
                    "",
                    tr("messages.gui.click_to_execute_all", "&c⚠ Click to execute on all players")));
        } else {
            inv.setItem(24, createItem(Material.BARRIER, tr("messages.gui.item_execute_no_name", "&8Execute Now"),
                    tr("messages.gui.requires_admin_perm", "&7Requires admin permission")));
        }

        // Bottom row - Info
        inv.setItem(38, createItem(Material.BOOK, tr("messages.gui.item_help_name", "&7Help"),
                tr("messages.gui.item_help_lore", "&fUse /eb help for commands")));

        inv.setItem(40, createItem(Material.COMPARATOR, tr("messages.gui.item_settings_name", "&7Settings"),
                tr("messages.gui.item_settings_lore_1", "&fEdit config.yml manually"),
                tr("messages.gui.item_settings_lore_2", "&fOr use /eb tax commands")));

        inv.setItem(42, createItem(Material.EXPERIENCE_BOTTLE, tr("messages.gui.item_records_name", "&7View Records"),
                tr("messages.gui.item_records_lore_1", "&fUse /eb checkrecords"),
                tr("messages.gui.item_records_lore_2", "&ffor operation history")));

        fillBackground(inv);
        player.openInventory(inv);
    }

    public void openDashboard(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, tr("messages.gui.dashboard_title", "&6Economic Dashboard"));

        List<Double> balances = plugin.collectAllBalances();
        if (balances == null)
            balances = new ArrayList<>();

        double totalMoney = balances.stream().mapToDouble(Double::doubleValue).sum();
        double gini = 0;
        double mean = 0;
        double median = 0;
        double top1 = 0;
        double top10 = 0;

        try {
            gini = EconomicMetrics.calculateGini(balances);
            mean = EconomicMetrics.calculateMean(balances);
            List<Double> sorted = EconomicMetrics.getSortedBalances(balances);
            median = EconomicMetrics.calculateMedian(sorted);
            top1 = EconomicMetrics.calculateConcentration(balances, 1.0);
            top10 = EconomicMetrics.calculateConcentration(balances, 10.0);
        } catch (Exception e) {
            // Ignore calculation errors
        }

        // Header
        inv.setItem(4, createItem(Material.BEACON, tr("messages.gui.dash_header_name", "&b&lEconomic Dashboard"),
                tr("messages.gui.dash_header_lore", "&7Real-time economy status"),
                "",
                tr("messages.gui.dash_header_players", "&7Total Players: &f") + balances.size()));

        // Row 2 - Main Stats
        inv.setItem(19, createItem(Material.GOLD_BLOCK, tr("messages.gui.dash_supply_name", "&e&lTotal Supply"),
                "§f" + EconomicMetrics.formatLargeNumber(totalMoney),
                "",
                tr("messages.gui.dash_supply_lore", "&7Combined balance of all players")));

        String giniColor = gini < 0.3 ? "§a" : gini < 0.5 ? "§e" : "§c";
        inv.setItem(21, createItem(Material.DIAMOND, tr("messages.gui.dash_gini_name", "&b&lGini Coefficient"),
                giniColor + String.format("%.4f", gini),
                "",
                tr("messages.gui.dash_gini_lore1", "&70.0 = Perfect Equality"),
                tr("messages.gui.dash_gini_lore2", "&71.0 = Maximum Inequality"),
                "",
                getGiniDescription(gini)));

        inv.setItem(23, createItem(Material.EMERALD, tr("messages.gui.dash_mean_name", "&a&lMean Balance"),
                "§f" + EconomicMetrics.formatLargeNumber(mean),
                "",
                tr("messages.gui.dash_mean_lore", "&7Average player balance")));

        inv.setItem(25, createItem(Material.HEART_OF_THE_SEA, tr("messages.gui.dash_median_name", "&d&lMedian Balance"),
                "§f" + EconomicMetrics.formatLargeNumber(median),
                "",
                tr("messages.gui.dash_median_lore", "&7Middle player balance")));

        // Row 3 - Concentration
        String top1Color = top1 < 20 ? "§a" : top1 < 40 ? "§e" : "§c";
        String top10Color = top10 < 50 ? "§a" : top10 < 70 ? "§e" : "§c";

        inv.setItem(29, createItem(Material.GOLDEN_APPLE, tr("messages.gui.dash_top1_name", "&6&lTop 1% Wealth"),
                top1Color + String.format("%.1f%%", top1),
                "",
                tr("messages.gui.dash_top1_lore", "&7Wealth held by richest 1%")));

        inv.setItem(31, createItem(Material.APPLE, tr("messages.gui.dash_top10_name", "&c&lTop 10% Wealth"),
                top10Color + String.format("%.1f%%", top10),
                "",
                tr("messages.gui.dash_top10_lore", "&7Wealth held by richest 10%")));

        inv.setItem(33, createItem(Material.CLOCK, tr("messages.gui.dash_active_name", "&e&lActive Players"),
                tr("messages.gui.dash_active_lore1", "&7Use /eb health for details"),
                "",
                tr("messages.gui.dash_active_lore2", "&7Tracks 7-day and 30-day"),
                tr("messages.gui.dash_active_lore3", "&7player activity")));

        // Row 4 - Actions
        if (player.hasPermission("ecobalancer.gui.admin")) {
            inv.setItem(47, createItem(Material.WRITABLE_BOOK, tr("messages.gui.dash_health_name", "&eView Health Report"),
                    tr("messages.gui.dash_health_lore1", "&7Detailed economy analysis"),
                    "",
                    tr("messages.gui.dash_health_lore2", "&aRun: /eb health")));

            inv.setItem(49, createItem(Material.MAP, tr("messages.gui.dash_trends_name", "&eView Trends"),
                    tr("messages.gui.dash_trends_lore1", "&7Historical data over time"),
                    "",
                    tr("messages.gui.dash_trends_lore2", "&aRun: /eb trends")));

            inv.setItem(51, createItem(Material.CHEST, tr("messages.gui.dash_records_name", "&eView Records"),
                    tr("messages.gui.dash_records_lore1", "&7Tax operation history"),
                    "",
                    tr("messages.gui.dash_records_lore2", "&aRun: /eb checkrecords")));
        }

        // Navigation
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_main", "&7Return to Main Menu")));

        fillBackground(inv);
        player.openInventory(inv);
    }

    private String getGiniDescription(double gini) {
        if (gini < 0.2)
            return tr("messages.gui.gini_excellent", "&aExcellent - Very equal distribution");
        if (gini < 0.3)
            return tr("messages.gui.gini_good", "&aGood - Relatively equal");
        if (gini < 0.4)
            return tr("messages.gui.gini_moderate", "&eModerate - Some inequality");
        if (gini < 0.5)
            return tr("messages.gui.gini_warning", "&eWarning - High inequality");
        return tr("messages.gui.gini_critical", "&cCritical - Severe inequality");
    }

    public void openTaxPolicies(Player player) {
        openTaxPolicies(player, 1);
    }

    public void openTaxPolicies(Player player, int page) {
        if (!player.hasPermission("ecobalancer.gui.admin")) {
            player.sendMessage(plugin.getFormattedMessage("messages.gui.no_permission_tax_policies", null));
            return;
        }

        viewingPolicyPage.put(player.getUniqueId(), page);
        List<String> policyNames = plugin.getPolicyManager().getPolicyNames();
        
        int itemsPerPage = 36;
        int totalPages = Math.max(1, (int) Math.ceil((double) policyNames.size() / itemsPerPage));
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        int size = 54;
        Inventory inv = Bukkit.createInventory(null, size, tr("messages.gui.tax_policies_title", "&6Tax Policies") + " - Page " + page);

        TaxPolicy active = plugin.getPolicyManager().getActivePolicy();
        String activeName = active != null ? active.getName() : "";

        // Header row
        inv.setItem(4, createItem(Material.GOLD_INGOT, tr("messages.gui.tp_header_name", "&e&lTax Policy Manager"),
                tr("messages.gui.tp_header_lore1", "&7Manage your tax policies"),
                "",
                tr("messages.gui.tp_header_active", "&7Active: &a") + (activeName.isEmpty() ? tr("messages.gui.none", "None") : activeName),
                "",
                tr("messages.gui.tp_header_hint1", "&e&lLeft-click&7 - View details / Activate"),
                tr("messages.gui.tp_header_hint2", "&c&lShift+Left-click&7 - Execute immediately")));

        int startIndex = (page - 1) * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, policyNames.size());
        
        int slot = 9;
        for (int i = startIndex; i < endIndex; i++) {
            String name = policyNames.get(i);
            boolean isActive = name.equals(activeName);
            Material mat = isActive ? Material.ENCHANTED_BOOK : Material.BOOK;
            String displayName = (isActive ? tr("messages.gui.tp_item_active_prefix", "&a✓ ") : "&e") + name;

            TaxPolicy p = plugin.getPolicyManager().getPolicy(name);
            String desc = p != null ? p.getDescription() : "";
            String schedule = p != null ? p.getScheduleType() : "?";
            String time = p != null ? p.getCheckTime() : "?";
            boolean isRoutine = p != null && p.isRoutine();

            List<String> lore = new ArrayList<>();
            lore.add("§7" + desc);
            lore.add("");
            lore.add(tr("messages.gui.tp_item_schedule", "&7Schedule: &f") + capitalize(schedule) + " @ " + time);
            lore.add(tr("messages.gui.tp_item_routine", "&7Routine: ") + (isRoutine ? tr("messages.gui.yes_auto", "&aYes (Auto)") : tr("messages.gui.no_manual", "&cNo (Manual)")));
            lore.add("");
            if (isActive) {
                lore.add(tr("messages.gui.tp_item_active_lore", "&a&lCURRENT ACTIVE POLICY"));
            } else {
                lore.add(tr("messages.gui.tp_item_inactive_lore", "&eLeft-click to activate/edit"));
            }
            lore.add(tr("messages.gui.tp_item_execute_lore", "&cShift+click to execute now"));
            lore.add("§8RAW_POLICY:" + name);

            inv.setItem(slot++, createItem(mat, displayName, lore.toArray(new String[0])));
        }

        // Controls at bottom
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_main", "&7Return to Main Menu")));

        if (page > 1) {
            inv.setItem(48, createItem(Material.ARROW, tr("messages.gui.btn_prev_page", "&aPrevious Page")));
        }
        if (page < totalPages) {
            inv.setItem(50, createItem(Material.ARROW, tr("messages.gui.btn_next_page", "&aNext Page")));
        }

        if (active != null) {
            inv.setItem(49, createItem(Material.REDSTONE, tr("messages.gui.tp_btn_execute_active", "&c&lExecute Active Policy"),
                    tr("messages.gui.tp_btn_execute_lore", "&7Run &e") + activeName + tr("messages.gui.tp_btn_execute_now", "&7 now"),
                    "",
                    tr("messages.gui.click_to_execute_all", "&c⚠ Affects all players!")));
        }

        inv.setItem(51, createItem(Material.EMERALD, tr("messages.gui.tp_btn_add", "&a&lAdd New Policy"),
                tr("messages.gui.tp_btn_add_lore1", "&7Create a new blank tax policy"),
                "",
                tr("messages.gui.tp_btn_add_click", "&aClick to create via Chat")));

        inv.setItem(53, createItem(Material.CHEST, tr("messages.gui.tp_btn_reload", "&eReload Policies"),
                tr("messages.gui.tp_btn_reload_lore", "&7Reload policies from disk")));

        fillBackground(inv);
        player.openInventory(inv);
    }

    public void openPolicyDetails(Player player, String policyName) {
        TaxPolicy policy = plugin.getPolicyManager().getPolicy(policyName);
        if (policy == null) {
            player.sendMessage(plugin.getFormattedMessage("messages.policy_not_found",
                    java.util.Collections.singletonMap("name", policyName)));
            return;
        }

        viewingPolicyDetails.put(player.getUniqueId(), policyName);

        Inventory inv = Bukkit.createInventory(null, 54, tr("messages.gui.policy_title_prefix", "&6Policy: ") + policyName);

        TaxPolicy active = plugin.getPolicyManager().getActivePolicy();
        boolean isActive = active != null && active.getName().equals(policyName);

        // Header
        inv.setItem(4, createItem(isActive ? Material.ENCHANTED_BOOK : Material.BOOK,
                (isActive ? "§a§l" : "§e§l") + policyName,
                "§7" + policy.getDescription(),
                "",
                isActive ? tr("messages.gui.pd_active", "&a✓ Currently Active") : tr("messages.gui.pd_inactive", "&7Inactive")));

        // Schedule edit
        inv.setItem(18, createItem(Material.COMPASS, tr("messages.gui.pd_toggle_schedule_type", "&eToggle Schedule Type"),
                tr("messages.gui.pd_schedule_type", "&7Type: &f") + capitalize(policy.getScheduleType()),
                "",
                tr("messages.gui.pd_desc_schedule_type", "&7Select daily, weekly, or monthly execution."),
                "",
                tr("messages.gui.click_toggle", "&aClick to toggle")));

        inv.setItem(19, createItem(Material.CLOCK, tr("messages.gui.pd_edit_time", "&eEdit Time"),
                tr("messages.gui.pd_time", "&7Time: &f") + policy.getCheckTime(),
                "",
                tr("messages.gui.pd_desc_time", "&7The exact time of day to trigger the policy."),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        if ("weekly".equalsIgnoreCase(policy.getScheduleType())) {
            inv.setItem(20, createItem(Material.DAYLIGHT_DETECTOR, tr("messages.gui.pd_edit_days", "&eEdit Days of Week"),
                    tr("messages.gui.pd_days_list", "&7Days: &f") + formatDaysOfWeek(policy.getScheduleDaysOfWeek()),
                    "",
                    tr("messages.gui.pd_desc_days", "&7Which days of the week to run the policy."),
                    "",
                    tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));
        } else if ("monthly".equalsIgnoreCase(policy.getScheduleType())) {
            inv.setItem(20, createItem(Material.DAYLIGHT_DETECTOR, tr("messages.gui.pd_edit_dates", "&eEdit Dates of Month"),
                    tr("messages.gui.pd_dates_list", "&7Dates: &f") + policy.getScheduleDatesOfMonth().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")),
                    "",
                    tr("messages.gui.pd_desc_dates", "&7Which dates of the month to run the policy."),
                    "",
                    tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));
        }

        inv.setItem(21, createItem(Material.NAME_TAG, tr("messages.gui.pd_toggle_routine", "&eToggle Routine"),
                tr("messages.gui.pd_routine", "&7Routine: ") + (policy.isRoutine() ? tr("messages.gui.yes", "&aYes") : tr("messages.gui.no", "&cNo")),
                "",
                tr("messages.gui.pd_desc_routine_1", "&7Auto: Runs automatically at the scheduled time."),
                tr("messages.gui.pd_desc_routine_2", "&7Manual: Only runs when executed by an admin."),
                "",
                tr("messages.gui.click_toggle", "&aClick to toggle")));

        // Settings edits
        inv.setItem(22, createItem(Material.GOLD_INGOT, tr("messages.gui.pd_max_deduction", "&eMax Deduction"),
                tr("messages.gui.pd_max_deduction_val", "&7Max Deduction: &f") + EconomicMetrics.formatLargeNumber(policy.getMaxDeductionPerPlayer()),
                "",
                tr("messages.gui.pd_desc_max_deduction_1", "&7The maximum amount of money to deduct"),
                tr("messages.gui.pd_desc_max_deduction_2", "&7from a player in a single execution."),
                tr("messages.gui.pd_desc_max_deduction_3", "&7Set to 0 to disable the limit."),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        inv.setItem(23, createItem(Material.SHIELD, tr("messages.gui.pd_min_protection", "&eMin Balance Protection"),
                tr("messages.gui.pd_min_protection_val", "&7Min Protection: &f") + EconomicMetrics.formatLargeNumber(policy.getMinBalanceProtection()),
                "",
                tr("messages.gui.pd_desc_min_protection_1", "&7Players with balance below this value"),
                tr("messages.gui.pd_desc_min_protection_2", "&7will not be taxed (poor protection)."),
                tr("messages.gui.pd_desc_min_protection_3", "&7Set to 0 to disable."),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        inv.setItem(24, createItem(Material.ENDER_PEARL, tr("messages.gui.pd_toggle_offline", "&eToggle Offline Only"),
                tr("messages.gui.pd_offline", "&7Offline Only: ") + (policy.isOnlyOfflinePlayers() ? tr("messages.gui.yes", "&aYes") : tr("messages.gui.no", "&cNo")),
                "",
                tr("messages.gui.pd_desc_offline_1", "&7Yes: Only taxes offline/AFK players."),
                tr("messages.gui.pd_desc_offline_2", "&7No: Taxes everyone including online players."),
                "",
                tr("messages.gui.click_toggle", "&aClick to toggle")));

        inv.setItem(25, createItem(Material.EXPERIENCE_BOTTLE, tr("messages.gui.pd_toggle_percentile", "&eToggle Percentile Mode"),
                tr("messages.gui.pd_percentile", "&7Percentile Mode: ") + (policy.isPercentileThresholds() ? tr("messages.gui.yes", "&aYes") : tr("messages.gui.no", "&cNo")),
                "",
                tr("messages.gui.pd_desc_percentile_1", "&7Yes: Thresholds use wealth percentile (e.g. top 10%)."),
                tr("messages.gui.pd_desc_percentile_2", "&7No: Thresholds use absolute balance (e.g. 100k)."),
                "",
                tr("messages.gui.click_toggle", "&aClick to toggle")));

        // Inactive rules
        inv.setItem(29, createItem(Material.ROTTEN_FLESH, tr("messages.gui.pd_inactive_deduct", "&eInactive Days To Deduct"),
                tr("messages.gui.pd_days", "&7Days: &f") + (policy.getInactiveDaysToDeduct() > 0 ? policy.getInactiveDaysToDeduct() : tr("messages.gui.disabled", "Disabled")),
                "",
                tr("messages.gui.pd_desc_inactive_deduct_1", "&7Start deducting tax only if the player"),
                tr("messages.gui.pd_desc_inactive_deduct_2", "&7has been offline for this many days."),
                tr("messages.gui.pd_desc_inactive_deduct_3", "&7Set to 0 to disable."),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        inv.setItem(30, createItem(Material.BONE, tr("messages.gui.pd_inactive_clear", "&eInactive Days To Clear"),
                tr("messages.gui.pd_days", "&7Days: &f") + (policy.getInactiveDaysToClear() > 0 ? policy.getInactiveDaysToClear() : tr("messages.gui.disabled", "Disabled")),
                "",
                tr("messages.gui.pd_desc_inactive_clear_1", "&7Clear the player's entire balance if they"),
                tr("messages.gui.pd_desc_inactive_clear_2", "&7have been offline for this many days."),
                tr("messages.gui.pd_desc_inactive_clear_3", "&7Set to 0 to disable."),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        // Tax brackets
        inv.setItem(32, createItem(Material.GOLD_NUGGET, tr("messages.gui.pd_manage_brackets", "&6&lManage Tax Brackets"),
                "§7" + policy.getTaxBrackets().size() + tr("messages.gui.pd_brackets_count", " bracket(s) configured"),
                "",
                tr("messages.gui.pd_click_brackets", "&aClick to open Brackets Editor")));

        // Actions at bottom
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_list", "&7Return to Policy List")));

        inv.setItem(47, createItem(Material.PAPER, tr("messages.gui.pd_test_calc", "&bTest Calculation"),
                tr("messages.gui.pd_test_calc_lore1", "&7Simulate a tax deduction"),
                "",
                tr("messages.gui.pd_test_calc_click", "&aClick to test via Chat")));

        if (!isActive) {
            inv.setItem(49, createItem(Material.LIME_DYE, tr("messages.gui.pd_activate", "&aActivate This Policy"),
                    tr("messages.gui.pd_activate_lore", "&7Set as the active policy"),
                    "",
                    tr("messages.gui.click_activate", "&aClick to activate")));
        }

        inv.setItem(50, createItem(Material.WRITABLE_BOOK, tr("messages.gui.pd_rename", "&eRename Policy"),
                tr("messages.gui.pd_rename_lore", "&7Change the name of this policy"),
                "",
                tr("messages.gui.click_edit_chat", "&aClick to edit in chat")));

        inv.setItem(51, createItem(Material.BARRIER, tr("messages.gui.pd_delete", "&cDelete Policy"),
                tr("messages.gui.pd_delete_lore", "&7Remove this policy entirely"),
                "",
                tr("messages.gui.click_delete", "&cShift+Left-click to delete")));

        inv.setItem(53, createItem(Material.REDSTONE, tr("messages.gui.btn_execute", "&c&lExecute Now"),
                tr("messages.gui.pd_execute_lore", "&7Run this policy immediately"),
                "",
                tr("messages.gui.click_to_execute_all", "&c⚠ Affects all players!")));

        fillBackground(inv);
        player.openInventory(inv);
    }

    public void openBracketsEditor(Player player, String policyName) {
        TaxPolicy policy = plugin.getPolicyManager().getPolicy(policyName);
        if (policy == null) return;
        viewingPolicyDetails.put(player.getUniqueId(), policyName);
        
        Inventory inv = Bukkit.createInventory(null, 54, tr("messages.gui.policy_title_prefix", "&6Policy: ") + policyName + tr("messages.gui.be_suffix", " (Brackets)"));
        
        inv.setItem(4, createItem(Material.GOLD_BLOCK, tr("messages.gui.be_header", "&6&lTax Brackets Editor"), tr("messages.gui.be_managing", "&7Managing brackets for: &e") + policyName));
        
        List<Map<String, Object>> brackets = policy.getTaxBrackets();
        brackets.sort(Comparator.comparingDouble(m -> ((Number) m.get("threshold")).doubleValue()));
        
        int slot = 9;
        for (int i = 0; i < brackets.size() && slot < 45; i++) {
            Map<String, Object> b = brackets.get(i);
            double threshold = ((Number) b.get("threshold")).doubleValue();
            double rate = ((Number) b.get("rate")).doubleValue();

            inv.setItem(slot++, createItem(Material.GOLD_NUGGET,
                    tr("messages.gui.be_bracket_name", "&eBracket: ") + EconomicMetrics.formatLargeNumber(threshold),
                    tr("messages.gui.be_bracket_th", "&7Threshold: &f") + EconomicMetrics.formatLargeNumber(threshold),
                    tr("messages.gui.be_bracket_rate", "&7Rate: &e") + String.format("%.2f%%", rate * 100),
                    "",
                    tr("messages.gui.be_bracket_delete", "&cShift+Left-click to Delete"),
                    "§8RAW_TH:" + threshold));
        }
        
        inv.setItem(49, createItem(Material.EMERALD, tr("messages.gui.be_add_bracket", "&a&l➕ Add New Bracket"), tr("messages.gui.be_add_click", "&7Click to add via Chat")));
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_settings", "&7Return to Policy Settings")));
        
        fillBackground(inv);
        player.openInventory(inv);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private String formatDaysOfWeek(List<Integer> days) {
        if (days == null || days.isEmpty()) return "";
        String[] dayNames = { "", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
        return days.stream()
                .filter(d -> d >= 1 && d <= 7)
                .map(d -> dayNames[d])
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // --- Helpers ---

    private ItemStack createItem(Material mat, int data, String name, String... lore) {
        ItemStack item = new ItemStack(mat, 1, (short) data);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Arrays.asList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        return createItem(mat, 0, name, lore);
    }

    private void fillBackground(Inventory inv) {
        ItemStack pane = createItem(Material.BLACK_STAINED_GLASS_PANE, 0, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane);
            }
        }
    }

    @EventHandler
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (pendingChatInputs.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            String message = event.getMessage();
            java.util.function.Consumer<String> consumer = pendingChatInputs.remove(player.getUniqueId());
            
            if (message.trim().equalsIgnoreCase(tr("messages.gui.chat_cancel_keyword", "cancel"))) {
                player.sendMessage(tr("messages.gui.chat_cancelled", "&e[EcoBalancer] &cInput cancelled."));
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String policyName = viewingPolicyDetails.get(player.getUniqueId());
                    if (policyName != null) {
                        openPolicyDetails(player, policyName);
                    } else {
                        openMainMenu(player);
                    }
                });
                return;
            }
            
            Bukkit.getScheduler().runTask(plugin, () -> consumer.accept(message.trim()));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player player = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();

        String mainMenuTitle = tr("messages.gui.main_menu_title", "&6EcoBalancer Menu");
        String dashboardTitle = tr("messages.gui.dashboard_title", "&6Economic Dashboard");
        String taxPoliciesTitle = tr("messages.gui.tax_policies_title", "&6Tax Policies");
        String policyPrefix = tr("messages.gui.policy_title_prefix", "&6Policy: ");

        if (!title.equals(mainMenuTitle) && !title.equals(dashboardTitle) && !title.startsWith(taxPoliciesTitle) && !title.startsWith(policyPrefix)) {
            return;
        }

        e.setCancelled(true);
        ItemStack current = e.getCurrentItem();
        if (current == null || current.getType() == Material.AIR)
            return;

        if (current.getType() == Material.BLACK_STAINED_GLASS_PANE)
            return;

        // Main Menu
        if (title.equals(mainMenuTitle)) {
            handleMainMenuClick(player, current);
        }
        // Dashboard
        else if (title.equals(dashboardTitle)) {
            if (current.getType() == Material.ARROW)
                openMainMenu(player);
            else {
                switch (current.getType()) {
                    case WRITABLE_BOOK:
                        player.closeInventory();
                        player.performCommand("eb health");
                        break;
                    case MAP:
                        player.closeInventory();
                        player.performCommand("eb trends");
                        break;
                    case CHEST:
                        player.closeInventory();
                        player.performCommand("eb checkrecords");
                        break;
                    default:
                        break;
                }
            }
        }
        // Tax Policies List
        else if (title.startsWith(taxPoliciesTitle)) {
            handleTaxPoliciesClick(player, current, e.getClick());
        }
        // Policy Details
        else if (title.startsWith(policyPrefix)) {
            handlePolicyDetailsClick(player, current, title, e.getClick());
        }
    }

    private void handleMainMenuClick(Player player, ItemStack current) {
        switch (current.getType()) {
            case PAPER:
                openDashboard(player);
                break;
            case GOLD_INGOT:
                openTaxPolicies(player);
                break;
            case REDSTONE:
                if (player.hasPermission("ecobalancer.gui.admin")) {
                    TaxPolicy active = plugin.getPolicyManager().getActivePolicy();
                    if (active != null) {
                        player.closeInventory();
                        player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_active_policy",
                                java.util.Collections.singletonMap("name", active.getName())));
                        plugin.checkAll(player);
                    } else {
                        player.sendMessage(plugin.getFormattedMessage("messages.gui.no_active_policy", null));
                    }
                }
                break;
            case BOOK:
                player.closeInventory();
                player.performCommand("eb help");
                break;
            case COMPARATOR:
                player.closeInventory();
                player.performCommand("eb reload");
                break;
            case EXPERIENCE_BOTTLE:
                player.closeInventory();
                player.performCommand("eb checkrecords");
                break;
            default:
                break;
        }
    }

    private void handleTaxPoliciesClick(Player player, ItemStack current, ClickType click) {
        switch (current.getType()) {
            case ARROW:
                if (current.hasItemMeta() && current.getItemMeta().hasDisplayName()) {
                    String name = current.getItemMeta().getDisplayName();
                    if (name.contains("Previous Page")) {
                        int page = viewingPolicyPage.getOrDefault(player.getUniqueId(), 1);
                        openTaxPolicies(player, page - 1);
                        return;
                    } else if (name.contains("Next Page")) {
                        int page = viewingPolicyPage.getOrDefault(player.getUniqueId(), 1);
                        openTaxPolicies(player, page + 1);
                        return;
                    }
                }
                openMainMenu(player);
                break;
            case CHEST:
                plugin.getPolicyManager().loadPolicies();
                player.sendMessage(plugin.getFormattedMessage("messages.gui.policies_reloaded", null));
                openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.getUniqueId(), 1));
                break;
            case EMERALD:
                promptForInput(player, tr("messages.gui.prompt_new_policy", "Enter name for the new policy (no spaces):"), input -> {
                    String newName = input.replace(" ", "_");
                    if (plugin.getPolicyManager().createPolicy(newName)) {
                        player.sendMessage(tr("messages.gui.msg_policy_created", "&aPolicy ") + newName + tr("messages.gui.msg_created", " created."));
                        openPolicyDetails(player, newName);
                    } else {
                        player.sendMessage(tr("messages.gui.msg_policy_exists", "&cPolicy already exists!"));
                        openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.getUniqueId(), 1));
                    }
                });
                break;
            case REDSTONE:
                // Execute active policy
                TaxPolicy active = plugin.getPolicyManager().getActivePolicy();
                if (active != null) {
                    player.closeInventory();
                    player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy",
                            java.util.Collections.singletonMap("name", active.getName())));
                    plugin.checkAll(player);
                }
                break;
            case BOOK:
            case ENCHANTED_BOOK:
                if (current.hasItemMeta() && current.getItemMeta().hasLore()) {
                    String rawName = null;
                    for (String line : current.getItemMeta().getLore()) {
                        if (line.startsWith("§8RAW_POLICY:")) {
                            rawName = line.substring(13); // length of "§8RAW_POLICY:"
                            break;
                        }
                    }

                    if (rawName != null && plugin.getPolicyManager().getPolicyNames().contains(rawName)) {
                        if (click.isShiftClick()) {
                            // Shift+click = Execute immediately
                            player.closeInventory();
                            player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy",
                                    java.util.Collections.singletonMap("name", rawName)));
                            plugin.executePolicy(player, rawName);
                        } else {
                            // Normal click = Open details
                            openPolicyDetails(player, rawName);
                        }
                    }
                }
                break;
            default:
                break;
        }
    }

    private void promptForInput(Player player, String prompt, java.util.function.Consumer<String> callback) {
        player.closeInventory();
        player.sendMessage(tr("messages.gui.chat_prefix", "&e[EcoBalancer] &f") + prompt);
        player.sendMessage(tr("messages.gui.chat_cancel_hint", "&7Type 'cancel' to abort."));
        pendingChatInputs.put(player.getUniqueId(), callback);
    }

    private void handlePolicyDetailsClick(Player player, ItemStack current, String title, ClickType click) {
        String policyName = viewingPolicyDetails.get(player.getUniqueId());
        if (policyName == null) {
            policyName = title.replace(tr("messages.gui.policy_title_prefix", "&6Policy: "), "").replace(" (Brackets)", "");
        }
        
        TaxPolicy policy = plugin.getPolicyManager().getPolicy(policyName);
        if (policy == null) return;

        boolean isBracketsView = title.contains("(Brackets)");

        if (isBracketsView) {
            switch (current.getType()) {
                case ARROW:
                    openPolicyDetails(player, policyName);
                    break;
                case EMERALD:
                    promptForInput(player, tr("messages.gui.prompt_add_bracket", "Enter threshold and rate separated by space (e.g. 1000 0.05):"), input -> {
                        try {
                            String[] parts = input.split(" ");
                            double threshold = Double.parseDouble(parts[0]);
                            double rate = Double.parseDouble(parts[1]);
                            Map<String, Object> bracket = new HashMap<>();
                            bracket.put("threshold", threshold);
                            bracket.put("rate", rate);
                            policy.getTaxBrackets().add(bracket);
                            plugin.getPolicyManager().savePolicy(policy.getName());
                            player.sendMessage(tr("messages.gui.msg_bracket_added", "&aBracket added."));
                        } catch (Exception ex) {
                            player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."));
                        }
                        openBracketsEditor(player, policy.getName());
                    });
                    break;
                case GOLD_NUGGET:
                    if (current.hasItemMeta() && current.getItemMeta().hasDisplayName() && current.getItemMeta().getDisplayName().startsWith(tr("messages.gui.be_bracket_prefix", "&eBracket: "))) {
                        if (click.isShiftClick()) {
                            List<String> lore = current.getItemMeta().getLore();
                            if (lore != null) {
                                for (String l : lore) {
                                    if (l.startsWith("§8RAW_TH:")) {
                                        try {
                                            double th = Double.parseDouble(l.replace("§8RAW_TH:", ""));
                                            policy.getTaxBrackets().removeIf(m -> Math.abs(((Number)m.get("threshold")).doubleValue() - th) < 1e-6);
                                            plugin.getPolicyManager().savePolicy(policy.getName());
                                            player.sendMessage(tr("messages.gui.msg_bracket_removed", "&aBracket removed."));
                                        } catch (Exception ex) {}
                                        break;
                                    }
                                }
                            }
                            openBracketsEditor(player, policy.getName());
                        }
                    }
                    break;
            }
            return;
        }

        switch (current.getType()) {
            case ARROW:
                int p = viewingPolicyPage.getOrDefault(player.getUniqueId(), 1);
                openTaxPolicies(player, p);
                break;
            case PAPER:
                promptForInput(player, tr("messages.gui.prompt_test_balance", "Enter an imaginary balance to test the deduction:"), input -> {
                    try {
                        double balance = Double.parseDouble(input);
                        double deduction = policy.calculateTax(balance, plugin.getPolicyManager()::getPolicy);
                        player.sendMessage(tr("messages.gui.test_balance", "&b[Test] &7Balance: &f") + EconomicMetrics.formatLargeNumber(balance));
                        player.sendMessage(tr("messages.gui.test_deduction", "&b[Test] &7Expected Deduction: &c-") + EconomicMetrics.formatLargeNumber(deduction));
                        player.sendMessage(tr("messages.gui.test_new_balance", "&b[Test] &7New Balance: &a") + EconomicMetrics.formatLargeNumber(balance - deduction));
                    } catch (NumberFormatException ex) {
                        player.sendMessage(tr("messages.gui.msg_invalid_number", "&cInvalid number format."));
                    }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case BARRIER:
                if (click.isShiftClick()) {
                    if (plugin.getPolicyManager().getActivePolicy() != null && plugin.getPolicyManager().getActivePolicy().getName().equals(policy.getName())) {
                        player.sendMessage(tr("messages.gui.msg_cant_delete_active", "&cCannot delete the active policy. Switch to another policy first."));
                    } else {
                        plugin.getPolicyManager().deletePolicy(policy.getName());
                        player.sendMessage(tr("messages.gui.msg_policy_deleted", "&aPolicy deleted."));
                        openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.getUniqueId(), 1));
                    }
                }
                break;
            case LIME_DYE:
                // Activate
                if (plugin.getPolicyManager().getPolicyNames().contains(policyName)) {
                    plugin.getPolicyManager().setActivePolicy(policyName);
                    player.sendMessage(plugin.getFormattedMessage("messages.tax.policy_set_success",
                            java.util.Collections.singletonMap("name", policyName)));
                    openPolicyDetails(player, policyName); // Refresh
                }
                break;
            case REDSTONE:
                // Execute
                player.closeInventory();
                player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy",
                        java.util.Collections.singletonMap("name", policyName)));
                plugin.executePolicy(player, policyName);
                break;
            case COMPASS:
                String currentType = policy.getScheduleType().toLowerCase();
                if (currentType.equals("daily")) policy.setScheduleType("weekly");
                else if (currentType.equals("weekly")) policy.setScheduleType("monthly");
                else policy.setScheduleType("daily");
                plugin.getPolicyManager().savePolicy(policy.getName());
                openPolicyDetails(player, policyName);
                break;
            case DAYLIGHT_DETECTOR:
                if ("weekly".equalsIgnoreCase(policy.getScheduleType())) {
                    promptForInput(player, tr("messages.gui.prompt_edit_days", "Enter days of week (1-7 separated by spaces, 1=Sun, 7=Sat):"), input -> {
                        try {
                            List<Integer> days = new ArrayList<>();
                            for (String s : input.split(" ")) {
                                int d = Integer.parseInt(s.trim());
                                if (d >= 1 && d <= 7) days.add(d);
                            }
                            if (!days.isEmpty()) {
                                policy.setScheduleDaysOfWeek(days);
                                plugin.getPolicyManager().savePolicy(policy.getName());
                                player.sendMessage(tr("messages.gui.msg_days_updated", "&aDays updated."));
                            } else {
                                player.sendMessage(tr("messages.gui.msg_invalid_days", "&cNo valid days provided."));
                            }
                        } catch (Exception e) { player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format.")); }
                        openPolicyDetails(player, policy.getName());
                    });
                } else if ("monthly".equalsIgnoreCase(policy.getScheduleType())) {
                    promptForInput(player, tr("messages.gui.prompt_edit_dates", "Enter dates of month (1-31 separated by spaces):"), input -> {
                        try {
                            List<Integer> dates = new ArrayList<>();
                            for (String s : input.split(" ")) {
                                int d = Integer.parseInt(s.trim());
                                if (d >= 1 && d <= 31) dates.add(d);
                            }
                            if (!dates.isEmpty()) {
                                policy.setScheduleDatesOfMonth(dates);
                                plugin.getPolicyManager().savePolicy(policy.getName());
                                player.sendMessage(tr("messages.gui.msg_dates_updated", "&aDates updated."));
                            } else {
                                player.sendMessage(tr("messages.gui.msg_invalid_dates", "&cNo valid dates provided."));
                            }
                        } catch (Exception e) { player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format.")); }
                        openPolicyDetails(player, policy.getName());
                    });
                }
                break;
            case NAME_TAG:
                policy.setRoutine(!policy.isRoutine());
                plugin.getPolicyManager().savePolicy(policy.getName());
                openPolicyDetails(player, policyName);
                break;
            case ENDER_PEARL:
                policy.setOnlyOfflinePlayers(!policy.isOnlyOfflinePlayers());
                plugin.getPolicyManager().savePolicy(policy.getName());
                openPolicyDetails(player, policyName);
                break;
            case EXPERIENCE_BOTTLE:
                policy.setPercentileThresholds(!policy.isPercentileThresholds());
                plugin.getPolicyManager().savePolicy(policy.getName());
                openPolicyDetails(player, policyName);
                break;
            case CLOCK:
                promptForInput(player, tr("messages.gui.prompt_edit_time", "Enter new check time (e.g., 00:00 or 15:30):"), input -> {
                    if (input.matches("\\d{1,2}:\\d{2}")) {
                        policy.setCheckTime(input);
                        plugin.getPolicyManager().savePolicy(policy.getName());
                        player.sendMessage(tr("messages.gui.msg_time_updated", "&aTime updated."));
                    } else {
                        player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."));
                    }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case GOLD_INGOT:
                promptForInput(player, tr("messages.gui.prompt_max_deduction", "Enter Max Deduction Per Player (e.g. 50000, 0 to disable):"), input -> {
                    try {
                        policy.setMaxDeductionPerPlayer(Double.parseDouble(input));
                        plugin.getPolicyManager().savePolicy(policy.getName());
                    } catch (Exception ex) { player.sendMessage(tr("messages.gui.msg_must_be_number", "&cMust be a number.")); }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case SHIELD:
                promptForInput(player, tr("messages.gui.prompt_min_protection", "Enter Min Balance Protection (e.g. 10000, 0 to disable):"), input -> {
                    try {
                        policy.setMinBalanceProtection(Double.parseDouble(input));
                        plugin.getPolicyManager().savePolicy(policy.getName());
                    } catch (Exception ex) { player.sendMessage(tr("messages.gui.msg_must_be_number", "&cMust be a number.")); }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case ROTTEN_FLESH:
                promptForInput(player, tr("messages.gui.prompt_inactive_deduct", "Enter Inactive Days To Deduct (e.g. 7, 0 to disable):"), input -> {
                    try {
                        policy.setInactiveDaysToDeduct(Integer.parseInt(input));
                        plugin.getPolicyManager().savePolicy(policy.getName());
                    } catch (Exception ex) { player.sendMessage(tr("messages.gui.msg_must_be_integer", "&cMust be an integer.")); }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case BONE:
                promptForInput(player, tr("messages.gui.prompt_inactive_clear", "Enter Inactive Days To Clear (e.g. 30, 0 to disable):"), input -> {
                    try {
                        policy.setInactiveDaysToClear(Integer.parseInt(input));
                        plugin.getPolicyManager().savePolicy(policy.getName());
                    } catch (Exception ex) { player.sendMessage(tr("messages.gui.msg_must_be_integer", "&cMust be an integer.")); }
                    openPolicyDetails(player, policy.getName());
                });
                break;
            case WRITABLE_BOOK:
                promptForInput(player, tr("messages.gui.prompt_rename_policy", "Enter new name for this policy:"), input -> {
                    String newName = input.trim().replace(" ", "_");
                    if (newName.isEmpty()) {
                        player.sendMessage(tr("messages.gui.msg_invalid_name", "&cInvalid name."));
                        openPolicyDetails(player, policy.getName());
                        return;
                    }
                    if (plugin.getPolicyManager().renamePolicy(policy.getName(), newName)) {
                        player.sendMessage(tr("messages.gui.msg_policy_renamed", "&aPolicy renamed to ") + newName);
                        openPolicyDetails(player, newName);
                    } else {
                        player.sendMessage(tr("messages.gui.msg_policy_exists", "&cPolicy already exists!"));
                        openPolicyDetails(player, policy.getName());
                    }
                });
                break;
            case GOLD_NUGGET:
                // Tax Brackets
                openBracketsEditor(player, policyName);
                break;
            default:
                break;
        }
    }

    private String tr(String key, String fallback) {
        String value = plugin.getLangConfig().getString(key, fallback);
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
