package org.cubexmc.contract.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.gui.ContractGui;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.Participant;
import org.cubexmc.contract.model.PayoutCondition;
import org.cubexmc.contract.model.PayoutRecipient;
import org.cubexmc.contract.model.PayoutRule;
import org.cubexmc.contract.service.ServiceResult;
import org.cubexmc.contract.util.Text;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class ContractCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault());

    private final ContractPlugin plugin;

    public ContractCommand(ContractPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player && player.hasPermission("contract.use")) {
                plugin.gui().openHub(player);
            } else {
                send(sender, plugin.lang().message("help"));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("help")) {
            send(sender, plugin.lang().message("help"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "service":
                return service(sender, args);
            case "wager":
                return wager(sender, args);
            case "resolve":
                return resolve(sender, args);
            case "mediate":
                return mediate(sender, args);
            case "partner":
                return partner(sender, args);
            case "gui":
                return gui(sender);
            case "list":
                return list(sender, args, false);
            case "all":
                return list(sender, args, true);
            case "my":
                return my(sender);
            case "info":
                return info(sender, args);
            case "accept":
                return accept(sender, args);
            case "submit":
                return submit(sender, args);
            case "approve":
                return approve(sender, args);
            case "cancel":
                return cancel(sender, args);
            case "dispute":
                return dispute(sender, args);
            case "admin":
                return admin(sender, args);
            default:
                send(sender, plugin.lang().message("help"));
                return true;
        }
    }

    private boolean gui(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.use")) {
            return true;
        }
        plugin.gui().openHub(player);
        return true;
    }

    private boolean wager(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.create")) {
            return true;
        }
        if (args.length < 6) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract wager <对方> <押注> <小时> <仲裁者> <标题>|<描述>")));
            return true;
        }
        String opponentName = args[1];
        Double stake = parseDouble(args[2]);
        Integer hours = parseInt(args[3]);
        String arbiterName = args[4];
        if (stake == null || hours == null) {
            send(sender, plugin.lang().message("invalid-number"));
            return true;
        }
        ContractText text = parseContractText(args, 5, false);
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract wager <对方> <押注> <小时> <仲裁者> <标题>|<描述>")));
            return true;
        }
        ServiceResult result = plugin.contracts().createWager(
            player, opponentName, java.math.BigDecimal.valueOf(stake), hours, arbiterName, text.title(), text.description());
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", Map.of("reason", result.reason())));
            return true;
        }
        send(sender, plugin.lang().message("wager-create-success", Map.of(
            "id", result.contract().shortId(),
            "opponent", opponentName,
            "stake", plugin.economy().format(result.amount())
        )));
        return true;
    }

    private boolean partner(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.create")) {
            return true;
        }
        if (args.length < 6) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract partner <对方> <我押注> <对方押注> <小时> [--mediator <中间人>] <标题>|<描述>")));
            return true;
        }
        String partnerName = args[1];
        Double stakeA = parseDouble(args[2]);
        Double stakeB = parseDouble(args[3]);
        Integer hours = parseInt(args[4]);
        if (stakeA == null || stakeB == null || hours == null) {
            send(sender, plugin.lang().message("invalid-number"));
            return true;
        }
        ContractText text = parseContractText(args, 5, true);
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract partner <对方> <我押注> <对方押注> <小时> [--mediator <中间人>] <标题>|<描述>")));
            return true;
        }
        ServiceResult result = plugin.contracts().createPartnership(
            player, partnerName,
            java.math.BigDecimal.valueOf(stakeA),
            java.math.BigDecimal.valueOf(stakeB),
            hours, text.title(), text.description(), text.mediatorName());
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", Map.of("reason", result.reason())));
            return true;
        }
        send(sender, plugin.lang().message("partner-create-success", Map.of(
            "id", result.contract().shortId(),
            "partner", partnerName,
            "stake", plugin.economy().format(result.amount())
        )));
        return true;
    }

    private boolean resolve(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.use")) {
            return true;
        }
        if (args.length < 3) {
            send(sender, plugin.lang().message("invalid-usage", Map.of("usage", "/contract resolve <id> <a|b>")));
            return true;
        }
        Optional<Contract> contract = plugin.storage().findByPrefix(args[1]);
        if (contract.isEmpty()) {
            send(sender, plugin.lang().message("not-found"));
            return true;
        }
        ServiceResult result = plugin.contracts().resolveWager(player, contract.get(), args[2]);
        if (!result.success()) {
            sendFailure(sender, result);
            return true;
        }
        send(sender, plugin.lang().message("resolve-success", Map.of(
            "id", contract.get().shortId(),
            "winner", args[2].toUpperCase(Locale.ROOT)
        )));
        return true;
    }

    private boolean mediate(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.mediate")) {
            return true;
        }
        if (args.length < 3) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract mediate <id> <accept|pay|refund|owner|contractor>")));
            return true;
        }
        Optional<Contract> contract = plugin.storage().findByPrefix(args[1]);
        if (contract.isEmpty()) {
            send(sender, plugin.lang().message("not-found"));
            return true;
        }
        ServiceResult result = plugin.contracts().mediate(player, contract.get(), args[2]);
        if (!result.success()) {
            sendFailure(sender, result);
            return true;
        }
        String successKey = args[2].equalsIgnoreCase("accept") ? "mediate-accept-success" : "mediate-success";
        send(sender, plugin.lang().message(successKey, Map.of(
            "id", contract.get().shortId(),
            "decision", args[2].toLowerCase(Locale.ROOT)
        )));
        return true;
    }

    private boolean service(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.create")) {
            return true;
        }
        if (args.length < 4) {
            send(sender, plugin.lang().message("invalid-usage", Map.of("usage", "/contract service <奖金> <小时> [--mediator <中间人>] <标题>|<描述>")));
            return true;
        }
        Double reward = parseDouble(args[1]);
        Integer hours = parseInt(args[2]);
        if (reward == null || hours == null) {
            send(sender, plugin.lang().message("invalid-number"));
            return true;
        }
        ContractText text = parseContractText(args, 3, true);
        if (text == null) {
            send(sender, plugin.lang().message("invalid-usage", Map.of(
                "usage", "/contract service <奖金> <小时> [--mediator <中间人>] <标题>|<描述>")));
            return true;
        }
        ServiceResult result = plugin.contracts().create(player, reward, hours, text.title(), text.description(), text.mediatorName());
        if (!result.success()) {
            send(sender, plugin.lang().message("cannot-create", Map.of("reason", result.reason())));
            return true;
        }
        send(sender, plugin.lang().message("create-success", Map.of(
            "id", result.contract().shortId(),
            "amount", plugin.economy().format(result.amount())
        )));
        return true;
    }

    private boolean list(CommandSender sender, String[] args, boolean all) {
        if (all && !sender.hasPermission("contract.admin.view")) {
            send(sender, plugin.lang().message("no-permission"));
            return true;
        }
        if (!sender.hasPermission("contract.use")) {
            send(sender, plugin.lang().message("no-permission"));
            return true;
        }
        int page = args.length >= 2 ? Math.max(1, parseInt(args[1], 1)) : 1;
        List<Contract> contracts = all ? plugin.contracts().allContracts() : plugin.contracts().openContracts();
        sendContractList(sender, contracts, page);
        return true;
    }

    private boolean my(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.use")) {
            return true;
        }
        List<Contract> contracts = plugin.contracts().allContracts().stream()
            .filter(contract -> contract.relatedTo(player.getUniqueId()))
            .toList();
        send(sender, plugin.lang().message("my-header"));
        sendContractList(sender, contracts, 1);
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (!sender.hasPermission("contract.use")) {
            send(sender, plugin.lang().message("no-permission"));
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        contract.ifPresent(value -> sendInfo(sender, value));
        return true;
    }

    private boolean accept(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.accept")) {
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        if (contract.isEmpty()) {
            return true;
        }
        ServiceResult result = plugin.contracts().accept(player, contract.get());
        sendResult(sender, result, "accept-success");
        return true;
    }

    private boolean submit(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.submit")) {
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        if (contract.isEmpty()) {
            return true;
        }
        ServiceResult result = plugin.contracts().submit(player, contract.get());
        sendResult(sender, result, "submit-success");
        return true;
    }

    private boolean approve(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.approve")) {
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        if (contract.isEmpty()) {
            return true;
        }
        ServiceResult result = plugin.contracts().approve(player, contract.get());
        if (!result.success()) {
            sendFailure(sender, result);
            return true;
        }
        send(sender, plugin.lang().message("approve-success", Map.of(
            "player", result.contract().contractorName(),
            "amount", plugin.economy().format(result.amount())
        )));
        return true;
    }

    private boolean cancel(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.cancel")) {
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        if (contract.isEmpty()) {
            return true;
        }
        ServiceResult result = plugin.contracts().cancel(player, contract.get());
        sendResult(sender, result, "cancel-success");
        return true;
    }

    private boolean dispute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null || !requirePermission(player, "contract.dispute")) {
            return true;
        }
        if (args.length < 3) {
            send(sender, plugin.lang().message("invalid-usage", Map.of("usage", "/contract dispute <id> <原因>")));
            return true;
        }
        Optional<Contract> contract = findContract(sender, args);
        if (contract.isEmpty()) {
            return true;
        }
        ServiceResult result = plugin.contracts().dispute(player, contract.get(), join(args, 2));
        sendResult(sender, result, "dispute-success");
        return true;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("contract.admin")) {
            send(sender, plugin.lang().message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            send(sender, Text.color("&#FFE066/contract admin reload|pay|refund|close <id>"));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("reload")) {
            if (!sender.hasPermission("contract.admin.reload")) {
                send(sender, plugin.lang().message("no-permission"));
                return true;
            }
            plugin.reloadContracts();
            send(sender, plugin.lang().message("reloaded"));
            return true;
        }
        if (!sender.hasPermission("contract.admin.settle")) {
            send(sender, plugin.lang().message("no-permission"));
            return true;
        }
        if (args.length < 3) {
            send(sender, plugin.lang().message("invalid-usage", Map.of("usage", "/contract admin " + action + " <id>")));
            return true;
        }
        Optional<Contract> contract = plugin.storage().findByPrefix(args[2]);
        if (contract.isEmpty()) {
            send(sender, plugin.lang().message("not-found"));
            return true;
        }
        ServiceResult result;
        String successKey;
        switch (action) {
            case "pay" -> {
                result = plugin.contracts().adminPay(contract.get(), sender.getName());
                successKey = "admin-pay-success";
            }
            case "refund" -> {
                result = plugin.contracts().adminRefund(contract.get(), sender.getName());
                successKey = "admin-refund-success";
            }
            case "close" -> {
                result = plugin.contracts().adminClose(contract.get(), sender.getName());
                successKey = "admin-close-success";
            }
            default -> {
                send(sender, Text.color("&#FFE066/contract admin reload|pay|refund|close <id>"));
                return true;
            }
        }
        if (!result.success()) {
            sendFailure(sender, result);
            return true;
        }
        send(sender, plugin.lang().message(successKey, Map.of("id", contract.get().shortId())));
        return true;
    }

    private Optional<Contract> findContract(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, plugin.lang().message("invalid-usage", Map.of("usage", "/contract " + args[0] + " <id>")));
            return Optional.empty();
        }
        Optional<Contract> contract = plugin.storage().findByPrefix(args[1]);
        if (contract.isEmpty()) {
            send(sender, plugin.lang().message("not-found"));
        }
        return contract;
    }

    private void sendContractList(CommandSender sender, List<Contract> contracts, int page) {
        if (contracts.isEmpty()) {
            send(sender, plugin.lang().message("empty-list"));
            return;
        }
        int pageSize = Math.max(1, plugin.getConfig().getInt("display.page-size", 8));
        int pages = Math.max(1, (int) Math.ceil((double) contracts.size() / pageSize));
        int currentPage = Math.min(Math.max(1, page), pages);
        int start = (currentPage - 1) * pageSize;
        int end = Math.min(start + pageSize, contracts.size());
        send(sender, plugin.lang().message("list-header", Map.of(
            "page", String.valueOf(currentPage),
            "pages", String.valueOf(pages)
        )));
        for (int index = start; index < end; index++) {
            Contract contract = contracts.get(index);
            send(sender, plugin.lang().message("list-line", Map.of(
                "id", contract.shortId(),
                "title", contract.title(),
                "type", plugin.lang().type(contract.type()),
                "status", plugin.lang().status(contract.status()),
                "reward", plugin.economy().format(contract.reward()),
                "owner", contract.ownerName()
            )));
        }
        send(sender, plugin.lang().message("list-footer"));
    }

    private void sendInfo(CommandSender sender, Contract contract) {
        boolean privileged = isPrivilegedViewer(sender, contract);
        send(sender, plugin.lang().message("info", Map.ofEntries(
            Map.entry("id", contract.shortId()),
            Map.entry("title", contract.title()),
            Map.entry("type", plugin.lang().type(contract.type())),
            Map.entry("status", plugin.lang().status(contract.status())),
            Map.entry("participants", participantLines(contract, privileged)),
            Map.entry("arbiter", arbiterLine(contract, privileged)),
            Map.entry("reward", plugin.economy().format(contract.reward())),
            Map.entry("payouts", payoutLines(contract)),
            Map.entry("approval", approvalLine(contract)),
            Map.entry("deadline", DATE_FORMAT.format(Instant.ofEpochMilli(contract.expiresAt()))),
            Map.entry("description", contract.description())
        )));
        if (privileged && contract.status() == ContractStatus.DISPUTED && contract.disputeReason() != null) {
            send(sender, Text.color("&#E63946争议原因: &#F1F5F9" + contract.disputeReason()));
        }
    }

    private boolean isPrivilegedViewer(CommandSender sender, Contract contract) {
        if (sender.hasPermission("contract.admin.view")) {
            return true;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }
        return contract.relatedTo(player.getUniqueId());
    }

    private String participantLines(Contract contract, boolean privileged) {
        List<String> lines = new ArrayList<>();
        for (Participant participant : contract.participants()) {
            String name = visibleParticipantName(participant, privileged);
            BigDecimal stake = participant.moneyStake();
            String stakeText = stake.signum() > 0
                ? " &#CFD8DC" + plugin.lang().term("stake", "Stake") + ": &#69DB7C" + plugin.economy().format(stake)
                : "";
            lines.add("    &#CFD8DC- &#FFE066" + plugin.lang().role(participant.role())
                + "&#CFD8DC: &#FFFFFF" + name + stakeText);
        }
        return String.join("\n", lines);
    }

    private String visibleParticipantName(Participant participant, boolean privileged) {
        if (participant.displayName() == null || participant.displayName().isBlank()) {
            return plugin.lang().term("unspecified", "Unspecified");
        }
        if (privileged) {
            return participant.displayName();
        }
        return switch (participant.role()) {
            case CONTRACTOR, PARTY_B, PARTNER, CLAIMER, DEBTOR -> plugin.lang().term("assigned", "Assigned");
            default -> participant.displayName();
        };
    }

    private String arbiterLine(Contract contract, boolean privileged) {
        Participant arbiter = contract.arbiter();
        if (arbiter == null) {
            return "";
        }
        String name = privileged ? arbiter.displayName() : plugin.lang().term("assigned", "Assigned");
        String accepted = contract.arbiterAccepted()
            ? plugin.lang().term("mediator-accepted", "Mediator accepted")
            : plugin.lang().term("mediator-pending", "Mediator pending");
        return "    &#CFD8DC- &#FFE066" + plugin.lang().role(arbiter.role())
            + "&#CFD8DC: &#FFFFFF" + name + " &#CFD8DC(" + accepted + ")\n";
    }

    private String payoutLines(Contract contract) {
        List<String> lines = new ArrayList<>();
        for (PayoutCondition condition : PayoutCondition.values()) {
            List<PayoutRule> rules = contract.payoutsFor(condition);
            if (rules.isEmpty()) {
                continue;
            }
            lines.add("    &#CFD8DC- &#FFE066" + plugin.lang().condition(condition)
                + "&#CFD8DC: " + summarizeRules(rules));
        }
        return lines.isEmpty() ? "    &#CFD8DC- " + plugin.lang().term("none", "None") : String.join("\n", lines);
    }

    private String summarizeRules(List<PayoutRule> rules) {
        List<String> parts = new ArrayList<>();
        for (PayoutRule rule : rules) {
            parts.add(plugin.lang().role(rule.source()) + " "
                + rule.sharePercent().stripTrailingZeros().toPlainString()
                + "% -> " + recipientLabel(rule.recipient()));
        }
        return String.join(", ", parts);
    }

    private String recipientLabel(PayoutRecipient recipient) {
        return switch (recipient.kind()) {
            case PARTICIPANT -> plugin.lang().role(recipient.role());
            case SYSTEM_SINK -> plugin.lang().term("system-sink", "System sink");
            case ARBITER -> plugin.lang().term("arbiter", "Arbiter");
        };
    }

    private String approvalLine(Contract contract) {
        String approved = contract.metadata.get("approved-roles");
        if (approved == null || approved.isBlank()) {
            return "";
        }
        return "&#FFE066" + plugin.lang().term("approved-roles", "Approved roles") + ": &#FFFFFF" + approved + "\n";
    }

    private void sendResult(CommandSender sender, ServiceResult result, String successKey) {
        if (!result.success()) {
            sendFailure(sender, result);
            return;
        }
        send(sender, plugin.lang().message(successKey, Map.of("id", result.contract().shortId())));
    }

    private void sendFailure(CommandSender sender, ServiceResult result) {
        send(sender, plugin.lang().message("operation-failed", Map.of("reason", result.reason())));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, plugin.lang().message("player-only"));
        return null;
    }

    private boolean requirePermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        send(player, plugin.lang().message("no-permission"));
        return false;
    }

    private void send(CommandSender sender, String message) {
        for (String line : message.split("\\R")) {
            sender.sendMessage(line);
        }
    }

    private String join(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private ContractText parseContractText(String[] args, int start, boolean allowMediator) {
        if (start >= args.length) {
            return null;
        }
        String mediatorName = null;
        int textStart = start;
        if (allowMediator && isMediatorFlag(args[start])) {
            if (start + 2 >= args.length) {
                return null;
            }
            mediatorName = args[start + 1];
            textStart = start + 2;
        }
        String rest = join(args, textStart);
        if (rest.isBlank()) {
            return null;
        }
        int splitIndex = rest.indexOf('|');
        if (splitIndex < 0) {
            return new ContractText(rest, "", mediatorName);
        }
        return new ContractText(rest.substring(0, splitIndex), rest.substring(splitIndex + 1), mediatorName);
    }

    private boolean isMediatorFlag(String arg) {
        return arg.equalsIgnoreCase("--mediator") || arg.equalsIgnoreCase("-m");
    }

    private Double parseDouble(String value) {
        try {
            double parsed = Double.parseDouble(value);
            // Reject NaN / Infinity so they never reach BigDecimal.valueOf (which throws on non-finite doubles).
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int parseInt(String value, int fallback) {
        Integer parsed = parseInt(value);
        return parsed == null ? fallback : parsed;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return startsWith(List.of("gui", "service", "wager", "resolve", "mediate", "partner", "list", "all", "my", "info", "accept", "submit", "approve", "cancel", "dispute", "admin", "help"), args[0]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("service")) {
            return startsWith(List.of("--mediator"), args[3]);
        }
        if (args.length == 6 && args[0].equalsIgnoreCase("partner")) {
            return startsWith(List.of("--mediator"), args[5]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("mediate")) {
            return startsWith(List.of("accept", "pay", "refund", "owner", "contractor", "a", "b"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return startsWith(List.of("reload", "pay", "refund", "close"), args[1]);
        }
        if ((args.length == 2 && List.of("info", "accept", "submit", "approve", "cancel", "dispute", "resolve", "mediate").contains(args[0].toLowerCase(Locale.ROOT)))
            || (args.length == 3 && args[0].equalsIgnoreCase("admin"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            boolean adminView = sender.hasPermission("contract.admin.view");
            java.util.UUID viewerUuid = sender instanceof Player p ? p.getUniqueId() : null;
            return plugin.contracts().allContracts().stream()
                .filter(contract -> adminView
                    || contract.status() == ContractStatus.OPEN
                    || (viewerUuid != null
                        && contract.relatedTo(viewerUuid)))
                .map(Contract::shortId)
                .filter(id -> id.toLowerCase(Locale.ROOT).startsWith(prefix))
                .limit(20)
                .toList();
        }
        return List.of();
    }

    private List<String> startsWith(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private record ContractText(String title, String description, String mediatorName) {
    }
}
