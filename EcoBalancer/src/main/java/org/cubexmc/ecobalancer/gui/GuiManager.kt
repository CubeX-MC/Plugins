package org.cubexmc.ecobalancer.gui

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.policies.TaxPolicy
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import java.util.Locale
import java.util.UUID
import java.util.function.Consumer
import kotlin.math.ceil
import kotlin.math.max

class GuiManager(private val plugin: EcoBalancer) : Listener {
    private val viewingPolicyDetails: MutableMap<UUID, String> = HashMap()
    private val viewingPolicyPage: MutableMap<UUID, Int> = HashMap()
    private val pendingChatInputs: MutableMap<UUID, Consumer<String>> = HashMap()

    init {
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun openMainMenu(player: Player) {
        val inv = Bukkit.createInventory(null, 45, tr("messages.gui.main_menu_title", "&6EcoBalancer Menu"))

        val active = plugin.policyManager.getActivePolicy()
        val activePolicyName = active?.name ?: tr("messages.gui.none", "None")
        val scheduleInfo = if (active != null) {
            "${active.scheduleType} @ ${active.checkTime}"
        } else {
            tr("messages.gui.na", "N/A")
        }

        inv.setItem(
            4,
            createItem(
                Material.BEACON,
                tr("messages.gui.item_header_name", "&b&lEcoBalancer"),
                tr("messages.gui.item_header_lore_1", "&7Economy management system"),
                "",
                tr("messages.gui.item_header_active", "&7Active Policy: &a") + activePolicyName,
                tr("messages.gui.item_header_schedule", "&7Schedule: &f") + scheduleInfo,
            ),
        )

        inv.setItem(
            20,
            createItem(
                Material.PAPER,
                tr("messages.gui.item_dashboard_name", "&eEconomic Dashboard"),
                tr("messages.gui.item_dashboard_lore_1", "&7View real-time economic statistics"),
                "",
                tr("messages.gui.item_dashboard_lore_2", "&7• Gini Coefficient"),
                tr("messages.gui.item_dashboard_lore_3", "&7• Total Money Supply"),
                tr("messages.gui.item_dashboard_lore_4", "&7• Player Statistics"),
                "",
                tr("messages.gui.click_to_open", "&aClick to open"),
            ),
        )

        inv.setItem(
            22,
            createItem(
                Material.GOLD_INGOT,
                tr("messages.gui.item_policies_name", "&eTax Policies"),
                tr("messages.gui.item_policies_lore_1", "&7Manage and execute tax policies"),
                "",
                tr("messages.gui.item_policies_current", "&7Current: &a") + activePolicyName,
                "",
                tr("messages.gui.click_to_manage", "&aClick to manage"),
                tr("messages.gui.requires_admin", "&cRequires: ecobalancer.gui.admin"),
            ),
        )

        if (player.hasPermission("ecobalancer.gui.admin")) {
            inv.setItem(
                24,
                createItem(
                    Material.REDSTONE,
                    tr("messages.gui.item_execute_name", "&c&lExecute Now"),
                    tr("messages.gui.item_execute_lore_1", "&7Execute active policy immediately"),
                    "",
                    tr("messages.gui.item_execute_policy", "&7Policy: &e") + activePolicyName,
                    "",
                    tr("messages.gui.click_to_execute_all", "&c⚠ Click to execute on all players"),
                ),
            )
        } else {
            inv.setItem(
                24,
                createItem(
                    Material.BARRIER,
                    tr("messages.gui.item_execute_no_name", "&8Execute Now"),
                    tr("messages.gui.requires_admin_perm", "&7Requires admin permission"),
                ),
            )
        }

        inv.setItem(38, createItem(Material.BOOK, tr("messages.gui.item_help_name", "&7Help"), tr("messages.gui.item_help_lore", "&fUse /eb help for commands")))
        inv.setItem(
            40,
            createItem(
                Material.COMPARATOR,
                tr("messages.gui.item_settings_name", "&7Settings"),
                tr("messages.gui.item_settings_lore_1", "&fEdit config.yml manually"),
                tr("messages.gui.item_settings_lore_2", "&fOr use /eb tax commands"),
            ),
        )
        inv.setItem(
            42,
            createItem(
                Material.EXPERIENCE_BOTTLE,
                tr("messages.gui.item_records_name", "&7View Records"),
                tr("messages.gui.item_records_lore_1", "&fUse /eb checkrecords"),
                tr("messages.gui.item_records_lore_2", "&ffor operation history"),
            ),
        )

        fillBackground(inv)
        player.openInventory(inv)
    }

    fun openDashboard(player: Player) {
        val inv = Bukkit.createInventory(null, 54, tr("messages.gui.dashboard_title", "&6Economic Dashboard"))

        val balances = plugin.collectAllBalances() ?: ArrayList()
        val totalMoney = balances.sum()
        var gini = 0.0
        var mean = 0.0
        var median = 0.0
        var top1 = 0.0
        var top10 = 0.0

        try {
            gini = EconomicMetrics.calculateGini(balances)
            mean = EconomicMetrics.calculateMean(balances)
            val sorted = EconomicMetrics.getSortedBalances(balances)
            median = EconomicMetrics.calculateMedian(sorted)
            top1 = EconomicMetrics.calculateConcentration(balances, 1.0)
            top10 = EconomicMetrics.calculateConcentration(balances, 10.0)
        } catch (_: Exception) {
            // Preserve the original GUI's best-effort dashboard behavior.
        }

        inv.setItem(
            4,
            createItem(
                Material.BEACON,
                tr("messages.gui.dash_header_name", "&b&lEconomic Dashboard"),
                tr("messages.gui.dash_header_lore", "&7Real-time economy status"),
                "",
                tr("messages.gui.dash_header_players", "&7Total Players: &f") + balances.size,
            ),
        )

        inv.setItem(19, createItem(Material.GOLD_BLOCK, tr("messages.gui.dash_supply_name", "&e&lTotal Supply"), "§f" + EconomicMetrics.formatLargeNumber(totalMoney), "", tr("messages.gui.dash_supply_lore", "&7Combined balance of all players")))

        val giniColor = if (gini < 0.3) "§a" else if (gini < 0.5) "§e" else "§c"
        inv.setItem(
            21,
            createItem(
                Material.DIAMOND,
                tr("messages.gui.dash_gini_name", "&b&lGini Coefficient"),
                giniColor + String.format("%.4f", gini),
                "",
                tr("messages.gui.dash_gini_lore1", "&70.0 = Perfect Equality"),
                tr("messages.gui.dash_gini_lore2", "&71.0 = Maximum Inequality"),
                "",
                getGiniDescription(gini),
            ),
        )

        inv.setItem(23, createItem(Material.EMERALD, tr("messages.gui.dash_mean_name", "&a&lMean Balance"), "§f" + EconomicMetrics.formatLargeNumber(mean), "", tr("messages.gui.dash_mean_lore", "&7Average player balance")))
        inv.setItem(25, createItem(Material.HEART_OF_THE_SEA, tr("messages.gui.dash_median_name", "&d&lMedian Balance"), "§f" + EconomicMetrics.formatLargeNumber(median), "", tr("messages.gui.dash_median_lore", "&7Middle player balance")))

        val top1Color = if (top1 < 20) "§a" else if (top1 < 40) "§e" else "§c"
        val top10Color = if (top10 < 50) "§a" else if (top10 < 70) "§e" else "§c"

        inv.setItem(29, createItem(Material.GOLDEN_APPLE, tr("messages.gui.dash_top1_name", "&6&lTop 1% Wealth"), top1Color + String.format("%.1f%%", top1), "", tr("messages.gui.dash_top1_lore", "&7Wealth held by richest 1%")))
        inv.setItem(31, createItem(Material.APPLE, tr("messages.gui.dash_top10_name", "&c&lTop 10% Wealth"), top10Color + String.format("%.1f%%", top10), "", tr("messages.gui.dash_top10_lore", "&7Wealth held by richest 10%")))
        inv.setItem(
            33,
            createItem(
                Material.CLOCK,
                tr("messages.gui.dash_active_name", "&e&lActive Players"),
                tr("messages.gui.dash_active_lore1", "&7Use /eb health for details"),
                "",
                tr("messages.gui.dash_active_lore2", "&7Tracks 7-day and 30-day"),
                tr("messages.gui.dash_active_lore3", "&7player activity"),
            ),
        )

        if (player.hasPermission("ecobalancer.gui.admin")) {
            inv.setItem(47, createItem(Material.WRITABLE_BOOK, tr("messages.gui.dash_health_name", "&eView Health Report"), tr("messages.gui.dash_health_lore1", "&7Detailed economy analysis"), "", tr("messages.gui.dash_health_lore2", "&aRun: /eb health")))
            inv.setItem(49, createItem(Material.MAP, tr("messages.gui.dash_trends_name", "&eView Trends"), tr("messages.gui.dash_trends_lore1", "&7Historical data over time"), "", tr("messages.gui.dash_trends_lore2", "&aRun: /eb trends")))
            inv.setItem(51, createItem(Material.CHEST, tr("messages.gui.dash_records_name", "&eView Records"), tr("messages.gui.dash_records_lore1", "&7Tax operation history"), "", tr("messages.gui.dash_records_lore2", "&aRun: /eb checkrecords")))
        }

        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_main", "&7Return to Main Menu")))
        fillBackground(inv)
        player.openInventory(inv)
    }

    private fun getGiniDescription(gini: Double): String =
        when {
            gini < 0.2 -> tr("messages.gui.gini_excellent", "&aExcellent - Very equal distribution")
            gini < 0.3 -> tr("messages.gui.gini_good", "&aGood - Relatively equal")
            gini < 0.4 -> tr("messages.gui.gini_moderate", "&eModerate - Some inequality")
            gini < 0.5 -> tr("messages.gui.gini_warning", "&eWarning - High inequality")
            else -> tr("messages.gui.gini_critical", "&cCritical - Severe inequality")
        }

    fun openTaxPolicies(player: Player) {
        openTaxPolicies(player, 1)
    }

    fun openTaxPolicies(player: Player, requestedPage: Int) {
        if (!player.hasPermission("ecobalancer.gui.admin")) {
            player.sendMessage(plugin.getFormattedMessage("messages.gui.no_permission_tax_policies", null))
            return
        }

        var page = requestedPage
        viewingPolicyPage[player.uniqueId] = page
        val policyNames = plugin.policyManager.getPolicyNames()
        val itemsPerPage = 36
        val totalPages = max(1, ceil(policyNames.size.toDouble() / itemsPerPage).toInt())
        if (page < 1) page = 1
        if (page > totalPages) page = totalPages

        val inv = Bukkit.createInventory(null, 54, tr("messages.gui.tax_policies_title", "&6Tax Policies") + " - Page " + page)
        val active = plugin.policyManager.getActivePolicy()
        val activeName = active?.name ?: ""

        inv.setItem(
            4,
            createItem(
                Material.GOLD_INGOT,
                tr("messages.gui.tp_header_name", "&e&lTax Policy Manager"),
                tr("messages.gui.tp_header_lore1", "&7Manage your tax policies"),
                "",
                tr("messages.gui.tp_header_active", "&7Active: &a") + (if (activeName.isEmpty()) tr("messages.gui.none", "None") else activeName),
                "",
                tr("messages.gui.tp_header_hint1", "&e&lLeft-click&7 - View details / Activate"),
                tr("messages.gui.tp_header_hint2", "&c&lShift+Left-click&7 - Execute immediately"),
            ),
        )

        val startIndex = (page - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, policyNames.size)
        var slot = 9
        for (i in startIndex until endIndex) {
            val name = policyNames[i]
            val isActive = name == activeName
            val mat = if (isActive) Material.ENCHANTED_BOOK else Material.BOOK
            val displayName = (if (isActive) tr("messages.gui.tp_item_active_prefix", "&a✓ ") else "&e") + name

            val policy = plugin.policyManager.getPolicy(name)
            val desc = policy?.description ?: ""
            val schedule = policy?.scheduleType ?: "?"
            val time = policy?.checkTime ?: "?"
            val isRoutine = policy?.isRoutine ?: false
            val lore = ArrayList<String>()
            lore.add("§7$desc")
            lore.add("")
            lore.add(tr("messages.gui.tp_item_schedule", "&7Schedule: &f") + capitalize(schedule) + " @ " + time)
            lore.add(tr("messages.gui.tp_item_routine", "&7Routine: ") + if (isRoutine) tr("messages.gui.yes_auto", "&aYes (Auto)") else tr("messages.gui.no_manual", "&cNo (Manual)"))
            lore.add("")
            if (isActive) {
                lore.add(tr("messages.gui.tp_item_active_lore", "&a&lCURRENT ACTIVE POLICY"))
            } else {
                lore.add(tr("messages.gui.tp_item_inactive_lore", "&eLeft-click to activate/edit"))
            }
            lore.add(tr("messages.gui.tp_item_execute_lore", "&cShift+click to execute now"))
            lore.add("§8RAW_POLICY:$name")
            inv.setItem(slot++, createItem(mat, displayName, *lore.toTypedArray()))
        }

        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_main", "&7Return to Main Menu")))
        if (page > 1) {
            inv.setItem(48, createItem(Material.ARROW, tr("messages.gui.btn_prev_page", "&aPrevious Page")))
        }
        if (page < totalPages) {
            inv.setItem(50, createItem(Material.ARROW, tr("messages.gui.btn_next_page", "&aNext Page")))
        }

        if (active != null) {
            inv.setItem(
                49,
                createItem(
                    Material.REDSTONE,
                    tr("messages.gui.tp_btn_execute_active", "&c&lExecute Active Policy"),
                    tr("messages.gui.tp_btn_execute_lore", "&7Run &e") + activeName + tr("messages.gui.tp_btn_execute_now", "&7 now"),
                    "",
                    tr("messages.gui.click_to_execute_all", "&c⚠ Affects all players!"),
                ),
            )
        }

        inv.setItem(51, createItem(Material.EMERALD, tr("messages.gui.tp_btn_add", "&a&lAdd New Policy"), tr("messages.gui.tp_btn_add_lore1", "&7Create a new blank tax policy"), "", tr("messages.gui.tp_btn_add_click", "&aClick to create via Chat")))
        inv.setItem(53, createItem(Material.CHEST, tr("messages.gui.tp_btn_reload", "&eReload Policies"), tr("messages.gui.tp_btn_reload_lore", "&7Reload policies from disk")))

        fillBackground(inv)
        player.openInventory(inv)
    }

    fun openPolicyDetails(player: Player, policyName: String) {
        val policy = plugin.policyManager.getPolicy(policyName)
        if (policy == null) {
            player.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to policyName)))
            return
        }

        viewingPolicyDetails[player.uniqueId] = policyName
        val inv = Bukkit.createInventory(null, 54, tr("messages.gui.policy_title_prefix", "&6Policy: ") + policyName)
        val active = plugin.policyManager.getActivePolicy()
        val isActive = active != null && active.name == policyName

        inv.setItem(
            4,
            createItem(
                if (isActive) Material.ENCHANTED_BOOK else Material.BOOK,
                (if (isActive) "§a§l" else "§e§l") + policyName,
                "§7" + (policy.description ?: ""),
                "",
                if (isActive) tr("messages.gui.pd_active", "&a✓ Currently Active") else tr("messages.gui.pd_inactive", "&7Inactive"),
            ),
        )

        inv.setItem(18, createItem(Material.COMPASS, tr("messages.gui.pd_toggle_schedule_type", "&eToggle Schedule Type"), tr("messages.gui.pd_schedule_type", "&7Type: &f") + capitalize(policy.scheduleType), "", tr("messages.gui.pd_desc_schedule_type", "&7Select daily, weekly, or monthly execution."), "", tr("messages.gui.click_toggle", "&aClick to toggle")))
        inv.setItem(19, createItem(Material.CLOCK, tr("messages.gui.pd_edit_time", "&eEdit Time"), tr("messages.gui.pd_time", "&7Time: &f") + policy.checkTime, "", tr("messages.gui.pd_desc_time", "&7The exact time of day to trigger the policy."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))

        if ("weekly".equals(policy.scheduleType, ignoreCase = true)) {
            inv.setItem(20, createItem(Material.DAYLIGHT_DETECTOR, tr("messages.gui.pd_edit_days", "&eEdit Days of Week"), tr("messages.gui.pd_days_list", "&7Days: &f") + formatDaysOfWeek(policy.scheduleDaysOfWeek), "", tr("messages.gui.pd_desc_days", "&7Which days of the week to run the policy."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        } else if ("monthly".equals(policy.scheduleType, ignoreCase = true)) {
            inv.setItem(20, createItem(Material.DAYLIGHT_DETECTOR, tr("messages.gui.pd_edit_dates", "&eEdit Dates of Month"), tr("messages.gui.pd_dates_list", "&7Dates: &f") + policy.scheduleDatesOfMonth.joinToString(", "), "", tr("messages.gui.pd_desc_dates", "&7Which dates of the month to run the policy."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        }

        inv.setItem(21, createItem(Material.NAME_TAG, tr("messages.gui.pd_toggle_routine", "&eToggle Routine"), tr("messages.gui.pd_routine", "&7Routine: ") + if (policy.isRoutine) tr("messages.gui.yes", "&aYes") else tr("messages.gui.no", "&cNo"), "", tr("messages.gui.pd_desc_routine_1", "&7Auto: Runs automatically at the scheduled time."), tr("messages.gui.pd_desc_routine_2", "&7Manual: Only runs when executed by an admin."), "", tr("messages.gui.click_toggle", "&aClick to toggle")))
        inv.setItem(22, createItem(Material.GOLD_INGOT, tr("messages.gui.pd_max_deduction", "&eMax Deduction"), tr("messages.gui.pd_max_deduction_val", "&7Max Deduction: &f") + EconomicMetrics.formatLargeNumber(policy.maxDeductionPerPlayer), "", tr("messages.gui.pd_desc_max_deduction_1", "&7The maximum amount of money to deduct"), tr("messages.gui.pd_desc_max_deduction_2", "&7from a player in a single execution."), tr("messages.gui.pd_desc_max_deduction_3", "&7Set to 0 to disable the limit."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        inv.setItem(23, createItem(Material.SHIELD, tr("messages.gui.pd_min_protection", "&eMin Balance Protection"), tr("messages.gui.pd_min_protection_val", "&7Min Protection: &f") + EconomicMetrics.formatLargeNumber(policy.minBalanceProtection), "", tr("messages.gui.pd_desc_min_protection_1", "&7Players with balance below this value"), tr("messages.gui.pd_desc_min_protection_2", "&7will not be taxed (poor protection)."), tr("messages.gui.pd_desc_min_protection_3", "&7Set to 0 to disable."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        inv.setItem(24, createItem(Material.ENDER_PEARL, tr("messages.gui.pd_toggle_offline", "&eToggle Offline Only"), tr("messages.gui.pd_offline", "&7Offline Only: ") + if (policy.isOnlyOfflinePlayers) tr("messages.gui.yes", "&aYes") else tr("messages.gui.no", "&cNo"), "", tr("messages.gui.pd_desc_offline_1", "&7Yes: Only taxes offline/AFK players."), tr("messages.gui.pd_desc_offline_2", "&7No: Taxes everyone including online players."), "", tr("messages.gui.click_toggle", "&aClick to toggle")))
        inv.setItem(25, createItem(Material.EXPERIENCE_BOTTLE, tr("messages.gui.pd_toggle_percentile", "&eToggle Percentile Mode"), tr("messages.gui.pd_percentile", "&7Percentile Mode: ") + if (policy.isPercentileThresholds) tr("messages.gui.yes", "&aYes") else tr("messages.gui.no", "&cNo"), "", tr("messages.gui.pd_desc_percentile_1", "&7Yes: Thresholds use wealth percentile (e.g. top 10%)."), tr("messages.gui.pd_desc_percentile_2", "&7No: Thresholds use absolute balance (e.g. 100k)."), "", tr("messages.gui.click_toggle", "&aClick to toggle")))
        inv.setItem(29, createItem(Material.ROTTEN_FLESH, tr("messages.gui.pd_inactive_deduct", "&eInactive Days To Deduct"), tr("messages.gui.pd_days", "&7Days: &f") + if (policy.inactiveDaysToDeduct > 0) policy.inactiveDaysToDeduct else tr("messages.gui.disabled", "Disabled"), "", tr("messages.gui.pd_desc_inactive_deduct_1", "&7Start deducting tax only if the player"), tr("messages.gui.pd_desc_inactive_deduct_2", "&7has been offline for this many days."), tr("messages.gui.pd_desc_inactive_deduct_3", "&7Set to 0 to disable."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        inv.setItem(30, createItem(Material.BONE, tr("messages.gui.pd_inactive_clear", "&eInactive Days To Clear"), tr("messages.gui.pd_days", "&7Days: &f") + if (policy.inactiveDaysToClear > 0) policy.inactiveDaysToClear else tr("messages.gui.disabled", "Disabled"), "", tr("messages.gui.pd_desc_inactive_clear_1", "&7Clear the player's entire balance if they"), tr("messages.gui.pd_desc_inactive_clear_2", "&7have been offline for this many days."), tr("messages.gui.pd_desc_inactive_clear_3", "&7Set to 0 to disable."), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        inv.setItem(32, createItem(Material.GOLD_NUGGET, tr("messages.gui.pd_manage_brackets", "&6&lManage Tax Brackets"), "§7" + policy.taxBrackets.size + tr("messages.gui.pd_brackets_count", " bracket(s) configured"), "", tr("messages.gui.pd_click_brackets", "&aClick to open Brackets Editor")))
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_list", "&7Return to Policy List")))
        inv.setItem(47, createItem(Material.PAPER, tr("messages.gui.pd_test_calc", "&bTest Calculation"), tr("messages.gui.pd_test_calc_lore1", "&7Simulate a tax deduction"), "", tr("messages.gui.pd_test_calc_click", "&aClick to test via Chat")))

        if (!isActive) {
            inv.setItem(49, createItem(Material.LIME_DYE, tr("messages.gui.pd_activate", "&aActivate This Policy"), tr("messages.gui.pd_activate_lore", "&7Set as the active policy"), "", tr("messages.gui.click_activate", "&aClick to activate")))
        }

        inv.setItem(50, createItem(Material.WRITABLE_BOOK, tr("messages.gui.pd_rename", "&eRename Policy"), tr("messages.gui.pd_rename_lore", "&7Change the name of this policy"), "", tr("messages.gui.click_edit_chat", "&aClick to edit in chat")))
        inv.setItem(51, createItem(Material.BARRIER, tr("messages.gui.pd_delete", "&cDelete Policy"), tr("messages.gui.pd_delete_lore", "&7Remove this policy entirely"), "", tr("messages.gui.click_delete", "&cShift+Left-click to delete")))
        inv.setItem(53, createItem(Material.REDSTONE, tr("messages.gui.btn_execute", "&c&lExecute Now"), tr("messages.gui.pd_execute_lore", "&7Run this policy immediately"), "", tr("messages.gui.click_to_execute_all", "&c⚠ Affects all players!")))

        fillBackground(inv)
        player.openInventory(inv)
    }

    fun openBracketsEditor(player: Player, policyName: String) {
        val policy = plugin.policyManager.getPolicy(policyName) ?: return
        viewingPolicyDetails[player.uniqueId] = policyName
        val inv = Bukkit.createInventory(null, 54, tr("messages.gui.policy_title_prefix", "&6Policy: ") + policyName + tr("messages.gui.be_suffix", " (Brackets)"))

        inv.setItem(4, createItem(Material.GOLD_BLOCK, tr("messages.gui.be_header", "&6&lTax Brackets Editor"), tr("messages.gui.be_managing", "&7Managing brackets for: &e") + policyName))

        val brackets = policy.taxBrackets.toMutableList()
        brackets.sortBy { ((it["threshold"] as Number).toDouble()) }

        var slot = 9
        var index = 0
        while (index < brackets.size && slot < 45) {
            val bracket = brackets[index]
            val threshold = (bracket["threshold"] as Number).toDouble()
            val rate = (bracket["rate"] as Number).toDouble()
            inv.setItem(
                slot++,
                createItem(
                    Material.GOLD_NUGGET,
                    tr("messages.gui.be_bracket_name", "&eBracket: ") + EconomicMetrics.formatLargeNumber(threshold),
                    tr("messages.gui.be_bracket_th", "&7Threshold: &f") + EconomicMetrics.formatLargeNumber(threshold),
                    tr("messages.gui.be_bracket_rate", "&7Rate: &e") + String.format("%.2f%%", rate * 100),
                    "",
                    tr("messages.gui.be_bracket_delete", "&cShift+Left-click to Delete"),
                    "§8RAW_TH:$threshold",
                ),
            )
            index++
        }

        inv.setItem(49, createItem(Material.EMERALD, tr("messages.gui.be_add_bracket", "&a&l➕ Add New Bracket"), tr("messages.gui.be_add_click", "&7Click to add via Chat")))
        inv.setItem(45, createItem(Material.ARROW, tr("messages.gui.btn_back_name", "&cBack"), tr("messages.gui.btn_back_settings", "&7Return to Policy Settings")))
        fillBackground(inv)
        player.openInventory(inv)
    }

    private fun capitalize(value: String?): String {
        if (value.isNullOrEmpty()) {
            return value ?: ""
        }
        return value.substring(0, 1).uppercase(Locale.getDefault()) + value.substring(1).lowercase(Locale.getDefault())
    }

    private fun formatDaysOfWeek(days: List<Int>?): String {
        if (days.isNullOrEmpty()) return ""
        val dayNames = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return days.filter { it in 1..7 }.joinToString(", ") { dayNames[it] }
    }

    private fun createItem(mat: Material, data: Int, name: String, vararg lore: String): ItemStack {
        val item = ItemStack(mat, 1, data.toShort())
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(name)
            meta.lore = lore.asList()
            item.itemMeta = meta
        }
        return item
    }

    private fun createItem(mat: Material, name: String, vararg lore: String): ItemStack = createItem(mat, 0, name, *lore)

    private fun fillBackground(inv: Inventory) {
        val pane = createItem(Material.BLACK_STAINED_GLASS_PANE, 0, " ")
        for (i in 0 until inv.size) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, pane)
            }
        }
    }

    @EventHandler
    fun onPlayerChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val callback = pendingChatInputs.remove(player.uniqueId) ?: return
        event.isCancelled = true
        val message = event.message

        if (message.trim().equals(tr("messages.gui.chat_cancel_keyword", "cancel"), ignoreCase = true)) {
            player.sendMessage(tr("messages.gui.chat_cancelled", "&e[EcoBalancer] &cInput cancelled."))
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val policyName = viewingPolicyDetails[player.uniqueId]
                if (policyName != null) {
                    openPolicyDetails(player, policyName)
                } else {
                    openMainMenu(player)
                }
            })
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable { callback.accept(message.trim()) })
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val title = event.view.title
        val mainMenuTitle = tr("messages.gui.main_menu_title", "&6EcoBalancer Menu")
        val dashboardTitle = tr("messages.gui.dashboard_title", "&6Economic Dashboard")
        val taxPoliciesTitle = tr("messages.gui.tax_policies_title", "&6Tax Policies")
        val policyPrefix = tr("messages.gui.policy_title_prefix", "&6Policy: ")

        if (title != mainMenuTitle && title != dashboardTitle && !title.startsWith(taxPoliciesTitle) && !title.startsWith(policyPrefix)) {
            return
        }

        event.isCancelled = true
        val current = event.currentItem ?: return
        if (current.type == Material.AIR || current.type == Material.BLACK_STAINED_GLASS_PANE) {
            return
        }

        if (title == mainMenuTitle) {
            handleMainMenuClick(player, current)
        } else if (title == dashboardTitle) {
            when (current.type) {
                Material.ARROW -> openMainMenu(player)
                Material.WRITABLE_BOOK -> {
                    player.closeInventory()
                    player.performCommand("eb health")
                }
                Material.MAP -> {
                    player.closeInventory()
                    player.performCommand("eb trends")
                }
                Material.CHEST -> {
                    player.closeInventory()
                    player.performCommand("eb checkrecords")
                }
                else -> {
                }
            }
        } else if (title.startsWith(taxPoliciesTitle)) {
            handleTaxPoliciesClick(player, current, event.click)
        } else if (title.startsWith(policyPrefix)) {
            handlePolicyDetailsClick(player, current, title, event.click)
        }
    }

    private fun handleMainMenuClick(player: Player, current: ItemStack) {
        when (current.type) {
            Material.PAPER -> openDashboard(player)
            Material.GOLD_INGOT -> openTaxPolicies(player)
            Material.REDSTONE -> {
                if (player.hasPermission("ecobalancer.gui.admin")) {
                    val active = plugin.policyManager.getActivePolicy()
                    if (active != null) {
                        player.closeInventory()
                        player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_active_policy", mapOf("name" to (active.name ?: ""))))
                        plugin.checkAll(player)
                    } else {
                        player.sendMessage(plugin.getFormattedMessage("messages.gui.no_active_policy", null))
                    }
                }
            }
            Material.BOOK -> {
                player.closeInventory()
                player.performCommand("eb help")
            }
            Material.COMPARATOR -> {
                player.closeInventory()
                player.performCommand("eb reload")
            }
            Material.EXPERIENCE_BOTTLE -> {
                player.closeInventory()
                player.performCommand("eb checkrecords")
            }
            else -> {
            }
        }
    }

    private fun handleTaxPoliciesClick(player: Player, current: ItemStack, click: ClickType) {
        when (current.type) {
            Material.ARROW -> {
                val meta = current.itemMeta
                if (meta != null && meta.hasDisplayName()) {
                    val name = meta.displayName
                    if (name.contains("Previous Page")) {
                        val page = viewingPolicyPage.getOrDefault(player.uniqueId, 1)
                        openTaxPolicies(player, page - 1)
                        return
                    } else if (name.contains("Next Page")) {
                        val page = viewingPolicyPage.getOrDefault(player.uniqueId, 1)
                        openTaxPolicies(player, page + 1)
                        return
                    }
                }
                openMainMenu(player)
            }
            Material.CHEST -> {
                plugin.policyManager.loadPolicies()
                player.sendMessage(plugin.getFormattedMessage("messages.gui.policies_reloaded", null))
                openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.uniqueId, 1))
            }
            Material.EMERALD -> {
                promptForInput(player, tr("messages.gui.prompt_new_policy", "Enter name for the new policy (no spaces):")) { input ->
                    val newName = input.replace(" ", "_")
                    if (plugin.policyManager.createPolicy(newName)) {
                        player.sendMessage(tr("messages.gui.msg_policy_created", "&aPolicy ") + newName + tr("messages.gui.msg_created", " created."))
                        openPolicyDetails(player, newName)
                    } else {
                        player.sendMessage(tr("messages.gui.msg_policy_exists", "&cPolicy already exists!"))
                        openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.uniqueId, 1))
                    }
                }
            }
            Material.REDSTONE -> {
                val active = plugin.policyManager.getActivePolicy()
                if (active != null) {
                    player.closeInventory()
                    player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy", mapOf("name" to (active.name ?: ""))))
                    plugin.checkAll(player)
                }
            }
            Material.BOOK, Material.ENCHANTED_BOOK -> {
                val lore = current.itemMeta?.lore
                if (lore != null) {
                    var rawName: String? = null
                    for (line in lore) {
                        if (line.startsWith("§8RAW_POLICY:")) {
                            rawName = line.substring(13)
                            break
                        }
                    }

                    if (rawName != null && plugin.policyManager.getPolicyNames().contains(rawName)) {
                        if (click.isShiftClick) {
                            player.closeInventory()
                            player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy", mapOf("name" to rawName)))
                            plugin.executePolicy(player, rawName)
                        } else {
                            openPolicyDetails(player, rawName)
                        }
                    }
                }
            }
            else -> {
            }
        }
    }

    private fun promptForInput(player: Player, prompt: String, callback: Consumer<String>) {
        player.closeInventory()
        player.sendMessage(tr("messages.gui.chat_prefix", "&e[EcoBalancer] &f") + prompt)
        player.sendMessage(tr("messages.gui.chat_cancel_hint", "&7Type 'cancel' to abort."))
        pendingChatInputs[player.uniqueId] = callback
    }

    private fun handlePolicyDetailsClick(player: Player, current: ItemStack, title: String, click: ClickType) {
        var policyName = viewingPolicyDetails[player.uniqueId]
        if (policyName == null) {
            policyName = title.replace(tr("messages.gui.policy_title_prefix", "&6Policy: "), "").replace(" (Brackets)", "")
        }

        val policy = plugin.policyManager.getPolicy(policyName) ?: return
        val currentPolicyName = policyName(policy, policyName)
        val isBracketsView = title.contains("(Brackets)")

        if (isBracketsView) {
            handleBracketsClick(player, current, click, policyName, policy)
            return
        }

        when (current.type) {
            Material.ARROW -> {
                val page = viewingPolicyPage.getOrDefault(player.uniqueId, 1)
                openTaxPolicies(player, page)
            }
            Material.PAPER -> {
                promptForInput(player, tr("messages.gui.prompt_test_balance", "Enter an imaginary balance to test the deduction:")) { input ->
                    try {
                        val balance = input.toDouble()
                        val deduction = policy.calculateTax(balance) { name -> plugin.policyManager.getPolicy(name) }
                        player.sendMessage(tr("messages.gui.test_balance", "&b[Test] &7Balance: &f") + EconomicMetrics.formatLargeNumber(balance))
                        player.sendMessage(tr("messages.gui.test_deduction", "&b[Test] &7Expected Deduction: &c-") + EconomicMetrics.formatLargeNumber(deduction))
                        player.sendMessage(tr("messages.gui.test_new_balance", "&b[Test] &7New Balance: &a") + EconomicMetrics.formatLargeNumber(balance - deduction))
                    } catch (_: NumberFormatException) {
                        player.sendMessage(tr("messages.gui.msg_invalid_number", "&cInvalid number format."))
                    }
                    openPolicyDetails(player, currentPolicyName)
                }
            }
            Material.BARRIER -> {
                if (click.isShiftClick) {
                    val active = plugin.policyManager.getActivePolicy()
                    if (active != null && active.name == policy.name) {
                        player.sendMessage(tr("messages.gui.msg_cant_delete_active", "&cCannot delete the active policy. Switch to another policy first."))
                    } else {
                        plugin.policyManager.deletePolicy(currentPolicyName)
                        player.sendMessage(tr("messages.gui.msg_policy_deleted", "&aPolicy deleted."))
                        openTaxPolicies(player, viewingPolicyPage.getOrDefault(player.uniqueId, 1))
                    }
                }
            }
            Material.LIME_DYE -> {
                if (plugin.policyManager.getPolicyNames().contains(policyName)) {
                    plugin.policyManager.setActivePolicy(policyName)
                    player.sendMessage(plugin.getFormattedMessage("messages.tax.policy_set_success", mapOf("name" to policyName)))
                    openPolicyDetails(player, policyName)
                }
            }
            Material.REDSTONE -> {
                player.closeInventory()
                player.sendMessage(plugin.getFormattedMessage("messages.gui.executing_policy", mapOf("name" to policyName)))
                plugin.executePolicy(player, policyName)
            }
            Material.COMPASS -> {
                val currentType = (policy.scheduleType ?: "").lowercase(Locale.getDefault())
                policy.scheduleType = if (currentType == "daily") "weekly" else if (currentType == "weekly") "monthly" else "daily"
                plugin.policyManager.savePolicy(currentPolicyName)
                openPolicyDetails(player, policyName)
            }
            Material.DAYLIGHT_DETECTOR -> handleDaylightDetectorClick(player, policyName, policy)
            Material.NAME_TAG -> {
                policy.isRoutine = !policy.isRoutine
                plugin.policyManager.savePolicy(currentPolicyName)
                openPolicyDetails(player, policyName)
            }
            Material.ENDER_PEARL -> {
                policy.isOnlyOfflinePlayers = !policy.isOnlyOfflinePlayers
                plugin.policyManager.savePolicy(currentPolicyName)
                openPolicyDetails(player, policyName)
            }
            Material.EXPERIENCE_BOTTLE -> {
                policy.isPercentileThresholds = !policy.isPercentileThresholds
                plugin.policyManager.savePolicy(currentPolicyName)
                openPolicyDetails(player, policyName)
            }
            Material.CLOCK -> promptForInput(player, tr("messages.gui.prompt_edit_time", "Enter new check time (e.g., 00:00 or 15:30):")) { input ->
                if (input.matches(Regex("\\d{1,2}:\\d{2}"))) {
                    policy.checkTime = input
                    plugin.policyManager.savePolicy(currentPolicyName)
                    player.sendMessage(tr("messages.gui.msg_time_updated", "&aTime updated."))
                } else {
                    player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."))
                }
                openPolicyDetails(player, currentPolicyName)
            }
            Material.GOLD_INGOT -> promptForInput(player, tr("messages.gui.prompt_max_deduction", "Enter Max Deduction Per Player (e.g. 50000, 0 to disable):")) { input ->
                try {
                    policy.maxDeductionPerPlayer = input.toDouble()
                    plugin.policyManager.savePolicy(currentPolicyName)
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_must_be_number", "&cMust be a number."))
                }
                openPolicyDetails(player, currentPolicyName)
            }
            Material.SHIELD -> promptForInput(player, tr("messages.gui.prompt_min_protection", "Enter Min Balance Protection (e.g. 10000, 0 to disable):")) { input ->
                try {
                    policy.minBalanceProtection = input.toDouble()
                    plugin.policyManager.savePolicy(currentPolicyName)
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_must_be_number", "&cMust be a number."))
                }
                openPolicyDetails(player, currentPolicyName)
            }
            Material.ROTTEN_FLESH -> promptForInput(player, tr("messages.gui.prompt_inactive_deduct", "Enter Inactive Days To Deduct (e.g. 7, 0 to disable):")) { input ->
                try {
                    policy.inactiveDaysToDeduct = input.toInt()
                    plugin.policyManager.savePolicy(currentPolicyName)
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_must_be_integer", "&cMust be an integer."))
                }
                openPolicyDetails(player, currentPolicyName)
            }
            Material.BONE -> promptForInput(player, tr("messages.gui.prompt_inactive_clear", "Enter Inactive Days To Clear (e.g. 30, 0 to disable):")) { input ->
                try {
                    policy.inactiveDaysToClear = input.toInt()
                    plugin.policyManager.savePolicy(currentPolicyName)
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_must_be_integer", "&cMust be an integer."))
                }
                openPolicyDetails(player, currentPolicyName)
            }
            Material.WRITABLE_BOOK -> promptForInput(player, tr("messages.gui.prompt_rename_policy", "Enter new name for this policy:")) { input ->
                val newName = input.trim().replace(" ", "_")
                if (newName.isEmpty()) {
                    player.sendMessage(tr("messages.gui.msg_invalid_name", "&cInvalid name."))
                    openPolicyDetails(player, currentPolicyName)
                    return@promptForInput
                }
                if (plugin.policyManager.renamePolicy(currentPolicyName, newName)) {
                    player.sendMessage(tr("messages.gui.msg_policy_renamed", "&aPolicy renamed to ") + newName)
                    openPolicyDetails(player, newName)
                } else {
                    player.sendMessage(tr("messages.gui.msg_policy_exists", "&cPolicy already exists!"))
                    openPolicyDetails(player, currentPolicyName)
                }
            }
            Material.GOLD_NUGGET -> openBracketsEditor(player, policyName)
            else -> {
            }
        }
    }

    private fun handleBracketsClick(player: Player, current: ItemStack, click: ClickType, policyName: String, policy: TaxPolicy) {
        when (current.type) {
            Material.ARROW -> openPolicyDetails(player, policyName)
            Material.EMERALD -> {
                promptForInput(player, tr("messages.gui.prompt_add_bracket", "Enter threshold and rate separated by space (e.g. 1000 0.05):")) { input ->
                    try {
                        val parts = input.split(" ")
                        val threshold = parts[0].toDouble()
                        val rate = parts[1].toDouble()
                        val bracket: MutableMap<String, Any> = HashMap()
                        bracket["threshold"] = threshold
                        bracket["rate"] = rate
                        val brackets = policy.taxBrackets.toMutableList()
                        brackets.add(bracket)
                        policy.taxBrackets = brackets
                        plugin.policyManager.savePolicy(policyName(policy, policyName))
                        player.sendMessage(tr("messages.gui.msg_bracket_added", "&aBracket added."))
                    } catch (_: Exception) {
                        player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."))
                    }
                    openBracketsEditor(player, policyName(policy, policyName))
                }
            }
            Material.GOLD_NUGGET -> {
                val meta = current.itemMeta
                if (meta != null && meta.hasDisplayName() && meta.displayName.startsWith(tr("messages.gui.be_bracket_prefix", "&eBracket: ")) && click.isShiftClick) {
                    val lore = meta.lore
                    if (lore != null) {
                        for (line in lore) {
                            if (line.startsWith("§8RAW_TH:")) {
                                try {
                                    val threshold = line.replace("§8RAW_TH:", "").toDouble()
                                    val brackets = policy.taxBrackets.toMutableList()
                                    brackets.removeIf { bracket -> kotlin.math.abs((bracket["threshold"] as Number).toDouble() - threshold) < 1e-6 }
                                    policy.taxBrackets = brackets
                                    plugin.policyManager.savePolicy(policyName(policy, policyName))
                                    player.sendMessage(tr("messages.gui.msg_bracket_removed", "&aBracket removed."))
                                } catch (_: Exception) {
                                }
                                break
                            }
                        }
                    }
                    openBracketsEditor(player, policyName(policy, policyName))
                }
            }
            else -> {
            }
        }
    }

    private fun handleDaylightDetectorClick(player: Player, policyName: String, policy: TaxPolicy) {
        if ("weekly".equals(policy.scheduleType, ignoreCase = true)) {
            promptForInput(player, tr("messages.gui.prompt_edit_days", "Enter days of week (1-7 separated by spaces, 1=Sun, 7=Sat):")) { input ->
                try {
                    val days = ArrayList<Int>()
                    for (part in input.split(" ")) {
                        val day = part.trim().toInt()
                        if (day in 1..7) days.add(day)
                    }
                    if (days.isNotEmpty()) {
                        policy.scheduleDaysOfWeek = days
                        plugin.policyManager.savePolicy(policyName(policy, policyName))
                        player.sendMessage(tr("messages.gui.msg_days_updated", "&aDays updated."))
                    } else {
                        player.sendMessage(tr("messages.gui.msg_invalid_days", "&cNo valid days provided."))
                    }
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."))
                }
                openPolicyDetails(player, policyName(policy, policyName))
            }
        } else if ("monthly".equals(policy.scheduleType, ignoreCase = true)) {
            promptForInput(player, tr("messages.gui.prompt_edit_dates", "Enter dates of month (1-31 separated by spaces):")) { input ->
                try {
                    val dates = ArrayList<Int>()
                    for (part in input.split(" ")) {
                        val date = part.trim().toInt()
                        if (date in 1..31) dates.add(date)
                    }
                    if (dates.isNotEmpty()) {
                        policy.scheduleDatesOfMonth = dates
                        plugin.policyManager.savePolicy(policyName(policy, policyName))
                        player.sendMessage(tr("messages.gui.msg_dates_updated", "&aDates updated."))
                    } else {
                        player.sendMessage(tr("messages.gui.msg_invalid_dates", "&cNo valid dates provided."))
                    }
                } catch (_: Exception) {
                    player.sendMessage(tr("messages.gui.msg_invalid_format", "&cInvalid format."))
                }
                openPolicyDetails(player, policyName(policy, policyName))
            }
        }
    }

    private fun tr(key: String, fallback: String): String {
        val value = plugin.langConfig.getString(key, fallback) ?: fallback
        return ChatColor.translateAlternateColorCodes('&', value)
    }

    private fun policyName(policy: TaxPolicy, fallback: String): String = policy.name ?: fallback
}
