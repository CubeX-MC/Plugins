package org.cubexmc.contract.storage;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;
import org.cubexmc.contract.model.Asset;
import org.cubexmc.contract.model.Contract;
import org.cubexmc.contract.model.ContractEvent;
import org.cubexmc.contract.model.ContractStatus;
import org.cubexmc.contract.model.ContractType;
import org.cubexmc.contract.model.Participant;
import org.cubexmc.contract.model.ParticipantRole;
import org.cubexmc.contract.model.PayoutCondition;
import org.cubexmc.contract.model.PayoutRecipient;
import org.cubexmc.contract.model.PayoutRule;
import org.cubexmc.contract.model.ResolutionRule;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

public final class ContractStorage {
    private final File file;
    private final File backupFile;
    private final File dataFolder;
    private final Logger logger;
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private volatile boolean dirty = false;

    public ContractStorage(ContractPlugin plugin) {
        this(new File(plugin.getDataFolder(), "contract.yml"), plugin.getLogger());
    }

    ContractStorage(File file, Logger logger) {
        this.file = file;
        this.backupFile = new File(file.getParentFile(), file.getName() + ".bak");
        this.dataFolder = file.getParentFile();
        this.logger = logger;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void flushIfDirty() throws IOException {
        if (!dirty) {
            return;
        }
        save();
    }

    public void load() {
        // Read into a temp map first so a failed/recovered load never leaves the live map half-populated.
        Map<String, Contract> loaded = readContracts();
        contracts.clear();
        contracts.putAll(loaded);
    }

    private Map<String, Contract> readContracts() {
        if (!file.exists()) {
            return new LinkedHashMap<>();
        }
        try {
            return parseContracts(loadStrict(file));
        } catch (Exception primary) {
            logger.severe("contract.yml is unreadable: " + primary.getMessage());
            if (backupFile.exists()) {
                try {
                    Map<String, Contract> recovered = parseContracts(loadStrict(backupFile));
                    logger.warning("Recovered " + recovered.size() + " contracts from " + backupFile.getName() + ".");
                    return recovered;
                } catch (Exception backup) {
                    logger.severe("Backup " + backupFile.getName() + " is also unreadable: " + backup.getMessage());
                }
            }
            // Refusing to start empty protects escrowed funds: an empty map would be flushed over the only
            // copy of the data and orphan every player's withdrawn money. Force the admin to restore a backup.
            throw new IllegalStateException(
                "contract.yml and its backup are both unreadable; refusing to start with an empty contract set.",
                primary);
        }
    }

    private YamlConfiguration loadStrict(File source) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(source);
        return yaml;
    }

    private Map<String, Contract> parseContracts(YamlConfiguration yaml) {
        Map<String, Contract> result = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("contracts");
        if (root == null) {
            return result;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                Contract contract = readContract(id, section);
                result.put(contract.id(), contract);
            } catch (RuntimeException ex) {
                logger.warning("Skipping malformed contract " + id + ": " + ex.getMessage());
            }
        }
        return result;
    }

    public void save() throws IOException {
        if (dataFolder != null && !dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("contracts");
        for (Contract contract : contracts.values()) {
            writeContract(root.createSection(contract.id()), contract);
        }
        saveAtomically(yaml);
        dirty = false;
    }

    /**
     * Persist the financial database crash-safely: serialize to a temp file, roll the previous good file
     * into {@code contract.yml.bak}, then atomically swap the temp file in. A crash mid-write can therefore
     * never truncate {@code contract.yml} and orphan escrowed funds.
     */
    private void saveAtomically(YamlConfiguration yaml) throws IOException {
        Path target = file.toPath();
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path temp = Files.createTempFile(dir, "contract", ".tmp");
        try {
            yaml.save(temp.toFile());
            if (Files.exists(target)) {
                Files.copy(target, backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void put(Contract contract) {
        contracts.put(contract.id(), contract);
    }

    public void remove(String id) {
        contracts.remove(id);
    }

    public Optional<Contract> findByPrefix(String input) {
        String normalized = input.replace("#", "").toLowerCase(Locale.ROOT);
        List<Contract> matches = contracts.values().stream()
            .filter(contract -> contract.id().toLowerCase(Locale.ROOT).startsWith(normalized)
                || contract.shortId().toLowerCase(Locale.ROOT).equals(normalized))
            .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    public Optional<Contract> findById(String id) {
        return Optional.ofNullable(contracts.get(id));
    }

    public List<Contract> all() {
        return contracts.values().stream()
            .sorted(Comparator.comparingLong(Contract::createdAt).reversed())
            .toList();
    }

    public List<Contract> openContracts() {
        return contracts.values().stream()
            .filter(contract -> contract.status() == ContractStatus.OPEN)
            .sorted(Comparator.comparingLong(Contract::createdAt).reversed())
            .toList();
    }

    private Contract readContract(String id, ConfigurationSection section) {
        if (section.contains("participants")) {
            return readNewFormat(id, section);
        }
        return readLegacyFormat(id, section);
    }

    private Contract readNewFormat(String id, ConfigurationSection section) {
        ContractType type = ContractType.valueOf(section.getString("type", "SERVICE"));
        ContractStatus status = ContractStatus.valueOf(section.getString("status", "OPEN"));
        ResolutionRule resolutionRule = ResolutionRule.valueOf(section.getString("resolution-rule", "OWNER_APPROVE"));

        List<Participant> participants = new ArrayList<>();
        Object pRaw = section.get("participants");
        if (pRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    Participant p = Participant.fromMap(map);
                    if (p.uuid() != null) {
                        p.displayName(resolveName(p.uuid(), p.displayName()));
                    }
                    participants.add(p);
                }
            }
        }

        Participant arbiter = null;
        Object aRaw = section.get("arbiter");
        if (aRaw instanceof Map<?, ?> map) {
            arbiter = Participant.fromMap(map);
            if (arbiter.uuid() != null) {
                arbiter.displayName(resolveName(arbiter.uuid(), arbiter.displayName()));
            }
        }

        List<PayoutRule> payouts = new ArrayList<>();
        Object payoutsRaw = section.get("payouts");
        if (payoutsRaw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map) {
                    payouts.add(PayoutRule.fromMap(map));
                }
            }
        }

        Contract contract = new Contract(
            id,
            type,
            section.getString("title", ""),
            section.getString("description", ""),
            participants,
            arbiter,
            resolutionRule,
            payouts,
            status,
            section.getLong("created-at"),
            nullableLong(section, "accepted-at"),
            nullableLong(section, "submitted-at"),
            nullableLong(section, "completed-at"),
            section.getLong("expires-at"),
            section.getString("dispute-reason"),
            readEvents(section)
        );

        ConfigurationSection meta = section.getConfigurationSection("metadata");
        if (meta != null) {
            for (String key : meta.getKeys(false)) {
                contract.metadata.put(key, meta.getString(key, ""));
            }
        }
        return contract;
    }

    /** Migrate pre-abstraction yaml entries (owner-uuid / reward / commission-percent flat keys). */
    private Contract readLegacyFormat(String id, ConfigurationSection section) {
        UUID ownerUuid = UUID.fromString(Objects.requireNonNull(section.getString("owner-uuid")));
        String ownerName = resolveName(ownerUuid, section.getString("owner-name", "Unknown"));
        UUID contractorUuid = uuidOrNull(section.getString("contractor-uuid"));
        String contractorName = contractorUuid == null
            ? section.getString("contractor-name")
            : resolveName(contractorUuid, section.getString("contractor-name"));
        ContractStatus status = ContractStatus.valueOf(section.getString("status", "OPEN"));
        BigDecimal reward = readBigDecimal(section, "reward");
        BigDecimal creationFee = readBigDecimal(section, "creation-fee");
        BigDecimal commissionPercent = readBigDecimal(section, "commission-percent");

        Participant owner = new Participant(ParticipantRole.OWNER, ownerUuid, ownerName,
            List.of(Asset.money(reward)));
        Participant contractor = new Participant(ParticipantRole.CONTRACTOR, contractorUuid, contractorName,
            List.of());

        List<PayoutRule> rules = new ArrayList<>();
        BigDecimal payoutShare = new BigDecimal("100").subtract(commissionPercent);
        rules.add(new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.CONTRACTOR), payoutShare));
        rules.add(new PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER,
            PayoutRecipient.systemSink(), commissionPercent));
        rules.add(new PayoutRule(PayoutCondition.FAILURE, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.OWNER), new BigDecimal("100")));
        rules.add(new PayoutRule(PayoutCondition.TIMEOUT, ParticipantRole.OWNER,
            PayoutRecipient.participant(ParticipantRole.OWNER), new BigDecimal("100")));

        Contract contract = new Contract(
            id,
            ContractType.SERVICE,
            section.getString("title", ""),
            section.getString("description", ""),
            List.of(owner, contractor),
            null,
            ResolutionRule.OWNER_APPROVE,
            rules,
            status,
            section.getLong("created-at"),
            nullableLong(section, "accepted-at"),
            nullableLong(section, "submitted-at"),
            nullableLong(section, "completed-at"),
            section.getLong("expires-at"),
            section.getString("dispute-reason"),
            readEvents(section)
        );
        contract.metadata.put("creation-fee", creationFee.toPlainString());
        contract.metadata.put("commission-percent", commissionPercent.toPlainString());
        return contract;
    }

    private List<ContractEvent> readEvents(ConfigurationSection section) {
        List<ContractEvent> result = new ArrayList<>();
        Object raw = section.get("events");
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(ContractEvent.fromMap(map));
            } else if (item instanceof String legacy) {
                result.add(ContractEvent.fromLegacyLine(legacy));
            }
        }
        return result;
    }

    private void writeContract(ConfigurationSection section, Contract contract) {
        section.set("type", contract.type().name());
        section.set("title", contract.title());
        section.set("description", contract.description());
        section.set("resolution-rule", contract.resolutionRule().name());

        List<Map<String, Object>> participants = new ArrayList<>();
        for (Participant p : contract.participants()) {
            participants.add(p.toMap());
        }
        section.set("participants", participants);

        if (contract.arbiter() != null) {
            section.set("arbiter", contract.arbiter().toMap());
        }

        List<Map<String, Object>> payouts = new ArrayList<>();
        for (PayoutRule rule : contract.payouts()) {
            payouts.add(rule.toMap());
        }
        section.set("payouts", payouts);

        section.set("status", contract.status().name());
        section.set("created-at", contract.createdAt());
        section.set("accepted-at", contract.acceptedAt());
        section.set("submitted-at", contract.submittedAt());
        section.set("completed-at", contract.completedAt());
        section.set("expires-at", contract.expiresAt());
        section.set("dispute-reason", contract.disputeReason());

        if (!contract.metadata.isEmpty()) {
            ConfigurationSection meta = section.createSection("metadata");
            for (Map.Entry<String, String> entry : contract.metadata.entrySet()) {
                meta.set(entry.getKey(), entry.getValue());
            }
        }

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ContractEvent event : contract.events()) {
            serialized.add(event.toMap());
        }
        section.set("events", serialized);
    }

    private BigDecimal readBigDecimal(ConfigurationSection section, String path) {
        Object raw = section.get(path);
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private String resolveName(UUID uuid, String fallback) {
        if (uuid == null) {
            return fallback;
        }
        try {
            String current = Bukkit.getOfflinePlayer(uuid).getName();
            return current != null ? current : fallback;
        } catch (Throwable t) {
            // Bukkit not initialized (unit tests) or a name lookup failure — keep the stored name.
            return fallback;
        }
    }

    private UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value);
    }

    private Long nullableLong(ConfigurationSection section, String path) {
        return section.contains(path) ? section.getLong(path) : null;
    }
}
