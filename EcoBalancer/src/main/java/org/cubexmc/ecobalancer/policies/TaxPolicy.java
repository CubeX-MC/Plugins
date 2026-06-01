package org.cubexmc.ecobalancer.policies;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;

public class TaxPolicy {
    private String name;
    private String description;
    private String scheduleType; // "daily", "weekly", "monthly", "once"
    private String checkTime = "00:00"; // default midnight
    private List<Integer> scheduleDaysOfWeek = new ArrayList<>();
    private List<Integer> scheduleDatesOfMonth = new ArrayList<>();

    // Limits
    private double maxDeductionPerPlayer = 0.0;
    private double minBalanceProtection = 100.0;
    private boolean onlyOfflinePlayers = false; // Default false

    // Filters (Optional implementation for future)
    private int inactiveDaysToDeduct = 0;
    private int inactiveDaysToClear = 0;

    // Brackets: List of Maps ({threshold: 1000, rate: 0.1})
    private List<Map<String, Object>> taxBrackets = new ArrayList<>();

    // Whether a percentile logic is used instead of absolute
    private boolean percentileThresholds = false;

    // Routine execution (true = periodic as per schedule, false = manual only)
    private boolean routine = true;

    // Composite policies (list of other policy names to aggregate)
    private List<String> composition = new ArrayList<>();

    // Optional safety controls. Empty exemptPermission means use global defaults.
    private String exemptPermission = "";
    private String debtMode = "inherit";

    // Constructor
    public TaxPolicy(String name) {
        this.name = name;
        this.description = "Custom Tax Policy";
        this.scheduleType = "monthly";
    }

    public TaxPolicy() {
        // No-arg constructor for serialization
    }

    // --- Logic for calculation ---

    /**
     * Calculate tax for a given balance.
     * 
     * @param balance        The player's balance.
     * @param policyProvider A functional interface or manager to look up other
     *                       policies for composition.
     *                       Can be null if no composition is used.
     * @return The calculated tax amount.
     */
    public double calculateTax(double balance, java.util.function.Function<String, TaxPolicy> policyProvider) {
        double totalTax = 0.0;

        // 1. Calculate base tax from this policy's brackets (if any)
        totalTax += calculateBaseTax(balance);

        // 2. Calculate tax from composed policies
        if (composition != null && !composition.isEmpty() && policyProvider != null) {
            for (String policyName : composition) {
                // Avoid self-reference to prevent infinite recursion (basic check)
                if (policyName.equals(this.name))
                    continue;

                TaxPolicy subPolicy = policyProvider.apply(policyName);
                if (subPolicy != null) {
                    // Recursive call, passing the same provider
                    totalTax += subPolicy.calculateTax(balance, policyProvider);
                }
            }
        }

        // Apply max deduction cap (on the TOTAL tax for THIS policy level)
        // Note: Sub-policies might have already capped their own contribution.
        // Whether we cap the SUM or just cap locally is a design choice.
        // Usually, a "Max Deduction" on a composite policy means "This combo shouldn't
        // take more than X".
        if (maxDeductionPerPlayer > 0 && totalTax > maxDeductionPerPlayer) {
            totalTax = maxDeductionPerPlayer;
        }

        // Balance protection check (on final result)
        // If balance - totalTax < minProtection, reduce tax.
        if (balance - totalTax < minBalanceProtection) {
            totalTax = Math.max(0.0, balance - minBalanceProtection);
        }

        return totalTax;
    }

    /**
     * Calculates tax based strictly on this policy's brackets/rates.
     */
    public double calculateBaseTax(double balance) {
        if (balance <= minBalanceProtection)
            return 0.0;

        double rate = 0.0;
        double bestThreshold = -1.0;

        for (Map<String, Object> bracket : taxBrackets) {
            Object thObj = bracket.get("threshold");
            double threshold;
            if (thObj instanceof Number) {
                threshold = ((Number) thObj).doubleValue();
            } else {
                threshold = Double.MAX_VALUE; // Treat null/other as infinity
            }

            // If threshold matches or we found a tighter lower-bound
            // Actually, strict logic: find the bracket with highest threshold that is <=
            // balance
            // Provided brackets: 10k, 1m, null(inf).
            // If 150k balance: matches 10k (<150k) and 100k (<150k). 100k > 10k. So take
            // 100k rate.
            if (balance >= threshold && threshold > bestThreshold) {
                bestThreshold = threshold;
                rate = ((Number) bracket.get("rate")).doubleValue();
            }
        }

        return balance * rate;
    }

    // --- Getters & Setters ---

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getScheduleType() {
        return scheduleType;
    }

    public void setScheduleType(String scheduleType) {
        this.scheduleType = scheduleType;
    }

    public String getCheckTime() {
        return checkTime;
    }

    public void setCheckTime(String checkTime) {
        this.checkTime = checkTime;
    }

    public List<Integer> getScheduleDaysOfWeek() {
        return scheduleDaysOfWeek;
    }

    public void setScheduleDaysOfWeek(List<Integer> days) {
        this.scheduleDaysOfWeek = days;
    }

    public List<Integer> getScheduleDatesOfMonth() {
        return scheduleDatesOfMonth;
    }

    public void setScheduleDatesOfMonth(List<Integer> dates) {
        this.scheduleDatesOfMonth = dates;
    }

    public double getMaxDeductionPerPlayer() {
        return maxDeductionPerPlayer;
    }

    public void setMaxDeductionPerPlayer(double max) {
        this.maxDeductionPerPlayer = max;
    }

    public double getMinBalanceProtection() {
        return minBalanceProtection;
    }

    public void setMinBalanceProtection(double min) {
        this.minBalanceProtection = min;
    }

    public boolean isOnlyOfflinePlayers() {
        return onlyOfflinePlayers;
    }

    public void setOnlyOfflinePlayers(boolean only) {
        this.onlyOfflinePlayers = only;
    }

    public int getInactiveDaysToDeduct() {
        return inactiveDaysToDeduct;
    }

    public void setInactiveDaysToDeduct(int days) {
        this.inactiveDaysToDeduct = days;
    }

    public int getInactiveDaysToClear() {
        return inactiveDaysToClear;
    }

    public void setInactiveDaysToClear(int days) {
        this.inactiveDaysToClear = days;
    }

    public List<Map<String, Object>> getTaxBrackets() {
        return taxBrackets;
    }

    public void setTaxBrackets(List<Map<String, Object>> brackets) {
        this.taxBrackets = brackets;
    }

    public boolean isPercentileThresholds() {
        return percentileThresholds;
    }

    public void setPercentileThresholds(boolean p) {
        this.percentileThresholds = p;
    }

    public boolean isRoutine() {
        return routine;
    }

    public void setRoutine(boolean routine) {
        this.routine = routine;
    }

    public List<String> getComposition() {
        return composition;
    }

    public void setComposition(List<String> composition) {
        this.composition = composition;
    }

    public String getExemptPermission() {
        return exemptPermission;
    }

    public void setExemptPermission(String exemptPermission) {
        this.exemptPermission = exemptPermission == null ? "" : exemptPermission;
    }

    public String getDebtMode() {
        return debtMode;
    }

    public void setDebtMode(String debtMode) {
        this.debtMode = debtMode == null ? "inherit" : debtMode;
    }

    @Override
    public String toString() {
        return "TaxPolicy{" + "name='" + name + '\'' + ", schedule='" + scheduleType + '\'' + ", routine=" + routine
                + '}';
    }
}
