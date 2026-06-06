package org.cubexmc.ecobalancer.policies

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.ecobalancer.EcoBalancer
import java.io.File
import java.util.Locale
import java.util.logging.Level

class PolicyManager(private val plugin: EcoBalancer) {
    private val policyDir = File(plugin.dataFolder, "policies")
    private val policies: MutableMap<String, TaxPolicy> = HashMap()
    private var activePolicyName = "default"

    fun initialize() {
        if (!policyDir.exists()) {
            policyDir.mkdirs()
        }

        loadPolicies()

        if (policies.isEmpty()) {
            createDefaultPolicy()
            loadPolicies()
        }

        plugin.logger.info("Loaded ${policies.size} tax policies.")
    }

    fun loadPolicies() {
        policies.clear()

        if (!policyDir.exists()) {
            return
        }

        val files = policyDir.listFiles { _, name -> name.endsWith(".yml") } ?: return

        for (file in files) {
            try {
                loadPolicyFromFile(file)
            } catch (exception: Exception) {
                plugin.logger.log(Level.SEVERE, "Failed to load policy file: ${file.name}", exception)
            }
        }
    }

    private fun loadPolicyFromFile(file: File) {
        val config = YamlConfiguration.loadConfiguration(file)

        if (config.contains("policies") && config.isConfigurationSection("policies")) {
            val root = config.getConfigurationSection("policies") ?: return
            for (key in root.getKeys(false)) {
                if (root.isConfigurationSection(key)) {
                    parsePolicy(key, root.getConfigurationSection(key) ?: continue)
                }
            }
        } else if (config.contains("schedule") || config.contains("brackets") || config.contains("settings")) {
            val name = config.getString("name", file.name.replace(".yml", "")) ?: file.name.replace(".yml", "")
            parsePolicy(name, config)
        } else {
            for (key in config.getKeys(false)) {
                if (config.isConfigurationSection(key)) {
                    val sec = config.getConfigurationSection(key) ?: continue
                    if (sec.contains("schedule") || sec.contains("brackets") || sec.contains("composition") || sec.contains("routine")) {
                        parsePolicy(key, sec)
                    }
                }
            }
        }
    }

    private fun parsePolicy(name: String, sec: ConfigurationSection) {
        val policy = TaxPolicy(name)

        policy.description = sec.getString("description", "Imported Policy")
        policy.isRoutine = sec.getBoolean("routine", true)
        policy.composition = sec.getStringList("composition")

        if (sec.contains("schedule")) {
            if (sec.isConfigurationSection("schedule")) {
                policy.scheduleType = sec.getString("schedule.type", "monthly")?.lowercase(Locale.getDefault())
                policy.checkTime = sec.getString("schedule.time", "00:00") ?: "00:00"
                policy.scheduleDaysOfWeek = sec.getIntegerList("schedule.days-of-week")
                policy.scheduleDatesOfMonth = sec.getIntegerList("schedule.dates-of-month")
            } else if (sec.isString("schedule")) {
                policy.scheduleType = sec.getString("schedule")?.lowercase(Locale.getDefault())
            }
        } else {
            policy.scheduleType = "monthly"
        }

        if (sec.contains("settings")) {
            val settings = sec.getConfigurationSection("settings")
            if (settings != null) {
                policy.maxDeductionPerPlayer = settings.getDouble("max-deduction", 0.0)
                policy.minBalanceProtection = settings.getDouble("min-balance-protection", 0.0)
                policy.isOnlyOfflinePlayers = settings.getBoolean("only-offline", false)
                policy.inactiveDaysToDeduct = settings.getInt("inactive-days-deduct", 0)
                policy.inactiveDaysToClear = settings.getInt("inactive-days-clear", 0)
                policy.isPercentileThresholds = settings.getBoolean("percentile", false)
                policy.exemptPermission = settings.getString("exempt-permission", "")
                policy.debtMode = settings.getString("debt-mode", "inherit")
            }
        } else {
            policy.maxDeductionPerPlayer = sec.getDouble("max-deduction-per-player", 0.0)
            policy.minBalanceProtection = sec.getDouble("min-balance-protection", 0.0)
            policy.exemptPermission = sec.getString("exempt-permission", "")
            policy.debtMode = sec.getString("debt-mode", "inherit")
        }

        val brackets: MutableList<Map<String, Any>> = ArrayList()
        if (sec.contains("brackets")) {
            val rawList = sec.getMapList("brackets")
            for (raw in rawList) {
                val nice: MutableMap<String, Any> = HashMap()
                if (raw.containsKey("threshold")) {
                    raw["threshold"]?.let { nice["threshold"] = it }
                }
                if (raw.containsKey("rate")) {
                    raw["rate"]?.let { nice["rate"] = it }
                }
                brackets.add(nice)
            }
        }
        policy.taxBrackets = brackets

        policies[name] = policy
    }

    private fun createDefaultPolicy() {
        val defFile = File(policyDir, "default.yml")

        if (defFile.exists()) {
            plugin.logger.warning("Found default.yml but failed to load any policies.")
            plugin.logger.warning("Renaming corrupted file and regenerating default policy...")
            val backup = File(policyDir, "default.yml.corrupted.${System.currentTimeMillis()}")
            if (!defFile.renameTo(backup)) {
                plugin.logger.severe("Failed to rename corrupted default.yml. Default policy creation aborted.")
                return
            }
        }

        try {
            val cfg = YamlConfiguration()
            cfg.set("name", "default")
            cfg.set("description", "Created by EcoBalancer")
            cfg.set("schedule.type", "monthly")
            cfg.set("schedule.time", "00:00")
            cfg.set("schedule.days-of-week", ArrayList<Int>())
            cfg.set("schedule.dates-of-month", ArrayList<Int>())
            cfg.set("settings.max-deduction", 10000.0)
            cfg.set("settings.min-balance-protection", 100.0)
            cfg.set("settings.only-offline", false)
            cfg.set("settings.inactive-days-deduct", 0)
            cfg.set("settings.inactive-days-clear", 0)
            cfg.set("settings.percentile", false)
            cfg.set("settings.exempt-permission", "")
            cfg.set("settings.debt-mode", "inherit")

            val brackets: MutableList<Map<String, Any>> = ArrayList()
            val b1: MutableMap<String, Any> = HashMap()
            b1["threshold"] = 1000.0
            b1["rate"] = 0.05
            brackets.add(b1)
            cfg.set("brackets", brackets)

            cfg.save(defFile)
            plugin.logger.info("Created new default policy at policies/default.yml")
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    fun getActivePolicy(): TaxPolicy? {
        if (policies.containsKey(activePolicyName)) {
            return policies[activePolicyName]
        }
        if (policies.isNotEmpty()) {
            return policies.values.iterator().next()
        }
        return null
    }

    fun getPolicy(name: String?): TaxPolicy? = policies[name]

    fun getPolicyNames(): List<String> = ArrayList(policies.keys)

    fun setActivePolicy(name: String) {
        if (policies.containsKey(name)) {
            activePolicyName = name
            plugin.config.set("active-policy", name)
            plugin.saveConfig()
        }
    }

    fun savePolicy(name: String) {
        val policy = policies[name] ?: return

        val file = File(policyDir, "$name.yml")
        val config = YamlConfiguration()

        config.set("name", policy.name)
        config.set("description", policy.description)
        config.set("schedule.type", policy.scheduleType)
        config.set("schedule.time", policy.checkTime)
        config.set("schedule.days-of-week", policy.scheduleDaysOfWeek)
        config.set("schedule.dates-of-month", policy.scheduleDatesOfMonth)
        config.set("settings.max-deduction", policy.maxDeductionPerPlayer)
        config.set("settings.min-balance-protection", policy.minBalanceProtection)
        config.set("settings.only-offline", policy.isOnlyOfflinePlayers)
        config.set("settings.inactive-days-deduct", policy.inactiveDaysToDeduct)
        config.set("settings.inactive-days-clear", policy.inactiveDaysToClear)
        config.set("settings.percentile", policy.isPercentileThresholds)
        config.set("settings.exempt-permission", policy.exemptPermission)
        config.set("settings.debt-mode", policy.debtMode)

        val brackets: MutableList<Map<String, Any?>> = ArrayList()
        for (bracket in policy.taxBrackets) {
            val simple: MutableMap<String, Any?> = HashMap()
            simple["threshold"] = bracket["threshold"]
            simple["rate"] = bracket["rate"]
            brackets.add(simple)
        }
        config.set("brackets", brackets)

        try {
            config.save(file)
        } catch (exception: Exception) {
            plugin.logger.log(Level.SEVERE, "Failed to save policy $name", exception)
        }
    }

    fun createPolicy(name: String): Boolean {
        if (policies.containsKey(name)) {
            return false
        }
        val policy = TaxPolicy(name)
        policy.description = "Created by EcoBalancer GUI/Command"
        policy.scheduleType = "monthly"
        policy.checkTime = "00:00"
        policy.scheduleDaysOfWeek = ArrayList()
        policy.scheduleDatesOfMonth = ArrayList()
        policy.maxDeductionPerPlayer = 10000.0
        policy.minBalanceProtection = 100.0
        policy.isOnlyOfflinePlayers = false
        policy.inactiveDaysToDeduct = 0
        policy.inactiveDaysToClear = 0
        policy.isPercentileThresholds = false
        policy.exemptPermission = ""
        policy.debtMode = "inherit"
        policy.taxBrackets = ArrayList()

        policies[name] = policy
        savePolicy(name)
        return true
    }

    fun deletePolicy(name: String): Boolean {
        if (!policies.containsKey(name)) {
            return false
        }
        policies.remove(name)
        val file = File(policyDir, "$name.yml")
        if (file.exists()) {
            file.delete()
        }
        if (activePolicyName == name) {
            activePolicyName = ""
            plugin.config.set("active-policy", "")
            plugin.saveConfig()
        }
        return true
    }

    fun clonePolicy(source: String, target: String): Boolean {
        if (!policies.containsKey(source) || policies.containsKey(target)) {
            return false
        }
        val original = policies[source] ?: return false
        val clone = TaxPolicy(target)

        clone.description = "${original.description} (Clone)"
        clone.scheduleType = original.scheduleType
        clone.checkTime = original.checkTime
        clone.scheduleDaysOfWeek = ArrayList(original.scheduleDaysOfWeek)
        clone.scheduleDatesOfMonth = ArrayList(original.scheduleDatesOfMonth)
        clone.maxDeductionPerPlayer = original.maxDeductionPerPlayer
        clone.minBalanceProtection = original.minBalanceProtection
        clone.isOnlyOfflinePlayers = original.isOnlyOfflinePlayers
        clone.inactiveDaysToDeduct = original.inactiveDaysToDeduct
        clone.inactiveDaysToClear = original.inactiveDaysToClear
        clone.isPercentileThresholds = original.isPercentileThresholds
        clone.exemptPermission = original.exemptPermission
        clone.debtMode = original.debtMode

        val brackets: MutableList<Map<String, Any>> = ArrayList()
        for (bracket in original.taxBrackets) {
            val b: MutableMap<String, Any> = HashMap()
            bracket["threshold"]?.let { b["threshold"] = it }
            bracket["rate"]?.let { b["rate"] = it }
            brackets.add(b)
        }
        clone.taxBrackets = brackets

        policies[target] = clone
        savePolicy(target)
        return true
    }

    fun renamePolicy(oldName: String, newName: String): Boolean {
        if (!policies.containsKey(oldName) || policies.containsKey(newName)) {
            return false
        }

        val original = policies[oldName] ?: return false
        val renamed = TaxPolicy(newName)

        renamed.description = original.description
        renamed.isRoutine = original.isRoutine
        renamed.scheduleType = original.scheduleType
        renamed.checkTime = original.checkTime
        renamed.scheduleDaysOfWeek = ArrayList(original.scheduleDaysOfWeek)
        renamed.scheduleDatesOfMonth = ArrayList(original.scheduleDatesOfMonth)
        renamed.maxDeductionPerPlayer = original.maxDeductionPerPlayer
        renamed.minBalanceProtection = original.minBalanceProtection
        renamed.isOnlyOfflinePlayers = original.isOnlyOfflinePlayers
        renamed.inactiveDaysToDeduct = original.inactiveDaysToDeduct
        renamed.inactiveDaysToClear = original.inactiveDaysToClear
        renamed.isPercentileThresholds = original.isPercentileThresholds
        renamed.exemptPermission = original.exemptPermission
        renamed.debtMode = original.debtMode

        val brackets: MutableList<Map<String, Any>> = ArrayList()
        for (bracket in original.taxBrackets) {
            val b: MutableMap<String, Any> = HashMap()
            bracket["threshold"]?.let { b["threshold"] = it }
            bracket["rate"]?.let { b["rate"] = it }
            brackets.add(b)
        }
        renamed.taxBrackets = brackets

        policies.remove(oldName)
        val oldFile = File(policyDir, "$oldName.yml")
        if (oldFile.exists()) {
            oldFile.delete()
        }

        policies[newName] = renamed
        savePolicy(newName)

        if (activePolicyName == oldName) {
            activePolicyName = newName
            plugin.config.set("active-policy", newName)
            plugin.saveConfig()
        }

        return true
    }
}
