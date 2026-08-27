package org.cubexmc.contract.storage

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.contract.ContractPlugin
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.UUID
import org.cubexmc.core.CubexLogger

class PendingTransactionStore {
    private val file: File
    private val logger: CubexLogger

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "pending-transactions.yml"), plugin.log())

    constructor(file: File, logger: CubexLogger) {
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
    @Synchronized
    fun beginWithdraw(playerUuid: UUID, amount: BigDecimal, purpose: String, contractId: String?): String {
        require(!isAllianceFunding(purpose)) { "Alliance funding requires a phased journal entry" }
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
        saveYaml(yaml)
        return id
    }

    /** ALLIANCE-only phased intent. Legacy withdraw records keep their original shape. */
    @Throws(IOException::class)
    @Synchronized
    fun beginAllianceWithdraw(playerUuid: UUID, amount: BigDecimal, purpose: String, contractId: String): String {
        require(isAllianceFunding(purpose) && contractId.isNotBlank() && amount.signum() > 0)
        try {
            amount.setScale(2, RoundingMode.UNNECESSARY)
        } catch (ex: ArithmeticException) {
            throw IllegalArgumentException("Alliance funding amount must use whole cents", ex)
        }
        val yaml = loadYaml()
        val id = UUID.randomUUID().toString()
        val section = yaml.createSection("pending.$id")
        section["type"] = PendingType.WITHDRAW.name
        section["player-uuid"] = playerUuid.toString()
        section["amount"] = amount.toPlainString()
        section["purpose"] = purpose
        section["contract-id"] = contractId
        section["created-at"] = System.currentTimeMillis()
        section["funding-phase"] = FundingPhase.PREPARED.name
        saveYaml(yaml)
        return id
    }

    @Throws(IOException::class)
    @Synchronized
    fun advanceFunding(id: String, expected: FundingPhase, next: FundingPhase) {
        val allowed = when (expected) {
            FundingPhase.PREPARED -> next == FundingPhase.WITHDRAWN || next == FundingPhase.REJECTED
            FundingPhase.WITHDRAWN -> next == FundingPhase.REFUNDING
            FundingPhase.REFUNDING -> next == FundingPhase.REFUNDED || next == FundingPhase.WITHDRAWN
            else -> false
        }
        require(allowed) { "Invalid funding phase transition" }
        val yaml = loadYaml()
        val section = requireNotNull(yaml.getConfigurationSection("pending.$id")) { "Funding intent missing" }
        require(isAllianceFunding(section.getString("purpose")) && section.getString("type") == PendingType.WITHDRAW.name)
        require(section.getString("funding-phase") == expected.name) { "Funding phase changed" }
        section["funding-phase"] = next.name
        saveYaml(yaml)
    }

    @Throws(IOException::class)
    @Synchronized
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
        saveYaml(yaml)
        return id
    }

    @Throws(IOException::class)
    @Synchronized
    fun beginSettlement(contractId: String, purpose: String): String {
        val id = UUID.randomUUID().toString()
        val yaml = loadYaml()
        val section = yaml.createSection("pending.$id")
        section["type"] = PendingType.SETTLEMENT.name
        section["amount"] = "0"
        section["purpose"] = purpose
        section["contract-id"] = contractId
        section["created-at"] = System.currentTimeMillis()
        saveYaml(yaml)
        return id
    }

    @Throws(IOException::class)
    @Synchronized
    fun clear(id: String) {
        val yaml = loadYaml()
        yaml["pending.$id"] = null
        saveYaml(yaml)
    }

    @Synchronized
    fun loadAll(): List<PendingEntry> {
        val entries = ArrayList<PendingEntry>()
        if (!file.exists()) {
            return entries
        }
        val yaml = loadYaml()
        val root = yaml.getConfigurationSection("pending") ?: return entries
        for (id in root.getKeys(false)) {
            val section = requireNotNull(root.getConfigurationSection(id)) { "Invalid pending entry $id" }
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
                val phase = section.getString("funding-phase")?.let { FundingPhase.valueOf(it) }
                if (isAllianceFunding(purpose) || phase != null) {
                    require(isAllianceFunding(purpose) && type == PendingType.WITHDRAW && phase != null && playerUuid != null && !contractId.isNullOrBlank()) {
                        "Incomplete alliance funding intent"
                    }
                    require(amount.signum() > 0)
                    amount.setScale(2, RoundingMode.UNNECESSARY)
                }
                entries.add(PendingEntry(id, type, playerUuid, amount, purpose, createdAt, contractId, payoutKey, settlementId, phase))
            } catch (ex: RuntimeException) {
                if (section.contains("funding-phase") || section.getString("purpose", "")?.startsWith("alliance-") == true) {
                    throw IllegalStateException("Invalid alliance funding intent $id; manual review required", ex)
                }
                logger.warn("Skipping malformed pending transaction $id: ${ex.message}")
            }
        }
        return entries
    }

    private fun loadYaml(): YamlConfiguration {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val yaml = YamlConfiguration()
        if (file.exists()) yaml.load(file)
        require(!yaml.contains("pending") || yaml.isConfigurationSection("pending")) { "Invalid pending journal root" }
        return yaml
    }

    /** Never truncate the journal in place or recover a stale journal snapshot. */
    private fun saveYaml(yaml: YamlConfiguration) {
        val target = file.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "pending-transactions-", ".tmp")
        try {
            yaml.save(temp.toFile())
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
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

    enum class FundingPhase { PREPARED, WITHDRAWN, REFUNDING, REFUNDED, REJECTED }

    companion object {
        @JvmStatic
        fun isAllianceFunding(purpose: String?): Boolean = purpose == "alliance-create" || purpose == "alliance-accept"
    }

    class PendingEntry @JvmOverloads constructor(
        private val id: String,
        private val type: PendingType,
        private val playerUuid: UUID?,
        private val amount: BigDecimal,
        private val purpose: String,
        private val createdAt: Long,
        private val contractId: String?,
        private val payoutKey: String?,
        private val settlementId: String?,
        private val fundingPhase: FundingPhase? = null,
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
        fun fundingPhase(): FundingPhase? = fundingPhase

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
                settlementId == other.settlementId && fundingPhase == other.fundingPhase
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
            result = 31 * result + (fundingPhase?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "PendingEntry[id=$id, type=$type, playerUuid=$playerUuid, amount=$amount, purpose=$purpose, createdAt=$createdAt, contractId=$contractId, payoutKey=$payoutKey, settlementId=$settlementId, fundingPhase=$fundingPhase]"
    }
}
