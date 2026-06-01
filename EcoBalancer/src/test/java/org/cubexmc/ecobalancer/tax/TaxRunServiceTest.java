package org.cubexmc.ecobalancer.tax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TaxRunServiceTest {
    @Test
    void tracksRunLifecycleAndProgress() {
        TaxRunService service = new TaxRunService();

        assertFalse(service.isRunning());
        assertTrue(service.tryStart(42, "inactive_tax", TaxOperationType.CHECK_ALL, 10, null));

        TaxRunState started = service.getState();
        assertTrue(started.isRunning());
        assertEquals(42, started.getOperationId());
        assertEquals("inactive_tax", started.getPolicyName());
        assertEquals(10, started.getTotalPlayers());
        assertEquals("Console/Scheduler", started.getSenderName());

        service.updateProgress(4, 2, 123.45D);
        TaxRunState progressed = service.getState();
        assertEquals(4, progressed.getProcessedPlayers());
        assertEquals(2, progressed.getAffectedPlayers());
        assertEquals(123.45D, progressed.getTotalDeducted(), 0.0001D);

        service.finish();
        assertFalse(service.isRunning());
        assertFalse(service.getState().isRunning());
    }

    @Test
    void rejectsOverlappingRuns() {
        TaxRunService service = new TaxRunService();

        assertTrue(service.tryStart(1, "first", TaxOperationType.CHECK_ALL, 1, null));
        assertFalse(service.tryStart(2, "second", TaxOperationType.POLICY_EXECUTE, 1, null));
        assertEquals(1, service.getState().getOperationId());
    }

    @Test
    void allowsOnlyOneConcurrentStarter() throws Exception {
        TaxRunService service = new TaxRunService();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                final int operationId = i;
                tasks.add(() -> service.tryStart(operationId, "policy", TaxOperationType.CHECK_ALL, 1, null));
            }

            int successfulStarts = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                if (result.get()) {
                    successfulStarts++;
                }
            }

            assertEquals(1, successfulStarts);
            assertTrue(service.isRunning());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void batchedRunProcessesAllItemsAndFinishes() {
        TaxRunService service = new TaxRunService();
        List<Integer> processed = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();

        boolean started = service.startBatchedRun(7, "policy", TaxOperationType.CHECK_ALL,
                Arrays.asList(1, 2, 3, 4, 5), 2, 1L, null,
                (task, delayTicks) -> task.run(),
                (item, index) -> processed.add(item),
                (start, end, total) -> progress.add(start + ":" + end + ":" + total),
                completions::incrementAndGet,
                (operationId, throwable) -> fail("unexpected failure"));

        assertTrue(started);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), processed);
        assertEquals(Arrays.asList("0:2:5", "2:4:5", "4:5:5"), progress);
        assertEquals(1, completions.get());
        assertFalse(service.isRunning());
    }

    @Test
    void batchedRunReleasesLockAfterFailure() {
        TaxRunService service = new TaxRunService();
        AtomicReference<Throwable> capturedFailure = new AtomicReference<>();

        boolean started = service.startBatchedRun(8, "policy", TaxOperationType.CHECK_ALL,
                Arrays.asList("ok", "boom", "later"), 2, 1L, null,
                (task, delayTicks) -> task.run(),
                (item, index) -> {
                    if ("boom".equals(item)) {
                        throw new IllegalStateException("test failure");
                    }
                },
                (start, end, total) -> {
                },
                () -> fail("completion should not run after failure"),
                (operationId, throwable) -> capturedFailure.set(throwable));

        assertTrue(started);
        assertFalse(service.isRunning());
        assertTrue(capturedFailure.get() instanceof IllegalStateException);
        assertTrue(service.tryStart(9, "next", TaxOperationType.CHECK_ALL, 1, null));
    }
}
