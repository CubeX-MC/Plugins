package org.cubexmc.metro.estimation

import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Objects
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.metro.Metro

/**
 * Bayesian travel time estimator per line section using a Normal mean update
 * with fixed prior strength.
 * Stores a prior mean (mu0) and prior strength (kappa0) and maintains weighted
 * observations online.
 * Outliers are rejected based on a configurable sigma threshold using online
 * variance (Welford's method).
 * Optionally applies daily decay to observed weights to adapt over time.
 */
class TravelTimeEstimator private constructor(
    private val plugin: Metro?,
    private val settings: Settings,
    dataDir: File,
    private val clock: Clock,
    private val warningSink: Consumer<String>,
) {
    private val storeFile: File
    private val statsByKey: MutableMap<String, SectionStats> = HashMap()

    init {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        storeFile = File(dataDir, DATA_FILE)
    }

    constructor(plugin: Metro) : this(
        plugin,
        PluginSettings(plugin),
        File(plugin.dataFolder, DATA_DIR),
        Clock.systemUTC(),
        Consumer { message -> plugin.logger.warning(message) },
    )

    constructor(settings: Settings?, dataDir: File?, clock: Clock?) : this(
        null,
        settings ?: throw NullPointerException("settings"),
        dataDir ?: throw NullPointerException("dataDir"),
        clock ?: throw NullPointerException("clock"),
        Consumer { },
    )

    fun getPlugin(): Metro? = plugin

    fun load() {
        statsByKey.clear()
        if (!storeFile.exists()) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(storeFile)
        val root = yaml.getConfigurationSection("sections") ?: return
        for (key in root.getKeys(false)) {
            val section = root.getConfigurationSection(key) ?: continue
            statsByKey[key] = SectionStats.fromConfig(
                section,
                settings.defaultSectionSeconds(),
                currentEpochDay(),
            )
        }
    }

    fun save() {
        val yaml = YamlConfiguration()
        val root = yaml.createSection("sections")
        for ((key, stats) in statsByKey) {
            val section = root.createSection(key)
            stats.applyDecay(currentEpochDay(), settings.decayPerDay())
            stats.writeTo(section, settings.priorStrength())
        }
        try {
            yaml.save(storeFile)
        } catch (ex: IOException) {
            warningSink.accept("Failed to save travel time estimates: ${ex.message}")
        }
    }

    fun estimateSeconds(lineId: String?, fromStopId: String?, toStopId: String?): Double {
        if (!settings.enabled()) {
            return settings.defaultSectionSeconds()
        }
        val stats = getOrCreate(lineId, fromStopId, toStopId)
        stats.applyDecay(currentEpochDay(), settings.decayPerDay())
        return stats.posteriorEstimate(settings.priorStrength())
    }

    fun record(
        lineId: String?,
        fromStopId: String?,
        toStopId: String?,
        durationSeconds: Double,
        sampleWeight: Double,
    ) {
        if (!settings.enabled()) {
            return
        }
        if (durationSeconds <= 0.01 || durationSeconds > 600.0) {
            return
        }
        if (sampleWeight <= 0.0) {
            return
        }

        val stats = getOrCreate(lineId, fromStopId, toStopId)
        stats.applyDecay(currentEpochDay(), settings.decayPerDay())
        if (stats.shouldReject(durationSeconds, settings.outlierSigma())) {
            return
        }
        stats.record(durationSeconds, sampleWeight)
    }

    private fun getOrCreate(lineId: String?, fromStopId: String?, toStopId: String?): SectionStats {
        val key = key(lineId, fromStopId, toStopId)
        return statsByKey.getOrPut(key) {
            SectionStats(settings.defaultSectionSeconds(), currentEpochDay())
        }
    }

    private fun currentEpochDay(): Long =
        LocalDate.now(clock.withZone(ZoneOffset.UTC)).toEpochDay()

    private fun key(lineId: String?, fromStopId: String?, toStopId: String?): String =
        "${Objects.toString(lineId, "?")}:${Objects.toString(fromStopId, "?")}->${Objects.toString(toStopId, "?")}"

    interface Settings {
        fun enabled(): Boolean

        fun defaultSectionSeconds(): Double

        fun priorStrength(): Double

        fun outlierSigma(): Double

        fun decayPerDay(): Double
    }

    private class PluginSettings(
        private val plugin: Metro,
    ) : Settings {
        override fun enabled(): Boolean = plugin.isTravelTimeEnabled

        override fun defaultSectionSeconds(): Double = plugin.defaultSectionSeconds

        override fun priorStrength(): Double = plugin.priorStrength

        override fun outlierSigma(): Double = plugin.outlierSigma

        override fun decayPerDay(): Double = plugin.decayPerDay
    }

    private class SectionStats(
        val mu0: Double,
        currentEpochDay: Long,
    ) {
        var observedMean: Double = mu0
        var observedWeight: Double = 0.0
        var observedM2: Double = 0.0
        var sampleCount: Long = 0
        var lastDecayEpochDay: Long = currentEpochDay

        fun writeTo(section: ConfigurationSection, priorStrength: Double) {
            section.set("mu0", mu0)
            section.set("estimate", posteriorEstimate(priorStrength))
            section.set("sampleCount", sampleCount)
            section.set("observed_mean", observedMean)
            section.set("observed_weight", observedWeight)
            section.set("observed_m2", observedM2)
            section.set("last_decay_epoch_day", lastDecayEpochDay)
        }

        fun applyDecay(currentEpochDay: Long, decayPerDay: Double) {
            if (currentEpochDay <= lastDecayEpochDay) {
                return
            }
            val daysElapsed = currentEpochDay - lastDecayEpochDay
            lastDecayEpochDay = currentEpochDay

            if (observedWeight <= 0.0) {
                observedWeight = 0.0
                observedM2 = 0.0
                observedMean = mu0
                return
            }

            val factor = max(0.0, min(1.0, decayPerDay)).pow(daysElapsed.toDouble())
            observedWeight *= factor
            observedM2 *= factor

            if (observedWeight <= MIN_OBSERVED_WEIGHT) {
                observedWeight = 0.0
                observedM2 = 0.0
                observedMean = mu0
            }
        }

        fun shouldReject(durationSeconds: Double, sigmaThreshold: Double): Boolean {
            if (sigmaThreshold <= 0.0) {
                return false
            }
            if (sampleCount < 2 || observedWeight <= MIN_OBSERVED_WEIGHT) {
                return false
            }
            val variance = observedM2 / observedWeight
            if (variance <= MIN_OBSERVED_WEIGHT) {
                return false
            }
            val standardDeviation = sqrt(variance)
            return abs(durationSeconds - observedMean) > sigmaThreshold * standardDeviation
        }

        fun record(durationSeconds: Double, sampleWeight: Double) {
            if (observedWeight <= 0.0) {
                observedMean = durationSeconds
                observedWeight = sampleWeight
                observedM2 = 0.0
                sampleCount++
                return
            }

            val newTotalWeight = observedWeight + sampleWeight
            val delta = durationSeconds - observedMean
            observedMean += (sampleWeight / newTotalWeight) * delta
            val delta2 = durationSeconds - observedMean
            observedM2 += sampleWeight * delta * delta2
            observedWeight = newTotalWeight
            sampleCount++
        }

        fun posteriorEstimate(priorStrength: Double): Double {
            val effectivePrior = max(0.0, priorStrength)
            if (observedWeight <= 0.0) {
                return mu0
            }
            return ((effectivePrior * mu0) + (observedWeight * observedMean)) /
                (effectivePrior + observedWeight)
        }

        companion object {
            fun fromConfig(
                section: ConfigurationSection,
                defaultSectionSeconds: Double,
                currentEpochDay: Long,
            ): SectionStats {
                val mu0 = section.getDouble("mu0", defaultSectionSeconds)
                val stats = SectionStats(mu0, currentEpochDay)
                stats.sampleCount = max(0L, section.getLong("sampleCount", 0L))

                if (section.contains("observed_weight") ||
                    section.contains("observed_mean") ||
                    section.contains("observed_m2")
                ) {
                    stats.observedWeight = max(0.0, section.getDouble("observed_weight", 0.0))
                    stats.observedMean = section.getDouble("observed_mean", mu0)
                    stats.observedM2 = max(0.0, section.getDouble("observed_m2", 0.0))
                    stats.lastDecayEpochDay = section.getLong("last_decay_epoch_day", currentEpochDay)
                    return stats
                }

                if (section.contains("estimate")) {
                    stats.observedWeight = max(0.0, stats.sampleCount.toDouble())
                    stats.observedMean = section.getDouble("estimate", mu0)
                    stats.observedM2 = 0.0
                    stats.lastDecayEpochDay = currentEpochDay
                    return stats
                }

                if (section.contains("mean")) {
                    stats.observedMean = section.getDouble("mean", mu0)
                    stats.observedWeight = max(0.0, section.getDouble("weight", 0.0))
                    stats.observedM2 = 0.0
                    stats.lastDecayEpochDay = currentEpochDay
                }
                return stats
            }
        }
    }

    companion object {
        private const val DATA_DIR = "data"
        private const val DATA_FILE = "travel_times.yml"
        private const val MIN_OBSERVED_WEIGHT = 1.0e-9
    }
}
