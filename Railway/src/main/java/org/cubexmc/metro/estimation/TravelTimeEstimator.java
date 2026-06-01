package org.cubexmc.metro.estimation;

import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.cubexmc.metro.Metro;

/**
 * Bayesian travel time estimator per line section using a Normal mean update
 * with fixed prior strength.
 * Stores a prior mean (mu0) and prior strength (kappa0) and maintains weighted
 * observations online.
 * Outliers are rejected based on a configurable sigma threshold using online
 * variance (Welford's method).
 * Optionally applies daily decay to observed weights to adapt over time.
 */
public class TravelTimeEstimator {

    private static final String DATA_DIR = "data";
    private static final String DATA_FILE = "travel_times.yml";

    private final Metro plugin;
    private final Settings settings;
    private final File storeFile;
    private final Clock clock;
    private final Consumer<String> warningSink;
    private final Map<String, SectionStats> statsByKey = new HashMap<>();

    public TravelTimeEstimator(Metro plugin) {
        this(plugin,
                new PluginSettings(plugin),
                new File(plugin.getDataFolder(), DATA_DIR),
                Clock.systemUTC(),
                plugin.getLogger()::warning);
    }

    public TravelTimeEstimator(Settings settings, File dataDir, Clock clock) {
        this(null, settings, dataDir, clock, message -> {
        });
    }

    private TravelTimeEstimator(Metro plugin, Settings settings, File dataDir, Clock clock,
            Consumer<String> warningSink) {
        this.plugin = plugin;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");

        File dir = Objects.requireNonNull(dataDir, "dataDir");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.storeFile = new File(dir, DATA_FILE);
    }

    public Metro getPlugin() {
        return plugin;
    }

    public void load() {
        statsByKey.clear();
        if (!storeFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection root = yaml.getConfigurationSection("sections");
        if (root == null)
            return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection cs = root.getConfigurationSection(key);
            if (cs == null)
                continue;

            SectionStats s = SectionStats.fromConfig(cs, settings.defaultSectionSeconds(), currentEpochDay());

            statsByKey.put(key, s);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        ConfigurationSection root = yaml.createSection("sections");
        for (Map.Entry<String, SectionStats> e : statsByKey.entrySet()) {
            ConfigurationSection cs = root.createSection(e.getKey());
            SectionStats s = e.getValue();
            s.applyDecay(currentEpochDay(), settings.decayPerDay());
            s.writeTo(cs, settings.priorStrength());
        }
        try {
            yaml.save(storeFile);
        } catch (IOException ex) {
            warningSink.accept("Failed to save travel time estimates: " + ex.getMessage());
        }
    }

    public double estimateSeconds(String lineId, String fromStopId, String toStopId) {
        if (!settings.enabled()) {
            return settings.defaultSectionSeconds();
        }
        SectionStats s = getOrCreate(lineId, fromStopId, toStopId);
        s.applyDecay(currentEpochDay(), settings.decayPerDay());
        return s.posteriorEstimate(settings.priorStrength());
    }

    public void record(String lineId, String fromStopId, String toStopId, double durationSeconds, double sampleWeight) {
        if (!settings.enabled())
            return;
        if (durationSeconds <= 0.01 || durationSeconds > 600.0)
            return;
        if (sampleWeight <= 0.0)
            return;

        SectionStats s = getOrCreate(lineId, fromStopId, toStopId);
        s.applyDecay(currentEpochDay(), settings.decayPerDay());

        if (s.shouldReject(durationSeconds, settings.outlierSigma())) {
            return;
        }

        s.record(durationSeconds, sampleWeight);
    }

    private SectionStats getOrCreate(String lineId, String fromStopId, String toStopId) {
        String key = key(lineId, fromStopId, toStopId);
        SectionStats s = statsByKey.get(key);
        if (s == null) {
            s = new SectionStats(settings.defaultSectionSeconds(), currentEpochDay());
            statsByKey.put(key, s);
        }
        return s;
    }

    private long currentEpochDay() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay();
    }

    private String key(String lineId, String fromStopId, String toStopId) {
        return Objects.toString(lineId, "?") + ":" + Objects.toString(fromStopId, "?") + "->"
                + Objects.toString(toStopId, "?");
    }

    public interface Settings {
        boolean enabled();

        double defaultSectionSeconds();

        double priorStrength();

        double outlierSigma();

        double decayPerDay();
    }

    private static final class PluginSettings implements Settings {
        private final Metro plugin;

        private PluginSettings(Metro plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean enabled() {
            return plugin.isTravelTimeEnabled();
        }

        @Override
        public double defaultSectionSeconds() {
            return plugin.getDefaultSectionSeconds();
        }

        @Override
        public double priorStrength() {
            return plugin.getPriorStrength();
        }

        @Override
        public double outlierSigma() {
            return plugin.getOutlierSigma();
        }

        @Override
        public double decayPerDay() {
            return plugin.getDecayPerDay();
        }
    }

    private static final class SectionStats {
        final double mu0;
        double observedMean;
        double observedWeight;
        double observedM2;
        long sampleCount;
        long lastDecayEpochDay;

        SectionStats(double mu0, long currentEpochDay) {
            this.mu0 = mu0;
            this.observedMean = mu0;
            this.observedWeight = 0.0;
            this.observedM2 = 0.0;
            this.sampleCount = 0;
            this.lastDecayEpochDay = currentEpochDay;
        }

        static SectionStats fromConfig(ConfigurationSection cs, double defaultSectionSeconds, long currentEpochDay) {
            double mu0 = cs.getDouble("mu0", defaultSectionSeconds);
            SectionStats s = new SectionStats(mu0, currentEpochDay);
            s.sampleCount = Math.max(0L, cs.getLong("sampleCount", 0L));

            if (cs.contains("observed_weight") || cs.contains("observed_mean") || cs.contains("observed_m2")) {
                s.observedWeight = Math.max(0.0, cs.getDouble("observed_weight", 0.0));
                s.observedMean = cs.getDouble("observed_mean", mu0);
                s.observedM2 = Math.max(0.0, cs.getDouble("observed_m2", 0.0));
                s.lastDecayEpochDay = cs.getLong("last_decay_epoch_day", currentEpochDay);
                return s;
            }

            if (cs.contains("estimate")) {
                s.observedWeight = Math.max(0.0, s.sampleCount);
                s.observedMean = cs.getDouble("estimate", mu0);
                s.observedM2 = 0.0;
                s.lastDecayEpochDay = currentEpochDay;
                return s;
            }

            if (cs.contains("mean")) {
                s.observedMean = cs.getDouble("mean", mu0);
                s.observedWeight = Math.max(0.0, cs.getDouble("weight", 0.0));
                s.observedM2 = 0.0;
                s.lastDecayEpochDay = currentEpochDay;
            }
            return s;
        }

        void writeTo(ConfigurationSection cs, double priorStrength) {
            cs.set("mu0", mu0);
            cs.set("estimate", posteriorEstimate(priorStrength));
            cs.set("sampleCount", sampleCount);
            cs.set("observed_mean", observedMean);
            cs.set("observed_weight", observedWeight);
            cs.set("observed_m2", observedM2);
            cs.set("last_decay_epoch_day", lastDecayEpochDay);
        }

        void applyDecay(long currentEpochDay, double decayPerDay) {
            if (currentEpochDay <= lastDecayEpochDay) {
                return;
            }
            long daysElapsed = currentEpochDay - lastDecayEpochDay;
            lastDecayEpochDay = currentEpochDay;

            if (observedWeight <= 0.0) {
                observedWeight = 0.0;
                observedM2 = 0.0;
                observedMean = mu0;
                return;
            }

            double factor = Math.pow(Math.max(0.0, Math.min(1.0, decayPerDay)), daysElapsed);
            observedWeight *= factor;
            observedM2 *= factor;

            if (observedWeight <= 1.0e-9) {
                observedWeight = 0.0;
                observedM2 = 0.0;
                observedMean = mu0;
            }
        }

        boolean shouldReject(double durationSeconds, double sigmaThreshold) {
            if (sigmaThreshold <= 0.0) {
                return false;
            }
            if (sampleCount < 2 || observedWeight <= 1.0e-9) {
                return false;
            }
            double variance = observedM2 / observedWeight;
            if (variance <= 1.0e-9) {
                return false;
            }
            double stddev = Math.sqrt(variance);
            return Math.abs(durationSeconds - observedMean) > sigmaThreshold * stddev;
        }

        void record(double durationSeconds, double sampleWeight) {
            if (observedWeight <= 0.0) {
                observedMean = durationSeconds;
                observedWeight = sampleWeight;
                observedM2 = 0.0;
                sampleCount++;
                return;
            }

            double newTotalWeight = observedWeight + sampleWeight;
            double delta = durationSeconds - observedMean;
            observedMean += (sampleWeight / newTotalWeight) * delta;
            double delta2 = durationSeconds - observedMean;
            observedM2 += sampleWeight * delta * delta2;
            observedWeight = newTotalWeight;
            sampleCount++;
        }

        double posteriorEstimate(double priorStrength) {
            double effectivePrior = Math.max(0.0, priorStrength);
            if (observedWeight <= 0.0) {
                return mu0;
            }
            return ((effectivePrior * mu0) + (observedWeight * observedMean))
                    / (effectivePrior + observedWeight);
        }
    }
}
