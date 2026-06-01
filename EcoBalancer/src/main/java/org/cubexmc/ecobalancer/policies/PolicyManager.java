package org.cubexmc.ecobalancer.policies;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.ecobalancer.EcoBalancer;

import java.io.File;
import java.util.*;
import java.util.logging.Level;

public class PolicyManager {
    private final EcoBalancer plugin;
    private final File policyDir;

    // Map of Policy Name -> TaxPolicy
    private final Map<String, TaxPolicy> policies = new HashMap<>();

    // Currently active policy name
    private String activePolicyName = "default";

    public PolicyManager(EcoBalancer plugin) {
        this.plugin = plugin;
        this.policyDir = new File(plugin.getDataFolder(), "policies");
    }

    public void initialize() {
        if (!policyDir.exists()) {
            policyDir.mkdirs();
        }

        loadPolicies();

        // If no policies loaded, try create default
        if (policies.isEmpty()) {
            createDefaultPolicy();
            loadPolicies();
        }

        plugin.getLogger().info("Loaded " + policies.size() + " tax policies.");
    }

    public void loadPolicies() {
        policies.clear();

        if (!policyDir.exists())
            return;

        File[] files = policyDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null)
            return;

        for (File file : files) {
            try {
                loadPolicyFromFile(file);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load policy file: " + file.getName(), e);
            }
        }
    }

    private void loadPolicyFromFile(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // User Requirement: "Scan folder and read ALL policies"
        // Also supports: "default.yml can store multiple policies"

        // Priority 1: Check for "policies" section (Explicit map of policies)
        if (config.contains("policies") && config.isConfigurationSection("policies")) {
            ConfigurationSection root = config.getConfigurationSection("policies");
            for (String key : root.getKeys(false)) {
                if (root.isConfigurationSection(key)) {
                    parsePolicy(key, root.getConfigurationSection(key));
                }
            }
        }

        // Priority 2: Check if ROOT ITSELF is a policy (has "schedule", "brackets", or
        // "routine")
        // Note: A file *could* contain "policies" AND be a policy itself, but that's
        // ambiguous.
        // We assume if "policies" exists, it's a container. If not, check root.
        else if (config.contains("schedule") || config.contains("brackets") || config.contains("settings")) {
            // Single policy file. Use filename (minus ext) as name if "name" not set
            String name = config.getString("name", file.getName().replace(".yml", ""));
            parsePolicy(name, config);
        }

        // Priority 3: Fallback - treat topline keys as potential policies
        // (Scenario: User puts multiple policies at root level in default.yml)
        else {
            for (String key : config.getKeys(false)) {
                if (config.isConfigurationSection(key)) {
                    ConfigurationSection sec = config.getConfigurationSection(key);
                    // Minimal heuristic: does it have "schedule", "brackets", "composition" or
                    // "routine"?
                    if (sec.contains("schedule") || sec.contains("brackets") || sec.contains("composition")
                            || sec.contains("routine")) {
                        parsePolicy(key, sec);
                    }
                }
            }
        }
    }

    private void parsePolicy(String name, ConfigurationSection sec) {
        TaxPolicy policy = new TaxPolicy(name);

        policy.setDescription(sec.getString("description", "Imported Policy"));
        policy.setRoutine(sec.getBoolean("routine", true)); // Default true
        policy.setComposition(sec.getStringList("composition"));

        // Schedule
        if (sec.contains("schedule")) {
            if (sec.isConfigurationSection("schedule")) {
                policy.setScheduleType(sec.getString("schedule.type", "monthly").toLowerCase());
                policy.setCheckTime(sec.getString("schedule.time", "00:00"));
                policy.setScheduleDaysOfWeek(sec.getIntegerList("schedule.days-of-week"));
                policy.setScheduleDatesOfMonth(sec.getIntegerList("schedule.dates-of-month"));
            } else if (sec.isString("schedule")) {
                policy.setScheduleType(sec.getString("schedule").toLowerCase());
            }
        } else {
            // If routine=false, maybe no schedule. But strict default is "monthly".
            policy.setScheduleType("monthly");
        }

        // Settings
        if (sec.contains("settings")) {
            ConfigurationSection settings = sec.getConfigurationSection("settings");
            policy.setMaxDeductionPerPlayer(settings.getDouble("max-deduction", 0.0));
            policy.setMinBalanceProtection(settings.getDouble("min-balance-protection", 0.0));
            policy.setOnlyOfflinePlayers(settings.getBoolean("only-offline", false));
            policy.setInactiveDaysToDeduct(settings.getInt("inactive-days-deduct", 0));
            policy.setInactiveDaysToClear(settings.getInt("inactive-days-clear", 0));
            policy.setPercentileThresholds(settings.getBoolean("percentile", false));
            policy.setExemptPermission(settings.getString("exempt-permission", ""));
            policy.setDebtMode(settings.getString("debt-mode", "inherit"));
        } else {
            // Attempt root-level read for backward compat logic helper (rare now but safe)
            policy.setMaxDeductionPerPlayer(sec.getDouble("max-deduction-per-player", 0.0));
            policy.setMinBalanceProtection(sec.getDouble("min-balance-protection", 0.0));
            policy.setExemptPermission(sec.getString("exempt-permission", ""));
            policy.setDebtMode(sec.getString("debt-mode", "inherit"));
        }

        // Brackets
        List<Map<String, Object>> brackets = new ArrayList<>();
        if (sec.contains("brackets")) {
            List<Map<?, ?>> rawList = sec.getMapList("brackets");
            for (Map<?, ?> raw : rawList) {
                Map<String, Object> nice = new HashMap<>();
                if (raw.containsKey("threshold"))
                    nice.put("threshold", raw.get("threshold"));
                if (raw.containsKey("rate"))
                    nice.put("rate", raw.get("rate"));
                brackets.add(nice);
            }
        }
        policy.setTaxBrackets(brackets);

        policies.put(name, policy);
    }

    private void createDefaultPolicy() {
        File defFile = new File(policyDir, "default.yml");

        // If file exists but we are here, it means no policies were loaded.
        // This implies the file is corrupted or empty.
        if (defFile.exists()) {
            plugin.getLogger().warning("Found default.yml but failed to load any policies.");
            plugin.getLogger().warning("Renaming corrupted file and regenerating default policy...");
            File backup = new File(policyDir, "default.yml.corrupted." + System.currentTimeMillis());
            if (!defFile.renameTo(backup)) {
                plugin.getLogger().severe("Failed to rename corrupted default.yml. Default policy creation aborted.");
                return;
            }
        }

        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("name", "default"); // Force lowercase 'default' to match activePolicyName default
            cfg.set("description", "Created by EcoBalancer");

            // Schedule section
            cfg.set("schedule.type", "monthly");
            cfg.set("schedule.time", "00:00");
            cfg.set("schedule.days-of-week", new ArrayList<>());
            cfg.set("schedule.dates-of-month", new ArrayList<>());

            // Settings section
            cfg.set("settings.max-deduction", 10000.0);
            cfg.set("settings.min-balance-protection", 100.0);
            cfg.set("settings.only-offline", false);
            cfg.set("settings.inactive-days-deduct", 0);
            cfg.set("settings.inactive-days-clear", 0);
            cfg.set("settings.percentile", false);
            cfg.set("settings.exempt-permission", "");
            cfg.set("settings.debt-mode", "inherit");

            List<Map<String, Object>> brackets = new ArrayList<>();
            Map<String, Object> b1 = new HashMap<>();
            b1.put("threshold", 1000.0);
            b1.put("rate", 0.05);
            brackets.add(b1);

            cfg.set("brackets", brackets);

            cfg.save(defFile);
            plugin.getLogger().info("Created new default policy at policies/default.yml");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public TaxPolicy getActivePolicy() {
        // Find default or first available
        if (policies.containsKey(activePolicyName)) {
            return policies.get(activePolicyName);
        }
        if (!policies.isEmpty()) {
            return policies.values().iterator().next();
        }
        return null; // Should not happen after init
    }

    public TaxPolicy getPolicy(String name) {
        return policies.get(name);
    }

    public List<String> getPolicyNames() {
        return new ArrayList<>(policies.keySet());
    }

    public void setActivePolicy(String name) {
        if (policies.containsKey(name)) {
            this.activePolicyName = name;
            // Persist choice to main config
            plugin.getConfig().set("active-policy", name);
            plugin.saveConfig();
        }
    }

    public void savePolicy(String name) {
        TaxPolicy policy = policies.get(name);
        if (policy == null)
            return;

        // Find file. For simplicity, we assume one-file-per-policy with name matching
        // If loaded from multi-policy file, this simplistic save might be risky.
        // For this refactor, we will save to "policies/<name>.yml" structure.

        File file = new File(policyDir, name + ".yml");
        YamlConfiguration config = new YamlConfiguration();

        config.set("name", policy.getName());
        config.set("description", policy.getDescription());

        config.set("schedule.type", policy.getScheduleType());
        config.set("schedule.time", policy.getCheckTime());
        config.set("schedule.days-of-week", policy.getScheduleDaysOfWeek());
        config.set("schedule.dates-of-month", policy.getScheduleDatesOfMonth());

        config.set("settings.max-deduction", policy.getMaxDeductionPerPlayer());
        config.set("settings.min-balance-protection", policy.getMinBalanceProtection());
        config.set("settings.only-offline", policy.isOnlyOfflinePlayers());
        config.set("settings.inactive-days-deduct", policy.getInactiveDaysToDeduct());
        config.set("settings.inactive-days-clear", policy.getInactiveDaysToClear());
        config.set("settings.percentile", policy.isPercentileThresholds());
        config.set("settings.exempt-permission", policy.getExemptPermission());
        config.set("settings.debt-mode", policy.getDebtMode());

        List<Map<String, Object>> brackets = new ArrayList<>();
        for (Map<String, Object> bracket : policy.getTaxBrackets()) {
            Map<String, Object> simple = new HashMap<>();
            simple.put("threshold", bracket.get("threshold"));
            simple.put("rate", bracket.get("rate"));
            brackets.add(simple);
        }
        config.set("brackets", brackets);

        try {
            config.save(file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save policy " + name, e);
        }
    }

    public boolean createPolicy(String name) {
        if (policies.containsKey(name)) {
            return false;
        }
        TaxPolicy policy = new TaxPolicy(name);
        policy.setDescription("Created by EcoBalancer GUI/Command");
        policy.setScheduleType("monthly");
        policy.setCheckTime("00:00");
        policy.setScheduleDaysOfWeek(new ArrayList<>());
        policy.setScheduleDatesOfMonth(new ArrayList<>());
        policy.setMaxDeductionPerPlayer(10000.0);
        policy.setMinBalanceProtection(100.0);
        policy.setOnlyOfflinePlayers(false);
        policy.setInactiveDaysToDeduct(0);
        policy.setInactiveDaysToClear(0);
        policy.setPercentileThresholds(false);
        policy.setExemptPermission("");
        policy.setDebtMode("inherit");
        policy.setTaxBrackets(new ArrayList<>());
        
        policies.put(name, policy);
        savePolicy(name);
        return true;
    }

    public boolean deletePolicy(String name) {
        if (!policies.containsKey(name)) {
            return false;
        }
        policies.remove(name);
        File file = new File(policyDir, name + ".yml");
        if (file.exists()) {
            file.delete();
        }
        if (activePolicyName.equals(name)) {
            activePolicyName = ""; 
            plugin.getConfig().set("active-policy", "");
            plugin.saveConfig();
        }
        return true;
    }

    public boolean clonePolicy(String source, String target) {
        if (!policies.containsKey(source) || policies.containsKey(target)) {
            return false;
        }
        TaxPolicy original = policies.get(source);
        TaxPolicy clone = new TaxPolicy(target);
        
        clone.setDescription(original.getDescription() + " (Clone)");
        clone.setScheduleType(original.getScheduleType());
        clone.setCheckTime(original.getCheckTime());
        clone.setScheduleDaysOfWeek(new ArrayList<>(original.getScheduleDaysOfWeek()));
        clone.setScheduleDatesOfMonth(new ArrayList<>(original.getScheduleDatesOfMonth()));
        clone.setMaxDeductionPerPlayer(original.getMaxDeductionPerPlayer());
        clone.setMinBalanceProtection(original.getMinBalanceProtection());
        clone.setOnlyOfflinePlayers(original.isOnlyOfflinePlayers());
        clone.setInactiveDaysToDeduct(original.getInactiveDaysToDeduct());
        clone.setInactiveDaysToClear(original.getInactiveDaysToClear());
        clone.setPercentileThresholds(original.isPercentileThresholds());
        clone.setExemptPermission(original.getExemptPermission());
        clone.setDebtMode(original.getDebtMode());
        
        List<Map<String, Object>> brackets = new ArrayList<>();
        for (Map<String, Object> bracket : original.getTaxBrackets()) {
            Map<String, Object> b = new HashMap<>();
            b.put("threshold", bracket.get("threshold"));
            b.put("rate", bracket.get("rate"));
            brackets.add(b);
        }
        clone.setTaxBrackets(brackets);
        
        policies.put(target, clone);
        savePolicy(target);
        return true;
    }

    public boolean renamePolicy(String oldName, String newName) {
        if (!policies.containsKey(oldName) || policies.containsKey(newName)) {
            return false;
        }

        // Clone it but keep same exact properties, update the name
        TaxPolicy original = policies.get(oldName);
        TaxPolicy renamed = new TaxPolicy(newName);
        
        renamed.setDescription(original.getDescription());
        renamed.setRoutine(original.isRoutine());
        renamed.setScheduleType(original.getScheduleType());
        renamed.setCheckTime(original.getCheckTime());
        renamed.setScheduleDaysOfWeek(new ArrayList<>(original.getScheduleDaysOfWeek()));
        renamed.setScheduleDatesOfMonth(new ArrayList<>(original.getScheduleDatesOfMonth()));
        renamed.setMaxDeductionPerPlayer(original.getMaxDeductionPerPlayer());
        renamed.setMinBalanceProtection(original.getMinBalanceProtection());
        renamed.setOnlyOfflinePlayers(original.isOnlyOfflinePlayers());
        renamed.setInactiveDaysToDeduct(original.getInactiveDaysToDeduct());
        renamed.setInactiveDaysToClear(original.getInactiveDaysToClear());
        renamed.setPercentileThresholds(original.isPercentileThresholds());
        renamed.setExemptPermission(original.getExemptPermission());
        renamed.setDebtMode(original.getDebtMode());
        
        List<Map<String, Object>> brackets = new ArrayList<>();
        for (Map<String, Object> bracket : original.getTaxBrackets()) {
            Map<String, Object> b = new HashMap<>();
            b.put("threshold", bracket.get("threshold"));
            b.put("rate", bracket.get("rate"));
            brackets.add(b);
        }
        renamed.setTaxBrackets(brackets);
        
        // Remove old policy and delete its file
        policies.remove(oldName);
        File oldFile = new File(policyDir, oldName + ".yml");
        if (oldFile.exists()) {
            oldFile.delete();
        }

        // Save new policy
        policies.put(newName, renamed);
        savePolicy(newName);

        // Update active policy if needed
        if (activePolicyName.equals(oldName)) {
            activePolicyName = newName;
            plugin.getConfig().set("active-policy", newName);
            plugin.saveConfig();
        }
        
        return true;
    }
}
