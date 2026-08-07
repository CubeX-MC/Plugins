package org.cubexmc.contract.storage

import org.bukkit.Bukkit
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.cubexmc.contract.ContractPlugin
import org.cubexmc.contract.model.Asset
import org.cubexmc.contract.model.Contract
import org.cubexmc.contract.model.ContractEvent
import org.cubexmc.contract.model.ContractObjective
import org.cubexmc.contract.model.ContractStatus
import org.cubexmc.contract.model.ContractType
import org.cubexmc.contract.model.Participant
import org.cubexmc.contract.model.ParticipantRole
import org.cubexmc.contract.model.PayoutCondition
import org.cubexmc.contract.model.PayoutRecipient
import org.cubexmc.contract.model.PayoutRule
import org.cubexmc.contract.model.ResolutionRule
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.Comparator
import java.util.LinkedHashMap
import java.util.Objects
import java.util.Optional
import java.util.UUID
import java.util.logging.Logger

class ContractStorage {
    private val file: File
    private val backupFile: File
    private val dataFolder: File?
    private val logger: Logger
    private val contracts: MutableMap<String, Contract> = LinkedHashMap()

    @Volatile
    private var dirty = false

    constructor(plugin: ContractPlugin) : this(File(plugin.dataFolder, "contract.yml"), plugin.logger)

    constructor(file: File, logger: Logger) {
        this.file = file
        this.backupFile = File(file.parentFile, file.name + ".bak")
        this.dataFolder = file.parentFile
        this.logger = logger
    }

    @Synchronized
    fun markDirty() {
        dirty = true
    }

    @Synchronized
    fun isDirty(): Boolean = dirty

    @Throws(IOException::class)
    @Synchronized
    fun flushIfDirty() {
        if (!dirty) {
            return
        }
        save()
    }

    @Synchronized
    fun load() {
        // Read into a temp map first so a failed/recovered load never leaves the live map half-populated.
        val loaded = readContracts()
        contracts.clear()
        contracts.putAll(loaded)
    }

    private fun readContracts(): Map<String, Contract> {
        if (!file.exists()) {
            return LinkedHashMap()
        }
        try {
            return parseContracts(loadStrict(file))
        } catch (primary: Exception) {
            logger.severe("contract.yml is unreadable: ${primary.message}")
            if (backupFile.exists()) {
                try {
                    val recovered = parseContracts(loadStrict(backupFile))
                    logger.warning("Recovered ${recovered.size} contracts from ${backupFile.name}.")
                    return recovered
                } catch (backup: Exception) {
                    logger.severe("Backup ${backupFile.name} is also unreadable: ${backup.message}")
                }
            }
            throw IllegalStateException(
                "contract.yml and its backup are both unreadable; refusing to start with an empty contract set.",
                primary,
            )
        }
    }

    @Throws(IOException::class, InvalidConfigurationException::class)
    private fun loadStrict(source: File): YamlConfiguration {
        val yaml = YamlConfiguration()
        yaml.load(source)
        return yaml
    }

    private fun parseContracts(yaml: YamlConfiguration): Map<String, Contract> {
        val result = LinkedHashMap<String, Contract>()
        val root = yaml.getConfigurationSection("contracts") ?: return result
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            try {
                val contract = readContract(id, section)
                result[contract.id()] = contract
            } catch (ex: RuntimeException) {
                logger.warning("Skipping malformed contract $id: ${ex.message}")
            }
        }
        return result
    }

    @Throws(IOException::class)
    @Synchronized
    fun save() {
        if (dataFolder != null && !dataFolder.exists()) {
            dataFolder.mkdirs()
        }
        val yaml = YamlConfiguration()
        val root = yaml.createSection("contracts")
        for (contract in contracts.values) {
            writeContract(root.createSection(contract.id()), contract)
        }
        saveAtomically(yaml)
        dirty = false
    }

    /**
     * Persist the financial database crash-safely: serialize to a temp file, roll the previous good file
     * into {@code contract.yml.bak}, then atomically swap the temp file in. A crash mid-write can therefore
     * never truncate {@code contract.yml} and orphan escrowed funds.
     */
    @Throws(IOException::class)
    private fun saveAtomically(yaml: YamlConfiguration) {
        val target = file.toPath()
        val dir = target.parent
        if (dir != null) {
            Files.createDirectories(dir)
        }
        val temp = Files.createTempFile(dir, "contract", ".tmp")
        try {
            yaml.save(temp.toFile())
            if (Files.exists(target)) {
                Files.copy(target, backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (ex: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Synchronized
    fun put(contract: Contract) {
        contracts[contract.id()] = contract
    }

    @Synchronized
    fun remove(id: String) {
        contracts.remove(id)
    }

    @Synchronized
    fun findByPrefix(input: String): Optional<Contract> {
        val normalized = input.replace("#", "").lowercase(java.util.Locale.ROOT)
        val matches = contracts.values.stream()
            .filter { contract ->
                contract.id().lowercase(java.util.Locale.ROOT).startsWith(normalized) ||
                    contract.shortId().lowercase(java.util.Locale.ROOT) == normalized
            }
            .toList()
        return if (matches.size == 1) Optional.of(matches[0]) else Optional.empty()
    }

    @Synchronized
    fun findById(id: String): Optional<Contract> = Optional.ofNullable(contracts[id])

    @Synchronized
    fun all(): List<Contract> =
        contracts.values.stream()
            .sorted(Comparator.comparingLong { contract: Contract -> contract.createdAt() }.reversed())
            .toList()

    @Synchronized
    fun openContracts(): List<Contract> =
        contracts.values.stream()
            .filter { contract -> contract.status() == ContractStatus.OPEN }
            .sorted(Comparator.comparingLong { contract: Contract -> contract.createdAt() }.reversed())
            .toList()

    private fun readContract(id: String, section: ConfigurationSection): Contract =
        if (section.contains("participants")) readNewFormat(id, section) else readLegacyFormat(id, section)

    private fun readNewFormat(id: String, section: ConfigurationSection): Contract {
        val type = ContractType.valueOf(section.getString("type", "SERVICE") ?: "SERVICE")
        val status = ContractStatus.valueOf(section.getString("status", "OPEN") ?: "OPEN")
        val resolutionRule = ResolutionRule.valueOf(section.getString("resolution-rule", "OWNER_APPROVE") ?: "OWNER_APPROVE")

        val participants = ArrayList<Participant>()
        val pRaw = section["participants"]
        if (pRaw is List<*>) {
            for (entry in pRaw) {
                if (entry is Map<*, *>) {
                    val p = Participant.fromMap(entry)
                    if (p.uuid() != null) {
                        p.displayName(resolveName(p.uuid(), p.displayName()))
                    }
                    participants.add(p)
                }
            }
        }

        var arbiter: Participant? = null
        val aRaw = section["arbiter"]
        if (aRaw is Map<*, *>) {
            arbiter = Participant.fromMap(aRaw)
            if (arbiter.uuid() != null) {
                arbiter.displayName(resolveName(arbiter.uuid(), arbiter.displayName()))
            }
        }

        val payouts = ArrayList<PayoutRule>()
        val payoutsRaw = section["payouts"]
        if (payoutsRaw is List<*>) {
            for (entry in payoutsRaw) {
                if (entry is Map<*, *>) {
                    payouts.add(PayoutRule.fromMap(entry))
                }
            }
        }

        val contract = Contract(
            id,
            type,
            section.getString("title", "") ?: "",
            section.getString("description", "") ?: "",
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
            readObjective(section),
            readDeliveryItems(section),
            readEvents(section),
            readRewardItems(section),
        )

        val meta = section.getConfigurationSection("metadata")
        if (meta != null) {
            for (key in meta.getKeys(false)) {
                contract.metadata[key] = meta.getString(key, "") ?: ""
            }
        }
        return contract
    }

    /** Migrate pre-abstraction yaml entries (owner-uuid / reward / commission-percent flat keys). */
    private fun readLegacyFormat(id: String, section: ConfigurationSection): Contract {
        val ownerUuid = UUID.fromString(Objects.requireNonNull(section.getString("owner-uuid")))
        val ownerName = resolveName(ownerUuid, section.getString("owner-name", "Unknown"))
        val contractorUuid = uuidOrNull(section.getString("contractor-uuid"))
        val contractorName = if (contractorUuid == null) {
            section.getString("contractor-name")
        } else {
            resolveName(contractorUuid, section.getString("contractor-name"))
        }
        val status = ContractStatus.valueOf(section.getString("status", "OPEN") ?: "OPEN")
        val reward = readBigDecimal(section, "reward")
        val creationFee = readBigDecimal(section, "creation-fee")
        val commissionPercent = readBigDecimal(section, "commission-percent")

        val owner = Participant(ParticipantRole.OWNER, ownerUuid, ownerName, listOf(Asset.money(reward)))
        val contractor = Participant(ParticipantRole.CONTRACTOR, contractorUuid, contractorName, emptyList())

        val rules = ArrayList<PayoutRule>()
        val payoutShare = BigDecimal("100").subtract(commissionPercent)
        rules.add(
            PayoutRule(
                PayoutCondition.SUCCESS,
                ParticipantRole.OWNER,
                PayoutRecipient.participant(ParticipantRole.CONTRACTOR),
                payoutShare,
            ),
        )
        rules.add(PayoutRule(PayoutCondition.SUCCESS, ParticipantRole.OWNER, PayoutRecipient.systemSink(), commissionPercent))
        rules.add(
            PayoutRule(
                PayoutCondition.FAILURE,
                ParticipantRole.OWNER,
                PayoutRecipient.participant(ParticipantRole.OWNER),
                BigDecimal("100"),
            ),
        )
        rules.add(
            PayoutRule(
                PayoutCondition.TIMEOUT,
                ParticipantRole.OWNER,
                PayoutRecipient.participant(ParticipantRole.OWNER),
                BigDecimal("100"),
            ),
        )

        val contract = Contract(
            id,
            ContractType.SERVICE,
            section.getString("title", "") ?: "",
            section.getString("description", "") ?: "",
            listOf(owner, contractor),
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
            null,
            emptyList(),
            readEvents(section),
        )
        contract.metadata["creation-fee"] = creationFee.toPlainString()
        contract.metadata["commission-percent"] = commissionPercent.toPlainString()
        return contract
    }

    private fun readEvents(section: ConfigurationSection): List<ContractEvent> {
        val result = ArrayList<ContractEvent>()
        val raw = section["events"]
        if (raw !is List<*>) {
            return result
        }
        for (item in raw) {
            if (item is Map<*, *>) {
                result.add(ContractEvent.fromMap(item))
            } else if (item is String) {
                result.add(ContractEvent.fromLegacyLine(item))
            }
        }
        return result
    }

    private fun writeContract(section: ConfigurationSection, contract: Contract) {
        section["type"] = contract.type().name
        section["title"] = contract.title()
        section["description"] = contract.description()
        section["resolution-rule"] = contract.resolutionRule().name

        val participants = ArrayList<Map<String, Any?>>()
        for (p in contract.participants()) {
            participants.add(p.toMap())
        }
        section["participants"] = participants

        if (contract.arbiter() != null) {
            section["arbiter"] = contract.arbiter()?.toMap()
        }

        val payouts = ArrayList<Map<String, Any?>>()
        for (rule in contract.payouts()) {
            payouts.add(rule.toMap())
        }
        section["payouts"] = payouts

        section["status"] = contract.status().name
        section["created-at"] = contract.createdAt()
        section["accepted-at"] = contract.acceptedAt()
        section["submitted-at"] = contract.submittedAt()
        section["completed-at"] = contract.completedAt()
        section["expires-at"] = contract.expiresAt()
        section["dispute-reason"] = contract.disputeReason()
        val objective = contract.objective()
        if (objective != null) {
            section["objective"] = objective.toMap()
        }
        if (contract.hasDeliveryItems()) {
            section["delivery-items"] = contract.deliveryItems().map { it.serialize() }
        }
        if (contract.hasRewardItems()) {
            section["reward-items"] = contract.rewardItems().map { it.serialize() }
        }

        if (contract.metadata.isNotEmpty()) {
            val meta = section.createSection("metadata")
            for ((key, value) in contract.metadata) {
                meta[key] = value
            }
        }

        val serialized = ArrayList<Map<String, Any?>>()
        for (event in contract.events()) {
            serialized.add(event.toMap())
        }
        section["events"] = serialized
    }

    private fun readObjective(section: ConfigurationSection): ContractObjective? {
        val nested = section.getConfigurationSection("objective")
        if (nested != null) {
            val map = LinkedHashMap<String, Any?>()
            for (key in nested.getKeys(false)) {
                map[key] = nested[key]
            }
            return ContractObjective.fromMap(map)
        }
        val raw = section["objective"]
        return if (raw is Map<*, *>) ContractObjective.fromMap(raw) else null
    }

    private fun readDeliveryItems(section: ConfigurationSection): List<ItemStack> {
        return readItems(section, "delivery-items")
    }

    private fun readRewardItems(section: ConfigurationSection): List<ItemStack> {
        return readItems(section, "reward-items")
    }

    private fun readItems(section: ConfigurationSection, path: String): List<ItemStack> {
        val raw = section[path]
        if (raw !is List<*>) {
            return emptyList()
        }
        val result = ArrayList<ItemStack>()
        for (entry in raw) {
            try {
                when (entry) {
                    is ItemStack -> result.add(entry.clone())
                    is Map<*, *> -> {
                        val map = LinkedHashMap<String, Any>()
                        for ((key, value) in entry) {
                            if (key is String && value != null) {
                                map[key] = value
                            }
                        }
                        result.add(ItemStack.deserialize(map))
                    }
                }
            } catch (ex: RuntimeException) {
                logger.warning("Skipping malformed stored $path item: ${ex.message}")
            }
        }
        return result
    }

    private fun readBigDecimal(section: ConfigurationSection, path: String): BigDecimal {
        val raw = section[path] ?: return BigDecimal.ZERO
        if (raw is Number) {
            return BigDecimal.valueOf(raw.toDouble())
        }
        return try {
            BigDecimal(raw.toString())
        } catch (ex: NumberFormatException) {
            BigDecimal.ZERO
        }
    }

    private fun resolveName(uuid: UUID?, fallback: String?): String? {
        if (uuid == null) {
            return fallback
        }
        return try {
            val current = Bukkit.getOfflinePlayer(uuid).name
            current ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    private fun uuidOrNull(value: String?): UUID? {
        if (value.isNullOrBlank()) {
            return null
        }
        return UUID.fromString(value)
    }

    private fun nullableLong(section: ConfigurationSection, path: String): Long? =
        if (section.contains(path)) section.getLong(path) else null
}
