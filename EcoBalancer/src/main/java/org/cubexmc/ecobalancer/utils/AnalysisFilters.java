package org.cubexmc.ecobalancer.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.*;

/**
 * Shared filter parsing and balance collection for analysis commands.
 */
public final class AnalysisFilters {
    private AnalysisFilters() {}

    public static final class FilterCriteria {
        public Integer activeWithinDays;     // d:N
        public Integer minPlaytimeHours;     // p:N
        public Double minBalance;            // l:X
        public Double maxBalance;            // u:X
        public Double minPercentile;         // lr:P (0-100)
        public Double maxPercentile;         // ur:P (0-100)
    }

    public static final class ParseResult {
        public final FilterCriteria criteria;
        public final List<String> remainingArgs;
        public ParseResult(FilterCriteria c, List<String> rest) {
            this.criteria = c; this.remainingArgs = rest;
        }
    }

    public static ParseResult parse(String[] args) {
        FilterCriteria c = new FilterCriteria();
        List<String> rest = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if (arg == null) continue;
                int idx = arg.indexOf(':');
                if (idx <= 0 || idx == arg.length() - 1) { rest.add(arg); continue; }
                String key = arg.substring(0, idx).toLowerCase(Locale.ROOT);
                String val = arg.substring(idx + 1);
                try {
                    switch (key) {
                        case "d": {
                            int n = Integer.parseInt(val);
                            if (n > 0) c.activeWithinDays = n;
                            break;
                        }
                        case "p": {
                            int n = Integer.parseInt(val);
                            if (n >= 0) c.minPlaytimeHours = n;
                            break;
                        }
                        case "l": {
                            c.minBalance = Double.parseDouble(val);
                            break;
                        }
                        case "u": {
                            c.maxBalance = Double.parseDouble(val);
                            break;
                        }
                        case "lr": {
                            double p = Double.parseDouble(val);
                            if (p < 0) p = 0; if (p > 100) p = 100;
                            c.minPercentile = p;
                            break;
                        }
                        case "ur": {
                            double p = Double.parseDouble(val);
                            if (p < 0) p = 0; if (p > 100) p = 100;
                            c.maxPercentile = p;
                            break;
                        }
                        default:
                            rest.add(arg);
                    }
                } catch (NumberFormatException ex) {
                    rest.add(arg);
                }
            }
        }
        return new ParseResult(c, rest);
    }

    /**
     * Collect balances filtered by criteria. Percentile thresholds are computed on the
     * set that already satisfies activity filters (d and p) but before absolute l/u bounds.
     */
    public static List<Double> collectFilteredBalances(FilterCriteria criteria, String statsWorldName) {
        if (criteria == null) criteria = new FilterCriteria();

        List<PlayerEntry> candidates = new ArrayList<>();
        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        long now = System.currentTimeMillis();
        long cutoff = (criteria.activeWithinDays == null) ? Long.MIN_VALUE : (now - criteria.activeWithinDays * 24L * 60 * 60 * 1000);

        for (OfflinePlayer p : players) {
            try {
                if (criteria.activeWithinDays != null && p.getLastPlayed() < cutoff) continue;
                if (!VaultUtils.hasAccount(p)) continue;

                if (criteria.minPlaytimeHours != null) {
                    PlaytimeUtils.ensureLoadedFor(p.getUniqueId(), statsWorldName);
                    double hours = PlaytimeUtils.getPlaytimeHours(p.getUniqueId());
                    if (hours < criteria.minPlaytimeHours) continue;
                }

                double bal = VaultUtils.getBalance(p);
                if (bal < 0) continue;
                candidates.add(new PlayerEntry(p, bal));
            } catch (Throwable t) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to process filtered balance candidate: " + t.getMessage());
            }
        }

        // Compute percentile bounds if needed
        double minBound = (criteria.minBalance != null) ? criteria.minBalance : Double.NEGATIVE_INFINITY;
        double maxBound = (criteria.maxBalance != null) ? criteria.maxBalance : Double.POSITIVE_INFINITY;

        if (criteria.minPercentile != null || criteria.maxPercentile != null) {
            List<Double> sorted = new ArrayList<>();
            for (PlayerEntry e : candidates) sorted.add(e.balance);
            Collections.sort(sorted);
            if (criteria.minPercentile != null) {
                double lb = percentileValue(sorted, criteria.minPercentile);
                if (lb > minBound) minBound = lb;
            }
            if (criteria.maxPercentile != null) {
                double ub = percentileValue(sorted, criteria.maxPercentile);
                if (ub < maxBound) maxBound = ub;
            }
        }

        if (minBound > maxBound) return Collections.emptyList();

        List<Double> result = new ArrayList<>();
        for (PlayerEntry e : candidates) {
            if (e.balance >= minBound && e.balance <= maxBound) result.add(e.balance);
        }
        return result;
    }

    /**
     * Collect players filtered by criteria, using the same semantics as balances.
     * Percentiles are computed on the candidate set after d/p filters.
     */
    public static List<OfflinePlayer> collectFilteredPlayers(FilterCriteria criteria, String statsWorldName) {
        if (criteria == null) criteria = new FilterCriteria();

        List<OfflinePlayer> candidates = new ArrayList<>();
        List<Double> candidateBalances = new ArrayList<>();
        OfflinePlayer[] players = Bukkit.getOfflinePlayers();
        long now = System.currentTimeMillis();
        long cutoff = (criteria.activeWithinDays == null) ? Long.MIN_VALUE : (now - criteria.activeWithinDays * 24L * 60 * 60 * 1000);

        for (OfflinePlayer p : players) {
            try {
                if (criteria.activeWithinDays != null && p.getLastPlayed() < cutoff) continue;
                if (!VaultUtils.hasAccount(p)) continue;
                if (criteria.minPlaytimeHours != null) {
                    PlaytimeUtils.ensureLoadedFor(p.getUniqueId(), statsWorldName);
                    double hours = PlaytimeUtils.getPlaytimeHours(p.getUniqueId());
                    if (hours < criteria.minPlaytimeHours) continue;
                }
                double bal = VaultUtils.getBalance(p);
                if (bal < 0) continue;
                candidates.add(p);
                candidateBalances.add(bal);
            } catch (Throwable t) {
                Bukkit.getLogger().fine("[EcoBalancer] Failed to process filtered player candidate: " + t.getMessage());
            }
        }

        double minBound = (criteria.minBalance != null) ? criteria.minBalance : Double.NEGATIVE_INFINITY;
        double maxBound = (criteria.maxBalance != null) ? criteria.maxBalance : Double.POSITIVE_INFINITY;

        if (criteria.minPercentile != null || criteria.maxPercentile != null) {
            List<Double> sorted = new ArrayList<>(candidateBalances);
            Collections.sort(sorted);
            if (criteria.minPercentile != null) {
                double lb = percentileValue(sorted, criteria.minPercentile);
                if (lb > minBound) minBound = lb;
            }
            if (criteria.maxPercentile != null) {
                double ub = percentileValue(sorted, criteria.maxPercentile);
                if (ub < maxBound) maxBound = ub;
            }
        }

        if (minBound > maxBound) return Collections.emptyList();

        List<OfflinePlayer> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            double bal = candidateBalances.get(i);
            if (bal >= minBound && bal <= maxBound) result.add(candidates.get(i));
        }
        return result;
    }

    private static double percentileValue(List<Double> sorted, double p) {
        if (sorted == null || sorted.isEmpty()) return 0.0;
        if (p <= 0) return sorted.get(0);
        if (p >= 100) return sorted.get(sorted.size() - 1);
        int n = sorted.size();
        int rank = (int) Math.ceil((p / 100.0) * n);
        rank = Math.max(1, Math.min(rank, n));
        return sorted.get(rank - 1);
    }

    private static final class PlayerEntry {
        @SuppressWarnings("unused")
        final OfflinePlayer player;
        final double balance;
        PlayerEntry(OfflinePlayer p, double b) { this.player = p; this.balance = b; }
    }
}


