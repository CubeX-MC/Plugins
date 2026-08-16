package org.cubexmc.config

import org.cubexmc.core.Reloadable
import java.util.function.BooleanSupplier

/** Ordered named reload stages with gating and per-stage failure reporting. */
class ReloadChain private constructor() : Reloadable {
    private val entries = ArrayList<Entry>()
    private var failurePolicyValue = ReloadFailurePolicy.CONTINUE

    fun failurePolicy(policy: ReloadFailurePolicy?): ReloadChain = apply {
        failurePolicyValue = policy ?: ReloadFailurePolicy.CONTINUE
    }

    fun add(name: String?, reloadable: Reloadable?): ReloadChain = apply {
        if (reloadable != null) entries.add(Entry(name.orEmpty(), reloadable, null))
    }

    fun addIf(name: String?, gate: BooleanSupplier?, reloadable: Reloadable?): ReloadChain = apply {
        if (reloadable != null) entries.add(Entry(name.orEmpty(), reloadable, gate))
    }

    fun names(): List<String> = entries.map { it.name }

    fun run(): ReloadReport {
        val succeeded = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failures = ArrayList<ReloadReport.Failure>()
        var aborted = false
        for (entry in entries) {
            if (aborted || entry.gate?.asBoolean == false) {
                skipped.add(entry.name)
                continue
            }
            try {
                entry.reloadable.reload()
                succeeded.add(entry.name)
            } catch (exception: Exception) {
                failures.add(ReloadReport.Failure(entry.name, exception))
                if (failurePolicyValue == ReloadFailurePolicy.ABORT) aborted = true
            }
        }
        return ReloadReport(succeeded, skipped, failures)
    }

    @Throws(Exception::class)
    override fun reload() {
        val report = run()
        if (!report.ok()) {
            val first = report.failures().first()
            throw first.cause() ?: IllegalStateException(first.summary())
        }
    }

    private class Entry(val name: String, val reloadable: Reloadable, val gate: BooleanSupplier?)

    companion object {
        @JvmStatic
        fun create(): ReloadChain = ReloadChain()
    }
}
