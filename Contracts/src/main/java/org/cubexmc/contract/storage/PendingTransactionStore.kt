package org.cubexmc.contract.storage

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.util.ArrayList
import java.util.UUID
import java.util.logging.Logger

class PendingTransactionStore {
    private val file: File
    private val logger: Logger

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "pending-transactions.yml"), plugin.logger)

    constructor(file: File, logger: Logger) {
        this.file = file
        this.logger = logger
    }

    @Throws(IOException::class)
    fun beginWithdraw(playerUuid: UUID, amount: BigDecimal, purpose: String): String =
        beginWithdraw(playerUuid, amount, purpose, null)

    /**
     * Records a write-ahead withdraw intent. The {@code contractId} lets crash recovery correlate the
     * withdraw with the contract it was meant to fund, so a withdraw that already became escrow is not
     * refunded a second time on restart.
     */
    @Throws(IOException::class)
    fun beginWithdraw(playerUuid: UUID, amount: BigDecimal, purpose: String, contractId: String?): String {
        val id = UUID.randomUUID().toString()
        val yaml = loadYaml()
        val section = yaml.createSection("pending.$id")
        section["type"] = PendingType.WITHDRAW.name
        section["player-uuid"] = playerUuid.toString()
        section["amount"] = amount.toPlainString()
        section["purpose"] = purpose
        if (contractId != null && contractId.isNotBlank()) {
            section["contract-id"] = contractId
        }
        section["created-at"] = System.currentTimeMillis()
        yaml.save(file)
        return id
    }

    @Throws(IOException::class)
    fun beginDeposit(
        playerUuid: UUID,
        amount: BigDecimal,
        purpose: String,
        contractId: String,
        payoutKey: String,
        settlementId: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val yaml = loadYaml()
        val section = yaml.createSection("pending.$id")
        section["type"] = PendingType.DEPOSIT.name
        section["player-uuid"] = playerUuid.toString()
        section["amount"] = amount.toPlainString()
        section["purpose"] = purpose
        section["contract-id"] = contractId
        section["payout-key"] = payoutKey
        section["settlement-id"] = settlementId
        section["created-at"] = System.currentTimeMillis()
        yaml.save(file)
        return id
    }

    @Throws(IOException::class)
    fun beginSettlement(contractId: String, purpose: String): String {
        val id = UUID.randomUUID().toString()
        val yaml = loadYaml()
        val section = yaml.createSection("pending.$id")
        section["type"] = PendingType.SETTLEMENT.name
        section["amount"] = "0"
        section["purpose"] = purpose
        section["contract-id"] = contractId
        section["created-at"] = System.currentTimeMillis()
        yaml.save(file)
        return id
    }

    @Throws(IOException::class)
    fun clear(id: String) {
        val yaml = loadYaml()
        yaml["pending.$id"] = null
        yaml.save(file)
    }

    fun loadAll(): List<PendingEntry> {
        val entries = ArrayList<PendingEntry>()
        if (!file.exists()) {
            return entries
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("pending") ?: return entries
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            try {
                val type = PendingType.valueOf(section.getString("type", PendingType.WITHDRAW.name) ?: PendingType.WITHDRAW.name)
                val playerRaw = section.getString("player-uuid")
                val playerUuid = if (playerRaw == null || playerRaw.isBlank()) null else UUID.fromString(playerRaw)
                val amount = readAmount(section)
                val purpose = section.getString("purpose", "") ?: ""
                val createdAt = section.getLong("created-at")
                val contractId = section.getString("contract-id")
                val payoutKey = section.getString("payout-key")
                val settlementId = section.getString("settlement-id")
                entries.add(PendingEntry(id, type, playerUuid, amount, purpose, createdAt, contractId, payoutKey, settlementId))
            } catch (ex: RuntimeException) {
                logger.warning("Skipping malformed pending transaction $id: ${ex.message}")
            }
        }
        return entries
    }

    private fun loadYaml(): YamlConfiguration {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    private fun readAmount(section: ConfigurationSection): BigDecimal {
        val raw = section["amount"] ?: return BigDecimal.ZERO
        if (raw is Number) {
            return BigDecimal.valueOf(raw.toDouble())
        }
        return BigDecimal(raw.toString())
    }

    enum class PendingType {
        WITHDRAW,
        DEPOSIT,
        SETTLEMENT,
    }

    class PendingEntry(
        private val id: String,
        private val type: PendingType,
        private val playerUuid: UUID?,
        private val amount: BigDecimal,
        private val purpose: String,
        private val createdAt: Long,
        private val contractId: String?,
        private val payoutKey: String?,
        private val settlementId: String?,
    ) {
        fun id(): String = id
        fun type(): PendingType = type
        fun playerUuid(): UUID? = playerUuid
        fun amount(): BigDecimal = amount
        fun purpose(): String = purpose
        fun createdAt(): Long = createdAt
        fun contractId(): String? = contractId
        fun payoutKey(): String? = payoutKey
        fun settlementId(): String? = settlementId

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingEntry) return false
            return id == other.id &&
                type == other.type &&
                playerUuid == other.playerUuid &&
                amount == other.amount &&
                purpose == other.purpose &&
                createdAt == other.createdAt &&
                contractId == other.contractId &&
                payoutKey == other.payoutKey &&
                settlementId == other.settlementId
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + type.hashCode()
            result = 31 * result + (playerUuid?.hashCode() ?: 0)
            result = 31 * result + amount.hashCode()
            result = 31 * result + purpose.hashCode()
            result = 31 * result + createdAt.hashCode()
            result = 31 * result + (contractId?.hashCode() ?: 0)
            result = 31 * result + (payoutKey?.hashCode() ?: 0)
            result = 31 * result + (settlementId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "PendingEntry[id=$id, type=$type, playerUuid=$playerUuid, amount=$amount, purpose=$purpose, createdAt=$createdAt, contractId=$contractId, payoutKey=$payoutKey, settlementId=$settlementId]"
    }
}
