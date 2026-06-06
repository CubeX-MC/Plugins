package org.cubexmc.ecobalancer.commands

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.cubexmc.ecobalancer.EcoBalancer
import org.cubexmc.ecobalancer.policies.TaxPolicy
import org.cubexmc.ecobalancer.tax.TaxLedgerService
import org.cubexmc.ecobalancer.tax.TaxRunState
import org.cubexmc.ecobalancer.utils.EconomicMetrics
import org.cubexmc.ecobalancer.utils.SchedulerUtils
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Command handler for /eb tax - allows configuration of the ACTIVE policy.
 */
class TaxCommand(private val plugin: EcoBalancer) : CommandExecutor {
    private var hasUnsavedChanges = false

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("ecobalancer.command.tax")) {
            sender.sendMessage(plugin.getFormattedMessage("messages.no_permission", null))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val subCommand = args[0].lowercase(Locale.getDefault())
        val subArgs = args.copyOfRange(1, args.size)

        return when (subCommand) {
            "policy" -> handlePolicy(sender, subArgs)
            "show" -> {
                showCurrentConfig(sender)
                true
            }
            "schedule" -> handleSchedule(sender, getPolicy(sender), subArgs)
            "time" -> handleTime(sender, getPolicy(sender), subArgs)
            "days" -> handleDays(sender, getPolicy(sender), subArgs)
            "dates" -> handleDates(sender, getPolicy(sender), subArgs)
            "inactive" -> handleInactive(sender, getPolicy(sender), subArgs)
            "clear" -> handleClear(sender, getPolicy(sender), subArgs)
            "bracket" -> handleBracket(sender, getPolicy(sender), subArgs)
            "mode" -> handleMode(sender, getPolicy(sender), subArgs)
            "filter" -> handleFilter(sender, subArgs)
            "account" -> handleAccount(sender, subArgs)
            "debt" -> handleDebt(sender, getPolicy(sender), subArgs)
            "status" -> handleStatus(sender)
            "fund" -> handleFund(sender)
            "stats" -> handleTaxStats(sender, subArgs)
            "save" -> handleSave(sender)
            "reload" -> {
                plugin.policyManager.loadPolicies()
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.reload_policies", null))
                true
            }
            else -> {
                sendUsage(sender)
                true
            }
        }
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.usage", null))
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_usage", null))
    }

    private fun handlePolicy(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_usage", null))
            return true
        }

        when (val action = args[0].lowercase(Locale.getDefault())) {
            "list" -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_list_header", null))
                val active = plugin.policyManager.getActivePolicy()
                for (name in plugin.policyManager.getPolicyNames()) {
                    val ph = hashMapOf("name" to name)
                    if (active != null && active.name == name) {
                        sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_item_active", ph))
                    } else {
                        sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_item_inactive", ph))
                    }
                }
            }
            "set" -> {
                if (args.size < 2) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_set_usage", null))
                    return true
                }
                val name = args[1]
                if (plugin.policyManager.getPolicyNames().contains(name)) {
                    plugin.policyManager.setActivePolicy(name)
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_set_success", mapOf("name" to name)))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to name)))
                }
            }
            "info" -> {
                if (args.size < 2) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_info_usage", null))
                    return true
                }
                val name = args[1]
                val policy = plugin.policyManager.getPolicy(name)
                if (policy != null) {
                    showPolicyInfo(sender, policy)
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to name)))
                }
            }
            "edit" -> {
                if (args.size < 3) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_edit_usage", null))
                    return true
                }
                val name = args[1]
                val policy = plugin.policyManager.getPolicy(name)
                if (policy != null) {
                    val property = args[2].lowercase(Locale.getDefault())
                    val editArgs = args.copyOfRange(3, args.size)
                    handlePolicyEdit(sender, policy, property, editArgs)
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to name)))
                }
            }
            "execute" -> {
                if (args.size < 2) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_execute_usage", null))
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_execute_hint", null))
                    return true
                }
                val name = args[1]
                if (plugin.policyManager.getPolicyNames().contains(name)) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_execute_start", mapOf("name" to name)))
                    plugin.executePolicy(sender, name)
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to name)))
                }
            }
            "create" -> {
                if (args.size < 2) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_create_usage", null))
                    return true
                }
                val name = args[1]
                if (plugin.policyManager.createPolicy(name)) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_create_success", mapOf("name" to name)))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_already_exists", mapOf("name" to name)))
                }
            }
            "delete" -> {
                if (args.size < 2) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_delete_usage", null))
                    return true
                }
                val name = args[1]
                if (plugin.policyManager.deletePolicy(name)) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_delete_success", mapOf("name" to name)))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.policy_not_found", mapOf("name" to name)))
                }
            }
            "clone" -> {
                if (args.size < 3) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_clone_usage", null))
                    return true
                }
                val source = args[1]
                val target = args[2]
                if (plugin.policyManager.clonePolicy(source, target)) {
                    val ph = hashMapOf("source" to source, "target" to target)
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_clone_success", ph))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_clone_failed", null))
                }
            }
            "rename" -> {
                if (args.size < 3) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_rename_usage", null))
                    return true
                }
                val oldName = args[1]
                val newName = args[2]
                if (plugin.policyManager.renamePolicy(oldName, newName)) {
                    val ph = hashMapOf("old" to oldName, "new" to newName)
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_rename_success", ph))
                } else {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.policy_rename_failed", null))
                }
            }
            else -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.unknown_action", null))
            }
        }
        return true
    }

    private fun handlePolicyEdit(sender: CommandSender, policy: TaxPolicy, property: String, args: Array<String>): Boolean =
        when (property) {
            "schedule" -> handleSchedule(sender, policy, args)
            "time" -> handleTime(sender, policy, args)
            "days" -> handleDays(sender, policy, args)
            "dates" -> handleDates(sender, policy, args)
            "inactive" -> handleInactive(sender, policy, args)
            "clear" -> handleClear(sender, policy, args)
            "bracket" -> handleBracket(sender, policy, args)
            "mode" -> handleMode(sender, policy, args)
            "debt" -> handleDebt(sender, policy, args)
            else -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.unknown_action", null))
                true
            }
        }

    private fun showCurrentConfig(sender: CommandSender) {
        val policy = plugin.policyManager.getActivePolicy()
        if (policy == null) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.no_active_policy_selected", null))
            return
        }
        showPolicyInfo(sender, policy)
    }

    private fun showPolicyInfo(sender: CommandSender, policy: TaxPolicy) {
        val ph = HashMap<String, String>()

        sender.sendMessage(plugin.getFormattedMessage("messages.tax.show_header", null))
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.active_policy_line", mapOf("name" to (policy.name ?: ""))))

        ph["type"] = policy.scheduleType ?: ""
        ph["time"] = policy.checkTime
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.schedule_line", ph))

        if ("weekly".equals(policy.scheduleType, ignoreCase = true)) {
            ph.clear()
            ph["days"] = formatDaysOfWeek(policy.scheduleDaysOfWeek)
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.days_of_week_line", ph))
        }

        if ("monthly".equals(policy.scheduleType, ignoreCase = true)) {
            ph.clear()
            ph["dates"] = policy.scheduleDatesOfMonth.joinToString(", ")
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.dates_of_month_line", ph))
        }

        ph.clear()
        ph["days"] = policy.inactiveDaysToDeduct.toString()
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.inactive_deduct_line", ph))

        ph["days"] = policy.inactiveDaysToClear.toString()
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.inactive_clear_line", ph))

        ph.clear()
        ph["mode"] = if (policy.isPercentileThresholds) "percentile" else "absolute"
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.mode_line", ph))

        ph.clear()
        ph["status"] = if (plugin.isTaxAccountEnabled) "§a✓" else "§c✗"
        ph["name"] = plugin.taxAccountName ?: "(not set)"
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_line", ph))

        ph.clear()
        ph["mode"] = policy.debtMode ?: "inherit"
        ph["global"] = plugin.config.getString("debt-mode", "skip") ?: "skip"
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.debt_line", ph))

        ph.clear()
        val exempt = policy.exemptPermission
        ph["permission"] = if (exempt == null || exempt.trim().isEmpty()) "(global/default)" else exempt
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.exempt_line", ph))

        sender.sendMessage(plugin.getFormattedMessage("messages.tax.brackets_header", null))
        val brackets = policy.taxBrackets.toMutableList()
        if (brackets.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.brackets_empty", null))
        } else {
            brackets.sortBy { (it["threshold"] as Number).toInt() }
            for (entry in brackets) {
                ph.clear()
                val threshold = (entry["threshold"] as Number).toInt()
                ph["threshold"] = formatThreshold(threshold)
                val rate = (entry["rate"] as Number).toDouble()
                ph["rate"] = String.format("%.2f%%", rate * 100)
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_line", ph))
            }
        }

        val activePolicy = plugin.policyManager.getActivePolicy()
        if (hasUnsavedChanges && activePolicy != null && activePolicy.name == policy.name) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.unsaved_changes", mapOf("policy" to (policy.name ?: ""))))
        }
    }

    private fun formatDaysOfWeek(days: List<Int>): String {
        val dayNames = arrayOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        return days.filter { it in 1..7 }
            .joinToString(", ") { dayNames[it] }
    }

    private fun formatThreshold(threshold: Int): String =
        when {
            threshold >= 1_000_000_000 -> String.format("%.1fB", threshold / 1_000_000_000.0)
            threshold >= 1_000_000 -> String.format("%.1fM", threshold / 1_000_000.0)
            threshold >= 1_000 -> String.format("%.1fK", threshold / 1_000.0)
            else -> threshold.toString()
        }

    private fun getPolicy(sender: CommandSender): TaxPolicy? {
        val policy = plugin.policyManager.getActivePolicy()
        if (policy == null) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.no_active_policy_found", null))
        }
        return policy
    }

    private fun handleSchedule(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.schedule_usage", null))
            return true
        }

        val type = args[0].lowercase(Locale.getDefault())
        if (type != "daily" && type != "weekly" && type != "monthly") {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_schedule_type", null))
            return true
        }

        policy.scheduleType = type
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_schedule", mapOf("type" to type)))
        return true
    }

    private fun handleTime(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.time_usage", null))
            return true
        }

        val time = args[0]
        if (!time.matches(Regex("\\d{1,2}:\\d{2}"))) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_time", null))
            return true
        }

        val parts = time.split(":")
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_time", null))
            return true
        }

        val normalizedTime = String.format("%02d:%02d", hour, minute)
        policy.checkTime = normalizedTime
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_time", mapOf("time" to normalizedTime)))
        return true
    }

    private fun handleDays(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.days_usage", null))
            return true
        }

        val days = ArrayList<Int>()
        for (arg in args) {
            try {
                val day = arg.toInt()
                if (day < 1 || day > 7) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_days", null))
                    return true
                }
                if (!days.contains(day)) days.add(day)
            } catch (exception: NumberFormatException) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_days", null))
                return true
            }
        }
        Collections.sort(days)
        policy.scheduleDaysOfWeek = days
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_days", mapOf("days" to formatDaysOfWeek(days))))
        return true
    }

    private fun handleDates(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.dates_usage", null))
            return true
        }

        val dates = ArrayList<Int>()
        for (arg in args) {
            try {
                val date = arg.toInt()
                if (date < 1 || date > 31) {
                    sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_dates", null))
                    return true
                }
                if (!dates.contains(date)) dates.add(date)
            } catch (exception: NumberFormatException) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_dates", null))
                return true
            }
        }
        Collections.sort(dates)
        policy.scheduleDatesOfMonth = dates
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_dates", mapOf("dates" to dates.joinToString(", "))))
        return true
    }

    private fun handleInactive(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.inactive_usage", null))
            return true
        }
        try {
            val days = args[0].toInt()
            if (days < 0) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_days_positive", null))
                return true
            }
            policy.inactiveDaysToDeduct = days
            hasUnsavedChanges = true
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_inactive_deduct", mapOf("days" to days.toString())))
        } catch (exception: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_number", null))
        }
        return true
    }

    private fun handleClear(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.clear_usage", null))
            return true
        }
        try {
            val days = args[0].toInt()
            if (days < 0) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_days_positive", null))
                return true
            }
            policy.inactiveDaysToClear = days
            hasUnsavedChanges = true
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_inactive_clear", mapOf("days" to days.toString())))
        } catch (exception: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_number", null))
        }
        return true
    }

    private fun handleBracket(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_usage", null))
            return true
        }
        val action = args[0].lowercase(Locale.getDefault())
        val actionArgs = args.copyOfRange(1, args.size)
        return when (action) {
            "add" -> handleBracketAdd(sender, policy, actionArgs)
            "remove" -> handleBracketRemove(sender, policy, actionArgs)
            "list" -> handleBracketList(sender, policy)
            "clear" -> handleBracketClear(sender, policy)
            else -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_usage", null))
                true
            }
        }
    }

    private fun handleBracketAdd(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.size < 2) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_add_usage", null))
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_add_example", null))
            return true
        }
        try {
            val threshold = parseThreshold(args[0])
            val rate = args[1].toDouble()
            if (threshold <= 0 || rate < 0 || rate > 1) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_threshold_or_rate", null))
                return true
            }

            val brackets = policy.taxBrackets.toMutableList()
            brackets.removeIf { entry -> (entry["threshold"] as Number).toInt() == threshold }
            val newBracket = HashMap<String, Any>()
            newBracket["threshold"] = threshold
            newBracket["rate"] = rate
            brackets.add(newBracket)
            policy.taxBrackets = brackets

            hasUnsavedChanges = true
            val ph = hashMapOf(
                "threshold" to formatThreshold(threshold),
                "rate" to String.format("%.2f%%", rate * 100),
            )
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_added", ph))
        } catch (exception: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_number_format", null))
        }
        return true
    }

    private fun parseThreshold(inputValue: String): Int {
        var input = inputValue.uppercase(Locale.getDefault()).trim()
        var multiplier = 1.0
        if (input.endsWith("K")) {
            multiplier = 1_000.0
            input = input.substring(0, input.length - 1)
        } else if (input.endsWith("M")) {
            multiplier = 1_000_000.0
            input = input.substring(0, input.length - 1)
        } else if (input.endsWith("B")) {
            multiplier = 1_000_000_000.0
            input = input.substring(0, input.length - 1)
        }
        return (input.toDouble() * multiplier).toInt()
    }

    private fun handleBracketRemove(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_remove_usage", null))
            return true
        }
        try {
            val threshold = parseThreshold(args[0])
            val brackets = policy.taxBrackets.toMutableList()
            val removed = brackets.removeIf { entry -> (entry["threshold"] as Number).toInt() == threshold }
            if (!removed) {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_bracket_not_found", mapOf("threshold" to formatThreshold(threshold))))
                return true
            }
            policy.taxBrackets = brackets
            hasUnsavedChanges = true
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.bracket_removed", mapOf("threshold" to formatThreshold(threshold))))
        } catch (exception: NumberFormatException) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_threshold_format", null))
        }
        return true
    }

    private fun handleBracketList(sender: CommandSender, policy: TaxPolicy?): Boolean {
        if (policy != null) {
            showPolicyInfo(sender, policy)
        }
        return true
    }

    private fun handleBracketClear(sender: CommandSender, policy: TaxPolicy?): Boolean {
        if (policy == null) return true
        policy.taxBrackets = ArrayList()
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.brackets_cleared", null))
        return true
    }

    private fun handleMode(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.mode_usage", null))
            return true
        }
        val mode = args[0].lowercase(Locale.getDefault())
        if (mode != "absolute" && mode != "percentile") {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_mode", null))
            return true
        }
        policy.isPercentileThresholds = mode == "percentile"
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_mode", mapOf("mode" to mode)))
        return true
    }

    private fun handleDebt(sender: CommandSender, policy: TaxPolicy?, args: Array<String>): Boolean {
        if (policy == null) return true
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.debt_usage", null))
            return true
        }
        val mode = args[0].lowercase(Locale.ROOT)
        if (mode != "inherit" && mode != "skip" && mode != "drain" && mode != "allow-negative") {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_invalid_debt_mode", null))
            return true
        }
        policy.debtMode = mode
        hasUnsavedChanges = true
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_debt_mode", mapOf("mode" to mode)))
        return true
    }

    private fun handleStatus(sender: CommandSender): Boolean {
        val state: TaxRunState? = plugin.taxRunService?.state
        if (state == null || !state.isRunning) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.status_idle", null))
            return true
        }
        val ph = hashMapOf(
            "operation_id" to state.operationId.toString(),
            "policy" to (state.policyName ?: ""),
            "processed" to state.processedPlayers.toString(),
            "total" to state.totalPlayers.toString(),
            "affected" to state.affectedPlayers.toString(),
            "deducted" to EconomicMetrics.formatLargeNumber(state.totalDeducted),
            "trigger" to (state.trigger?.configKey ?: "unknown"),
            "sender" to (state.senderName ?: ""),
        )
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.status_running", ph))
        return true
    }

    private fun handleFund(sender: CommandSender): Boolean {
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.fund_loading", null))
        SchedulerUtils.runTaskAsync(plugin) {
            val stats = plugin.taxLedgerService.getServerStats()
            SchedulerUtils.runTask(plugin) {
                val ph = hashMapOf(
                    "balance" to EconomicMetrics.formatLargeNumber(stats.taxFundBalance),
                    "total" to EconomicMetrics.formatLargeNumber(stats.totalTaxCollected),
                    "latest" to EconomicMetrics.formatLargeNumber(stats.latestTaxCollected),
                    "operation_id" to stats.latestOperationId.toString(),
                    "vault_balance" to if (plugin.isTaxAccountEnabled) plugin.taxAccountBalance else "disabled",
                )
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.fund_summary", ph))
                sendFundHealth(sender, stats)
            }
        }
        return true
    }

    private fun sendFundHealth(sender: CommandSender, stats: TaxLedgerService.ServerTaxStats) {
        if (!plugin.isTaxAccountEnabled) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.fund_health_disabled", null))
            return
        }
        val vaultBalance = plugin.taxAccountBalanceValue
        val delta = vaultBalance - stats.taxFundBalance
        if (kotlin.math.abs(delta) <= 0.01) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.fund_health_ok", null))
            return
        }
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.fund_health_mismatch", mapOf("delta" to EconomicMetrics.formatLargeNumber(delta))))
    }

    private fun handleTaxStats(sender: CommandSender, args: Array<String>): Boolean {
        val targetName = if (args.isNotEmpty()) args[0] else sender.name
        val target: OfflinePlayer = Bukkit.getOfflinePlayer(targetName)
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.stats_loading", null))
        SchedulerUtils.runTaskAsync(plugin) {
            val stats = plugin.taxLedgerService.getPlayerStats(target)
            SchedulerUtils.runTask(plugin) {
                val time = if (stats.latestTaxTime <= 0) {
                    "never"
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(stats.latestTaxTime))
                }
                val ph = hashMapOf(
                    "player" to stats.playerName,
                    "latest" to EconomicMetrics.formatLargeNumber(stats.latestTaxPaid),
                    "total" to EconomicMetrics.formatLargeNumber(stats.totalTaxPaid),
                    "time" to time,
                )
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.stats_summary", ph))
            }
        }
        return true
    }

    private fun handleFilter(sender: CommandSender, args: Array<String>): Boolean {
        val filter = args.joinToString(" ")
        plugin.config.set("tax-filters", filter)
        val ph = hashMapOf("filter" to if (filter.isEmpty()) "(cleared)" else filter)
        sender.sendMessage(plugin.getFormattedMessage("messages.tax.set_filter", ph))
        return true
    }

    private fun handleAccount(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_usage", null))
            return true
        }
        when (val action = args[0].lowercase(Locale.getDefault())) {
            "enable" -> {
                plugin.isTaxAccountEnabled = true
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_enabled", null))
            }
            "disable" -> {
                plugin.isTaxAccountEnabled = false
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_disabled", null))
            }
            "name" -> {
                if (args.size < 2) return true
                val name = args[1]
                plugin.taxAccountName = name
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_name_set", mapOf("name" to name)))
            }
            else -> {
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.account_usage", null))
            }
        }
        plugin.saveCurrentConfiguration()
        return true
    }

    private fun handleSave(sender: CommandSender): Boolean {
        if (!hasUnsavedChanges) {
            sender.sendMessage(plugin.getFormattedMessage("messages.tax.error_no_changes", null))
            return true
        }

        try {
            val policy = plugin.policyManager.getActivePolicy()
            if (policy != null) {
                plugin.policyManager.savePolicy(policy.name ?: return true)
                hasUnsavedChanges = false
                sender.sendMessage(plugin.getFormattedMessage("messages.tax.saved", null))
            }
        } catch (exception: Exception) {
            sender.sendMessage(
                plugin.getFormattedMessage(
                    "messages.tax.error_save_failed",
                    mapOf("error" to (exception.message ?: "unknown")),
                ),
            )
        }
        return true
    }
}
