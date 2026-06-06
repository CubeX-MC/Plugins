package org.cubexmc.ecobalancer.tax

import org.bukkit.command.CommandSender
import java.util.concurrent.atomic.AtomicBoolean

class TaxRunService {
    private val running = AtomicBoolean(false)

    @Volatile
    var state: TaxRunState = idleState()
        private set

    fun tryStart(
        operationId: Int,
        policyName: String,
        trigger: TaxOperationType,
        totalPlayers: Int,
        sender: CommandSender?,
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            return false
        }
        val senderName = sender?.name ?: "Console/Scheduler"
        state = TaxRunState(
            true,
            operationId,
            policyName,
            System.currentTimeMillis(),
            totalPlayers,
            0,
            0,
            0.0,
            trigger,
            senderName,
        )
        return true
    }

    fun <T> startBatchedRun(
        operationId: Int,
        policyName: String,
        trigger: TaxOperationType,
        items: List<T>?,
        batchSize: Int,
        delayTicks: Long,
        sender: CommandSender?,
        scheduler: BatchScheduler,
        processor: BatchProcessor<T>,
        progressListener: BatchProgressListener?,
        completion: BatchCompletion?,
        failureHandler: BatchFailureHandler?,
    ): Boolean {
        val totalItems = items?.size ?: 0
        if (!tryStart(operationId, policyName, trigger, totalItems, sender)) {
            return false
        }
        BatchedRun(
            operationId,
            items,
            batchSize.coerceAtLeast(1),
            delayTicks.coerceAtLeast(0L),
            scheduler,
            processor,
            progressListener,
            completion,
            failureHandler,
        ).run()
        return true
    }

    fun updateProgress(processedPlayers: Int, affectedPlayers: Int, totalDeducted: Double) {
        val current = state
        if (!current.isRunning) {
            return
        }
        state = TaxRunState(
            true,
            current.operationId,
            current.policyName,
            current.startedAt,
            current.totalPlayers,
            processedPlayers,
            affectedPlayers,
            totalDeducted,
            current.trigger,
            current.senderName,
        )
    }

    fun finish() {
        state = idleState()
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()

    private inner class BatchedRun<T>(
        private val operationId: Int,
        private val items: List<T>?,
        private val batchSize: Int,
        private val delayTicks: Long,
        private val scheduler: BatchScheduler,
        private val processor: BatchProcessor<T>,
        private val progressListener: BatchProgressListener?,
        private val completion: BatchCompletion?,
        private val failureHandler: BatchFailureHandler?,
    ) : Runnable {
        private var index = 0

        override fun run() {
            try {
                val runItems = items
                val total = runItems?.size ?: 0
                val start = index
                val end = (start + batchSize).coerceAtMost(total)
                if (runItems != null) {
                    for (i in start until end) {
                        processor.process(runItems[i], i)
                    }
                }
                index = end
                updateProgress(index, 0, 0.0)
                progressListener?.onBatchProcessed(start, end, total)
                if (index < total) {
                    scheduler.schedule(this, delayTicks)
                    return
                }
                completion?.onComplete()
                finish()
            } catch (throwable: Throwable) {
                finish()
                failureHandler?.onFailure(operationId, throwable)
            }
        }
    }

    fun interface BatchScheduler {
        fun schedule(task: Runnable, delayTicks: Long)
    }

    fun interface BatchProcessor<T> {
        @Throws(Exception::class)
        fun process(item: T, index: Int)
    }

    fun interface BatchProgressListener {
        fun onBatchProcessed(startInclusive: Int, endExclusive: Int, totalItems: Int)
    }

    fun interface BatchCompletion {
        @Throws(Exception::class)
        fun onComplete()
    }

    fun interface BatchFailureHandler {
        fun onFailure(operationId: Int, throwable: Throwable)
    }

    companion object {
        private fun idleState(): TaxRunState = TaxRunState(false, -1, "", 0L, 0, 0, 0, 0.0, null, "")
    }
}
