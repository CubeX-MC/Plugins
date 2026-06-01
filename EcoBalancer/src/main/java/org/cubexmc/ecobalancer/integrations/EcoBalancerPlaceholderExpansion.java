package org.cubexmc.ecobalancer.integrations;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.OfflinePlayer;
import org.cubexmc.ecobalancer.EcoBalancer;
import org.cubexmc.ecobalancer.policies.TaxPolicy;
import org.cubexmc.ecobalancer.tax.TaxLedgerService;
import org.cubexmc.ecobalancer.tax.TaxRunState;
import org.cubexmc.ecobalancer.utils.EconomicMetrics;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class EcoBalancerPlaceholderExpansion extends PlaceholderExpansion {
    private static final long CACHE_TTL_MILLIS = 5_000L;

    private final EcoBalancer plugin;
    private volatile CachedValue<TaxLedgerService.ServerTaxStats> serverStatsCache;
    private final Map<UUID, CachedValue<TaxLedgerService.PlayerTaxStats>> playerStatsCache = new ConcurrentHashMap<>();

    public EcoBalancerPlaceholderExpansion(EcoBalancer plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "ecobal";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }
        switch (params.toLowerCase(java.util.Locale.ROOT)) {
            case "tax_fund_balance":
                return EconomicMetrics.formatLargeNumber(getServerStats().taxFundBalance);
            case "tax_total_collected":
                return EconomicMetrics.formatLargeNumber(getServerStats().totalTaxCollected);
            case "tax_latest_collected":
                return EconomicMetrics.formatLargeNumber(getServerStats().latestTaxCollected);
            case "tax_latest_operation":
                return String.valueOf(getServerStats().latestOperationId);
            case "player_latest_tax":
                if (player == null) return "0";
                return EconomicMetrics.formatLargeNumber(getPlayerStats(player).latestTaxPaid);
            case "player_total_tax":
                if (player == null) return "0";
                return EconomicMetrics.formatLargeNumber(getPlayerStats(player).totalTaxPaid);
            case "tax_next_run":
                long next = plugin.getNextScheduledRunMillis();
                return next <= 0 ? "None" : new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(next));
            case "tax_active_policy":
                TaxPolicy policy = plugin.getPolicyManager().getActivePolicy();
                return policy == null ? "None" : policy.getName();
            case "tax_status":
                TaxRunState state = plugin.getTaxRunService().getState();
                return state.isRunning() ? "running" : "idle";
            case "gini":
                try {
                    return String.format("%.4f", EconomicMetrics.calculateGini(plugin.collectAllBalances()));
                } catch (Throwable t) {
                    return "0";
                }
            case "top1_concentration":
                try {
                    return String.format("%.2f", EconomicMetrics.calculateConcentration(plugin.collectAllBalances(), 1.0));
                } catch (Throwable t) {
                    return "0";
                }
            default:
                return null;
        }
    }

    private TaxLedgerService.ServerTaxStats getServerStats() {
        long now = System.currentTimeMillis();
        CachedValue<TaxLedgerService.ServerTaxStats> cached = serverStatsCache;
        if (cached != null && cached.expiresAt > now) {
            return cached.value;
        }
        TaxLedgerService.ServerTaxStats stats = plugin.getTaxLedgerService().getServerStats();
        serverStatsCache = new CachedValue<>(stats, now + CACHE_TTL_MILLIS);
        return stats;
    }

    private TaxLedgerService.PlayerTaxStats getPlayerStats(OfflinePlayer player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        CachedValue<TaxLedgerService.PlayerTaxStats> cached = playerStatsCache.get(uuid);
        if (cached != null && cached.expiresAt > now) {
            return cached.value;
        }
        TaxLedgerService.PlayerTaxStats stats = plugin.getTaxLedgerService().getPlayerStats(player);
        playerStatsCache.put(uuid, new CachedValue<>(stats, now + CACHE_TTL_MILLIS));
        return stats;
    }

    private static final class CachedValue<T> {
        private final T value;
        private final long expiresAt;

        private CachedValue(T value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
