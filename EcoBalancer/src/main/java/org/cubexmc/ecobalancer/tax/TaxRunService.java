package org.cubexmc.ecobalancer.tax;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.command.CommandSender;

public class TaxRunService {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile TaxRunState state = idleState();

    public boolean tryStart(int operationId, String policyName, TaxOperationType trigger, int totalPlayers,
            CommandSender sender) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        String senderName = sender == null ? "Console/Scheduler" : sender.getName();
        state = new TaxRunState(true, operationId, policyName, System.currentTimeMillis(), totalPlayers, 0, 0, 0.0,
                trigger, senderName);
        return true;
    }

    public <T> boolean startBatchedRun(int operationId, String policyName, TaxOperationType trigger,
            List<T> items, int batchSize, long delayTicks, CommandSender sender, BatchScheduler scheduler,
            BatchProcessor<T> processor, BatchProgressListener progressListener, BatchCompletion completion,
            BatchFailureHandler failureHandler) {
        int totalItems = items == null ? 0 : items.size();
        if (!tryStart(operationId, policyName, trigger, totalItems, sender)) {
            return false;
        }
        BatchedRun<T> run = new BatchedRun<>(operationId, items, Math.max(1, batchSize), Math.max(0L, delayTicks),
                scheduler, processor, progressListener, completion, failureHandler);
        run.run();
        return true;
    }

    public void updateProgress(int processedPlayers, int affectedPlayers, double totalDeducted) {
        TaxRunState current = state;
        if (!current.isRunning()) {
            return;
        }
        state = new TaxRunState(true, current.getOperationId(), current.getPolicyName(), current.getStartedAt(),
                current.getTotalPlayers(), processedPlayers, affectedPlayers, totalDeducted, current.getTrigger(),
                current.getSenderName());
    }

    public void finish() {
        state = idleState();
        running.set(false);
    }

    public TaxRunState getState() {
        return state;
    }

    public boolean isRunning() {
        return running.get();
    }

    private static TaxRunState idleState() {
        return new TaxRunState(false, -1, "", 0L, 0, 0, 0, 0.0, null, "");
    }

    private final class BatchedRun<T> implements Runnable {
        private final int operationId;
        private final List<T> items;
        private final int batchSize;
        private final long delayTicks;
        private final BatchScheduler scheduler;
        private final BatchProcessor<T> processor;
        private final BatchProgressListener progressListener;
        private final BatchCompletion completion;
        private final BatchFailureHandler failureHandler;
        private int index;

        private BatchedRun(int operationId, List<T> items, int batchSize, long delayTicks, BatchScheduler scheduler,
                BatchProcessor<T> processor, BatchProgressListener progressListener, BatchCompletion completion,
                BatchFailureHandler failureHandler) {
            this.operationId = operationId;
            this.items = items;
            this.batchSize = batchSize;
            this.delayTicks = delayTicks;
            this.scheduler = scheduler;
            this.processor = processor;
            this.progressListener = progressListener;
            this.completion = completion;
            this.failureHandler = failureHandler;
        }

        @Override
        public void run() {
            try {
                int total = items == null ? 0 : items.size();
                int start = index;
                int end = Math.min(start + batchSize, total);
                for (int i = start; i < end; i++) {
                    processor.process(items.get(i), i);
                }
                index = end;
                updateProgress(index, 0, 0.0D);
                if (progressListener != null) {
                    progressListener.onBatchProcessed(start, end, total);
                }
                if (index < total) {
                    scheduler.schedule(this, delayTicks);
                    return;
                }
                if (completion != null) {
                    completion.onComplete();
                }
                finish();
            } catch (Throwable t) {
                finish();
                if (failureHandler != null) {
                    failureHandler.onFailure(operationId, t);
                }
            }
        }
    }

    @FunctionalInterface
    public interface BatchScheduler {
        void schedule(Runnable task, long delayTicks);
    }

    @FunctionalInterface
    public interface BatchProcessor<T> {
        void process(T item, int index) throws Exception;
    }

    @FunctionalInterface
    public interface BatchProgressListener {
        void onBatchProcessed(int startInclusive, int endExclusive, int totalItems);
    }

    @FunctionalInterface
    public interface BatchCompletion {
        void onComplete() throws Exception;
    }

    @FunctionalInterface
    public interface BatchFailureHandler {
        void onFailure(int operationId, Throwable throwable);
    }
}
