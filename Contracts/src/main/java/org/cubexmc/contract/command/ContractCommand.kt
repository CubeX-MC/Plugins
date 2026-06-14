package org.cubexmc.contract.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.contract.model.PayoutRecipient
import org.cubexmc.contract.model.PayoutRule
import org.cubexmc.contract.service.ServiceResult
import org.cubexmc.contract.util.Text
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional

class ContractCommand(private val plugin: ContractPlugin) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player && sender.hasPermission("contract.use")) {
                plugin.gui().open(sender)
            } else {
                send(sender, plugin.lang().message("help"))
            }
            return true
        }

        if (args[0].equals("help", ignoreCase = true)) {
            send(sender, plugin.lang().message("help"))
            return true
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "service" -> service(sender, args)
            "wager" -> wager(sender, args)
            "resolve" -> resolve(sender, args)
            "mediate" -> mediate(sender, args)
            "partner" -> partner(sender, args)
            "gui" -> gui(sender)
            "list" -> list(sender, args, false)
            "all" -> list(sender, args, true)
            "my" -> my(sender)
            "rep" -> rep(sender, args)
            "info" -> info(sender, args)
            "accept" -> accept(sender, args)
            "submit" -> submit(sender, args)
            "approve" -> approve(sender, args)
            "cancel" -> cancel(sender, args)
            "dispute" -> dispute(sender, args)
            "withdraw" -> withdraw(sender, args)
            "admin" -> admin(sender, args)
            else -> {
                send(sender, plugin.lang().message("help"))
                true
            }
        }
    }

    private fun gui(sender: CommandSender): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.use")) {
            return true
        }
        plugin.gui().open(player)
        return true
    }

    private fun wager(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.create")) {
            return true
        }
        if (args.size < 6) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract wager <对方> <押注> <天> <仲裁者> <标题>|<描述>")))
            return true
        }
        val opponentName = args[1]
        val stake = parseDouble(args[2])
        val days = parseInt(args[3])
        val arbiterName = args[4]
        if (stake == null || days == null) {
            send(sender, plugin.lang().message("invalid-number"))
            return true
        }
        val text = parseContractText(args, 5, false)
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract wager <对方> <押注> <天> <仲裁者> <标题>|<描述>")))
            return true
        }
        val result = plugin.contracts().createWager(
            player,
            opponentName,
            BigDecimal.valueOf(stake),
            days,
            arbiterName,
            text.title(),
            text.description(),
        )
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", mapOf("reason" to result.reason())))
            return true
        }
        val contract = result.contract() ?: throw NullPointerException("contract")
        send(sender, plugin.lang().message("wager-create-success", mapOf(
            "id" to contract.shortId(),
            "opponent" to opponentName,
            "stake" to plugin.economy().format(result.amount()),
        )))
        return true
    }

    private fun partner(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.create")) {
            return true
        }
        if (args.size < 6) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract partner <对方> <我押注> <对方押注> <天> [--mediator <中间人>] <标题>|<描述>")))
            return true
        }
        val partnerName = args[1]
        val stakeA = parseDouble(args[2])
        val stakeB = parseDouble(args[3])
        val days = parseInt(args[4])
        if (stakeA == null || stakeB == null || days == null) {
            send(sender, plugin.lang().message("invalid-number"))
            return true
        }
        val text = parseContractText(args, 5, true)
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract partner <对方> <我押注> <对方押注> <天> [--mediator <中间人>] <标题>|<描述>")))
            return true
        }
        val result = plugin.contracts().createPartnership(
            player,
            partnerName,
            BigDecimal.valueOf(stakeA),
            BigDecimal.valueOf(stakeB),
            days,
            text.title(),
            text.description(),
            text.mediatorName(),
        )
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", mapOf("reason" to result.reason())))
            return true
        }
        val contract = result.contract() ?: throw NullPointerException("contract")
        send(sender, plugin.lang().message("partner-create-success", mapOf(
            "id" to contract.shortId(),
            "partner" to partnerName,
            "stake" to plugin.economy().format(result.amount()),
        )))
        return true
    }

    private fun resolve(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.use")) {
            return true
        }
        if (args.size < 3) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract resolve <id> <a|b>")))
            return true
        }
        val contract = plugin.storage().findByPrefix(args[1])
        if (contract.isEmpty) {
            send(sender, plugin.lang().message("not-found"))
            return true
        }
        val value = contract.get()
        val result = plugin.contracts().resolveWager(player, value, args[2])
        if (!result.success()) {
            sendFailure(sender, result)
            return true
        }
        send(sender, plugin.lang().message("resolve-success", mapOf(
            "id" to value.shortId(),
            "winner" to args[2].uppercase(Locale.ROOT),
        )))
        return true
    }

    private fun mediate(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.mediate")) {
            return true
        }
        if (args.size < 3) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract mediate <id> <accept|pay|refund|owner|contractor>")))
            return true
        }
        val contract = plugin.storage().findByPrefix(args[1])
        if (contract.isEmpty) {
            send(sender, plugin.lang().message("not-found"))
            return true
        }
        val value = contract.get()
        val result = plugin.contracts().mediate(player, value, args[2])
        if (!result.success()) {
            sendFailure(sender, result)
            return true
        }
        val successKey = if (args[2].equals("accept", ignoreCase = true)) "mediate-accept-success" else "mediate-success"
        send(sender, plugin.lang().message(successKey, mapOf(
            "id" to value.shortId(),
            "decision" to args[2].lowercase(Locale.ROOT),
        )))
        return true
    }

    private fun service(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.create")) {
            return true
        }
        if (args.size < 4) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract service <奖金> <天> [--mediator <中间人>] <标题>|<描述>")))
            return true
        }
        val reward = parseDouble(args[1])
        val days = parseInt(args[2])
        if (reward == null || days == null) {
            send(sender, plugin.lang().message("invalid-number"))
            return true
        }
        val text = parseContractText(args, 3, true)
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract service <奖金> <天> [--mediator <中间人>] <标题>|<描述>")))
            return true
        }
        val result = plugin.contracts().create(player, reward, days, text.title(), text.description(), text.mediatorName())
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", mapOf("reason" to result.reason())))
            return true
        }
        val contract = result.contract() ?: throw NullPointerException("contract")
        send(sender, plugin.lang().message("create-success", mapOf(
            "id" to contract.shortId(),
            "amount" to plugin.economy().format(result.amount()),
        )))
        return true
    }

    private fun list(sender: CommandSender, args: Array<String>, all: Boolean): Boolean {
        if (all && !sender.hasPermission("contract.admin.view")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        if (!sender.hasPermission("contract.use")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        val page = if (args.size >= 2) kotlin.math.max(1, parseInt(args[1], 1)) else 1
        val contracts = if (all) plugin.contracts().allContracts() else plugin.contracts().openContracts()
        sendContractList(sender, contracts, page)
        return true
    }

    private fun my(sender: CommandSender): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.use")) {
            return true
        }
        val contracts = plugin.contracts().allContracts().stream()
            .filter { contract -> contract.relatedTo(player.uniqueId) }
            .toList()
        send(sender, plugin.lang().message("my-header"))
        sendContractList(sender, contracts, 1)
        return true
    }

    private fun info(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("contract.use")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        val contract = findContract(sender, args)
        contract.ifPresent { value -> sendInfo(sender, value) }
        return true
    }

    private fun accept(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.accept")) {
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().accept(player, contract.get())
        sendResult(sender, result, "accept-success")
        return true
    }

    private fun submit(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.submit")) {
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().submit(player, contract.get())
        sendResult(sender, result, "submit-success")
        return true
    }

    private fun approve(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.approve")) {
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().approve(player, contract.get())
        if (!result.success()) {
            sendFailure(sender, result)
            return true
        }
        val resultContract = result.contract() ?: throw NullPointerException("contract")
        send(sender, plugin.lang().message("approve-success", mapOf(
            "player" to (resultContract.contractorName() ?: ""),
            "amount" to plugin.economy().format(result.amount()),
        )))
        return true
    }

    private fun cancel(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.cancel")) {
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().cancel(player, contract.get())
        sendResult(sender, result, "cancel-success")
        return true
    }

    private fun dispute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.dispute")) {
            return true
        }
        if (args.size < 3) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract dispute <id> <原因>")))
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().dispute(player, contract.get(), join(args, 2))
        sendResult(sender, result, "dispute-success")
        return true
    }

    private fun rep(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("contract.use")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        val record = if (args.size >= 2) {
            plugin.reputation().findByName(args[1])
        } else {
            val player = requirePlayer(sender) ?: return true
            plugin.reputation().snapshot(player.uniqueId)
        }
        val fallbackName = if (args.size >= 2) args[1] else sender.name
        if (record == null) {
            send(sender, Text.color("&#FFE066$fallbackName 暂无合同履约记录。"))
            return true
        }
        send(sender, Text.color("&#F4D03F${record.name.ifBlank { fallbackName }} 的履约档案:"))
        send(sender, Text.color("&#CFD8DC完成 &#69DB7C${record.completed} &#CFD8DC· 取消 &#FFE066${record.cancelled} &#CFD8DC· 逾期 &#E63946${record.expired} &#CFD8DC· 争议 &#E63946${record.disputed}"))
        return true
    }

    private fun withdraw(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender)
        if (player == null || !requirePermission(player, "contract.dispute")) {
            return true
        }
        val contract = findContract(sender, args)
        if (contract.isEmpty) {
            return true
        }
        val result = plugin.contracts().withdrawDispute(player, contract.get())
        sendResult(sender, result, "withdraw-success")
        return true
    }

    private fun admin(sender: CommandSender, args: Array<String>): Boolean {
        if (!sender.hasPermission("contract.admin")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        if (args.size < 2) {
            send(sender, Text.color("&#FFE066/contract admin reload|pay|refund|close <id>"))
            return true
        }
        val action = args[1].lowercase(Locale.ROOT)
        if (action == "reload") {
            if (!sender.hasPermission("contract.admin.reload")) {
                send(sender, plugin.lang().message("no-permission"))
                return true
            }
            plugin.reloadContracts()
            send(sender, plugin.lang().message("reloaded"))
            return true
        }
        if (!sender.hasPermission("contract.admin.settle")) {
            send(sender, plugin.lang().message("no-permission"))
            return true
        }
        if (args.size < 3) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract admin $action <id>")))
            return true
        }
        val contract = plugin.storage().findByPrefix(args[2])
        if (contract.isEmpty) {
            send(sender, plugin.lang().message("not-found"))
            return true
        }
        val value = contract.get()
        val result: ServiceResult
        val successKey: String
        when (action) {
            "pay" -> {
                result = plugin.contracts().adminPay(value, sender.name)
                successKey = "admin-pay-success"
            }
            "refund" -> {
                result = plugin.contracts().adminRefund(value, sender.name)
                successKey = "admin-refund-success"
            }
            "close" -> {
                result = plugin.contracts().adminClose(value, sender.name)
                successKey = "admin-close-success"
            }
            else -> {
                send(sender, Text.color("&#FFE066/contract admin reload|pay|refund|close <id>"))
                return true
            }
        }
        if (!result.success()) {
            sendFailure(sender, result)
            return true
        }
        send(sender, plugin.lang().message(successKey, mapOf("id" to value.shortId())))
        return true
    }

    private fun findContract(sender: CommandSender, args: Array<String>): Optional<Contract> {
        if (args.size < 2) {
            send(sender, plugin.lang().message("invalid-usage", mapOf("usage" to "/contract ${args[0]} <id>")))
            return Optional.empty()
        }
        val contract = plugin.storage().findByPrefix(args[1])
        if (contract.isEmpty) {
            send(sender, plugin.lang().message("not-found"))
        }
        return contract
    }

    private fun sendContractList(sender: CommandSender, contracts: List<Contract>, page: Int) {
        if (contracts.isEmpty()) {
            send(sender, plugin.lang().message("empty-list"))
            return
        }
        val pageSize = kotlin.math.max(1, plugin.config.getInt("display.page-size", 8))
        val pages = kotlin.math.max(1, kotlin.math.ceil(contracts.size.toDouble() / pageSize).toInt())
        val currentPage = kotlin.math.min(kotlin.math.max(1, page), pages)
        val start = (currentPage - 1) * pageSize
        val end = kotlin.math.min(start + pageSize, contracts.size)
        send(sender, plugin.lang().message("list-header", mapOf("page" to currentPage.toString(), "pages" to pages.toString())))
        for (index in start until end) {
            val contract = contracts[index]
            send(sender, plugin.lang().message("list-line", mapOf(
                "id" to contract.shortId(),
                "title" to contract.title(),
                "type" to plugin.lang().type(contract.type()),
                "status" to plugin.lang().status(contract.status()),
                "reward" to plugin.economy().format(contract.reward()),
                "owner" to (contract.ownerName() ?: ""),
            )))
        }
        send(sender, plugin.lang().message("list-footer"))
    }

    private fun sendInfo(sender: CommandSender, contract: Contract) {
        val privileged = isPrivilegedViewer(sender, contract)
        send(sender, plugin.lang().message("info", mapOf(
            "id" to contract.shortId(),
            "title" to contract.title(),
            "type" to plugin.lang().type(contract.type()),
            "status" to plugin.lang().status(contract.status()),
            "participants" to participantLines(contract, privileged),
            "arbiter" to arbiterLine(contract, privileged),
            "reward" to plugin.economy().format(contract.reward()),
            "payouts" to payoutLines(contract),
            "approval" to approvalLine(contract),
            "deadline" to DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt())),
            "description" to contract.description(),
        )))
        if (privileged && contract.status() == ContractStatus.DISPUTED && contract.disputeReason() != null) {
            send(sender, Text.color("&#E63946争议原因: &#F1F5F9${contract.disputeReason()}"))
        }
    }

    private fun isPrivilegedViewer(sender: CommandSender, contract: Contract): Boolean {
        if (sender.hasPermission("contract.admin.view")) {
            return true
        }
        if (sender !is Player) {
            return false
        }
        return contract.relatedTo(sender.uniqueId)
    }

    private fun participantLines(contract: Contract, privileged: Boolean): String {
        val lines = ArrayList<String>()
        for (participant in contract.participants()) {
            val name = visibleParticipantName(participant, privileged)
            val stake = participant.moneyStake()
            val stakeText =
                if (stake.signum() > 0) " &#CFD8DC${plugin.lang().term("stake", "Stake")}: &#69DB7C${plugin.economy().format(stake)}" else ""
            lines.add("    &#CFD8DC- &#FFE066${plugin.lang().role(participant.role())}&#CFD8DC: &#FFFFFF$name$stakeText")
        }
        return lines.joinToString("\n")
    }

    private fun visibleParticipantName(participant: Participant, privileged: Boolean): String {
        val displayName = participant.displayName()
        if (displayName.isNullOrBlank()) {
            return plugin.lang().term("unspecified", "Unspecified")
        }
        if (privileged) {
            return displayName
        }
        return when (participant.role()) {
            org.cubexmc.contract.model.ParticipantRole.CONTRACTOR,
            org.cubexmc.contract.model.ParticipantRole.PARTY_B,
            org.cubexmc.contract.model.ParticipantRole.PARTNER,
            org.cubexmc.contract.model.ParticipantRole.CLAIMER,
            org.cubexmc.contract.model.ParticipantRole.DEBTOR,
            -> plugin.lang().term("assigned", "Assigned")
            else -> displayName
        }
    }

    private fun arbiterLine(contract: Contract, privileged: Boolean): String {
        val arbiter = contract.arbiter() ?: return ""
        val name = if (privileged) arbiter.displayName() else plugin.lang().term("assigned", "Assigned")
        val accepted =
            if (contract.arbiterAccepted()) plugin.lang().term("mediator-accepted", "Mediator accepted") else plugin.lang().term("mediator-pending", "Mediator pending")
        return "    &#CFD8DC- &#FFE066${plugin.lang().role(arbiter.role())}&#CFD8DC: &#FFFFFF$name &#CFD8DC($accepted)\n"
    }

    private fun payoutLines(contract: Contract): String {
        val lines = ArrayList<String>()
        for (condition in PayoutCondition.entries) {
            val rules = contract.payoutsFor(condition)
            if (rules.isEmpty()) {
                continue
            }
            lines.add("    &#CFD8DC- &#FFE066${plugin.lang().condition(condition)}&#CFD8DC: ${summarizeRules(rules)}")
        }
        return if (lines.isEmpty()) "    &#CFD8DC- ${plugin.lang().term("none", "None")}" else lines.joinToString("\n")
    }

    private fun summarizeRules(rules: List<PayoutRule>): String {
        val parts = ArrayList<String>()
        for (rule in rules) {
            parts.add("${plugin.lang().role(rule.source())} ${rule.sharePercent().stripTrailingZeros().toPlainString()}% -> ${recipientLabel(rule.recipient())}")
        }
        return parts.joinToString(", ")
    }

    private fun recipientLabel(recipient: PayoutRecipient): String =
        when (recipient.kind()) {
            PayoutRecipient.Kind.PARTICIPANT -> plugin.lang().role(recipient.role() ?: throw NullPointerException("recipient.role"))
            PayoutRecipient.Kind.SYSTEM_SINK -> plugin.lang().term("system-sink", "System sink")
            PayoutRecipient.Kind.ARBITER -> plugin.lang().term("arbiter", "Arbiter")
        }

    private fun approvalLine(contract: Contract): String {
        val approved = contract.metadata["approved-roles"]
        if (approved.isNullOrBlank()) {
            return ""
        }
        return "&#FFE066${plugin.lang().term("approved-roles", "Approved roles")}: &#FFFFFF$approved\n"
    }

    private fun sendResult(sender: CommandSender, result: ServiceResult, successKey: String) {
        if (!result.success()) {
            sendFailure(sender, result)
            return
        }
        val contract = result.contract() ?: throw NullPointerException("contract")
        send(sender, plugin.lang().message(successKey, mapOf("id" to contract.shortId())))
    }

    private fun sendFailure(sender: CommandSender, result: ServiceResult) {
        send(sender, plugin.lang().message("operation-failed", mapOf("reason" to result.reason())))
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) {
            return sender
        }
        send(sender, plugin.lang().message("player-only"))
        return null
    }

    private fun requirePermission(player: Player, permission: String): Boolean {
        if (player.hasPermission(permission)) {
            return true
        }
        send(player, plugin.lang().message("no-permission"))
        return false
    }

    private fun send(sender: CommandSender, message: String) {
        for (line in message.split(Regex("\\R"))) {
            sender.sendMessage(line)
        }
    }

    private fun join(args: Array<String>, start: Int): String {
        val builder = StringBuilder()
        for (index in start until args.size) {
            if (builder.isNotEmpty()) {
                builder.append(' ')
            }
            builder.append(args[index])
        }
        return builder.toString()
    }

    private fun parseContractText(args: Array<String>, start: Int, allowMediator: Boolean): ContractText? {
        if (start >= args.size) {
            return null
        }
        var mediatorName: String? = null
        var textStart = start
        if (allowMediator && isMediatorFlag(args[start])) {
            if (start + 2 >= args.size) {
                return null
            }
            mediatorName = args[start + 1]
            textStart = start + 2
        }
        val rest = join(args, textStart)
        if (rest.isBlank()) {
            return null
        }
        val splitIndex = rest.indexOf('|')
        if (splitIndex < 0) {
            return ContractText(rest, "", mediatorName)
        }
        return ContractText(rest.substring(0, splitIndex), rest.substring(splitIndex + 1), mediatorName)
    }

    private fun isMediatorFlag(arg: String): Boolean = arg.equals("--mediator", ignoreCase = true) || arg.equals("-m", ignoreCase = true)

    private fun parseDouble(value: String): Double? =
        try {
            val parsed = value.toDouble()
            if (parsed.isFinite()) parsed else null
        } catch (ex: NumberFormatException) {
            null
        }

    private fun parseInt(value: String): Int? =
        try {
            value.toInt()
        } catch (ex: NumberFormatException) {
            null
        }

    private fun parseInt(value: String, fallback: Int): Int = parseInt(value) ?: fallback

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (args.size == 1) {
            return startsWith(listOf("gui", "service", "wager", "resolve", "mediate", "partner", "list", "all", "my", "rep", "info", "accept", "submit", "approve", "cancel", "dispute", "withdraw", "admin", "help"), args[0])
        }
        if (args.size == 4 && args[0].equals("service", ignoreCase = true)) {
            return startsWith(listOf("--mediator"), args[3])
        }
        if (args.size == 6 && args[0].equals("partner", ignoreCase = true)) {
            return startsWith(listOf("--mediator"), args[5])
        }
        if (args.size == 3 && args[0].equals("mediate", ignoreCase = true)) {
            return startsWith(listOf("accept", "pay", "refund", "owner", "contractor", "a", "b"), args[2])
        }
        if (args.size == 2 && args[0].equals("admin", ignoreCase = true)) {
            return startsWith(listOf("reload", "pay", "refund", "close"), args[1])
        }
        if ((args.size == 2 && listOf("info", "accept", "submit", "approve", "cancel", "dispute", "withdraw", "resolve", "mediate").contains(args[0].lowercase(Locale.ROOT))) ||
            (args.size == 3 && args[0].equals("admin", ignoreCase = true))
        ) {
            val prefix = args[args.size - 1].lowercase(Locale.ROOT)
            val adminView = sender.hasPermission("contract.admin.view")
            val viewerUuid = if (sender is Player) sender.uniqueId else null
            return plugin.contracts().allContracts().stream()
                .filter { contract -> adminView || contract.status() == ContractStatus.OPEN || viewerUuid != null && contract.relatedTo(viewerUuid) }
                .map { contract -> contract.shortId() }
                .filter { id -> id.lowercase(Locale.ROOT).startsWith(prefix) }
                .limit(20)
                .toList()
        }
        return listOf()
    }

    private fun startsWith(values: List<String>, prefix: String): List<String> {
        val lower = prefix.lowercase(Locale.ROOT)
        val result = ArrayList<String>()
        for (value in values) {
            if (value.startsWith(lower)) {
                result.add(value)
            }
        }
        return result
    }

    private class ContractText(
        private val title: String,
        private val description: String,
        private val mediatorName: String?,
    ) {
        fun title(): String = title
        fun description(): String = description
        fun mediatorName(): String? = mediatorName
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
    }
}
