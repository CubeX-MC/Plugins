package org.cubexmc.contract.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.contract.ContractPlugin;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class PendingTransactionStore {
    private final File file;
    private final Logger logger;

    public PendingTransactionStore(ContractPlugin plugin) {
        this(new File(plugin.getDataFolder(), "pending-transactions.yml"), plugin.getLogger());
    }

    PendingTransactionStore(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public String beginWithdraw(UUID playerUuid, BigDecimal amount, String purpose) throws IOException {
        return beginWithdraw(playerUuid, amount, purpose, null);
    }

    /**
     * Records a write-ahead withdraw intent. The {@code contractId} lets crash recovery correlate the
     * withdraw with the contract it was meant to fund, so a withdraw that already became escrow is not
     * refunded a second time on restart.
     */
    public String beginWithdraw(UUID playerUuid, BigDecimal amount, String purpose, String contractId)
            throws IOException {
        String id = UUID.randomUUID().toString();
        YamlConfiguration yaml = loadYaml();
        ConfigurationSection section = yaml.createSection("pending." + id);
        section.set("type", PendingType.WITHDRAW.name());
        section.set("player-uuid", playerUuid.toString());
        section.set("amount", amount.toPlainString());
        section.set("purpose", purpose);
        if (contractId != null && !contractId.isBlank()) {
            section.set("contract-id", contractId);
        }
        section.set("created-at", System.currentTimeMillis());
        yaml.save(file);
        return id;
    }

    public String beginDeposit(UUID playerUuid, BigDecimal amount, String purpose,
                               String contractId, String payoutKey, String settlementId) throws IOException {
        String id = UUID.randomUUID().toString();
        YamlConfiguration yaml = loadYaml();
        ConfigurationSection section = yaml.createSection("pending." + id);
        section.set("type", PendingType.DEPOSIT.name());
        section.set("player-uuid", playerUuid.toString());
        section.set("amount", amount.toPlainString());
        section.set("purpose", purpose);
        section.set("contract-id", contractId);
        section.set("payout-key", payoutKey);
        section.set("settlement-id", settlementId);
        section.set("created-at", System.currentTimeMillis());
        yaml.save(file);
        return id;
    }

    public String beginSettlement(String contractId, String purpose) throws IOException {
        String id = UUID.randomUUID().toString();
        YamlConfiguration yaml = loadYaml();
        ConfigurationSection section = yaml.createSection("pending." + id);
        section.set("type", PendingType.SETTLEMENT.name());
        section.set("amount", "0");
        section.set("purpose", purpose);
        section.set("contract-id", contractId);
        section.set("created-at", System.currentTimeMillis());
        yaml.save(file);
        return id;
    }

    public void clear(String id) throws IOException {
        YamlConfiguration yaml = loadYaml();
        yaml.set("pending." + id, null);
        yaml.save(file);
    }

    public List<PendingEntry> loadAll() {
        List<PendingEntry> entries = new ArrayList<>();
        if (!file.exists()) {
            return entries;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("pending");
        if (root == null) {
            return entries;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                PendingType type = PendingType.valueOf(section.getString("type", PendingType.WITHDRAW.name()));
                String playerRaw = section.getString("player-uuid");
                UUID playerUuid = playerRaw == null || playerRaw.isBlank() ? null : UUID.fromString(playerRaw);
                BigDecimal amount = readAmount(section);
                String purpose = section.getString("purpose", "");
                long createdAt = section.getLong("created-at");
                String contractId = section.getString("contract-id");
                String payoutKey = section.getString("payout-key");
                String settlementId = section.getString("settlement-id");
                entries.add(new PendingEntry(id, type, playerUuid, amount, purpose,
                    createdAt, contractId, payoutKey, settlementId));
            } catch (RuntimeException ex) {
                logger.warning("Skipping malformed pending transaction " + id + ": " + ex.getMessage());
            }
        }
        return entries;
    }

    private YamlConfiguration loadYaml() {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private BigDecimal readAmount(ConfigurationSection section) {
        Object raw = section.get("amount");
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(raw.toString());
    }

    public enum PendingType {
        WITHDRAW,
        DEPOSIT,
        SETTLEMENT
    }

    public record PendingEntry(String id, PendingType type, UUID playerUuid, BigDecimal amount, String purpose,
                               long createdAt, String contractId, String payoutKey, String settlementId) {
    }
}
