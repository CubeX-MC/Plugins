package org.cubexmc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.cubexmc.core.Reloadable;
import org.junit.jupiter.api.Test;

class ReloadChainTest {

    @Test
    void reloadsEntriesInRegistrationOrder() throws Exception {
        // Arrange
        List<String> calls = new ArrayList<>();
        ReloadChain chain = ReloadChain.create()
                .add("config", (Reloadable) () -> calls.add("config"))
                .add("language", (Reloadable) () -> calls.add("language"))
                .add("storage", (Reloadable) () -> calls.add("storage"));

        // Act
        chain.reload();

        // Assert
        assertEquals(List.of("config", "language", "storage"), calls);
        assertEquals(List.of("config", "language", "storage"), chain.names());
    }

    @Test
    void continuePolicyRunsLaterStagesAndNamesTheFailure() {
        // Arrange
        List<String> calls = new ArrayList<>();
        ReloadChain chain = ReloadChain.create()
                .failurePolicy(ReloadFailurePolicy.CONTINUE)
                .add("config", (Reloadable) () -> calls.add("config"))
                .add("language", (Reloadable) () -> {
                    throw new IllegalStateException("bad yaml");
                })
                .add("storage", (Reloadable) () -> calls.add("storage"));

        // Act
        ReloadReport report = chain.run();

        // Assert
        assertEquals(List.of("config", "storage"), calls);
        assertFalse(report.ok());
        assertEquals(List.of("config", "storage"), report.succeeded());
        assertEquals(1, report.failures().size());
        assertEquals("language", report.failures().get(0).stage());
        assertEquals(List.of("language: bad yaml"), report.failureSummaries());
    }

    @Test
    void abortPolicySkipsEverythingAfterTheFailure() {
        // Arrange
        List<String> calls = new ArrayList<>();
        ReloadChain chain = ReloadChain.create()
                .failurePolicy(ReloadFailurePolicy.ABORT)
                .add("migrate", (Reloadable) () -> {
                    throw new IllegalStateException("migration failed");
                })
                .add("config", (Reloadable) () -> calls.add("config"))
                .add("storage", (Reloadable) () -> calls.add("storage"));

        // Act
        ReloadReport report = chain.run();

        // Assert
        assertEquals(List.of(), calls);
        assertFalse(report.ok());
        assertEquals(List.of("config", "storage"), report.skipped());
    }

    @Test
    void gatedStageIsSkippedWithoutCountingAsFailure() {
        // Arrange: the classic "flush failed, so do not reload state from a stale file" shape.
        List<String> calls = new ArrayList<>();
        boolean[] flushed = {false};
        ReloadChain chain = ReloadChain.create()
                .add("flush", (Reloadable) () -> flushed[0] = false)
                .addIf("reload-data", () -> flushed[0], (Reloadable) () -> calls.add("reload-data"))
                .add("language", (Reloadable) () -> calls.add("language"));

        // Act
        ReloadReport report = chain.run();

        // Assert
        assertEquals(List.of("language"), calls);
        assertTrue(report.ok(), "a gated-out stage is not a failure");
        assertEquals(List.of("reload-data"), report.skipped());
    }

    @Test
    void gateIsEvaluatedAtRunTimeNotBuildTime() {
        // Arrange
        List<String> calls = new ArrayList<>();
        boolean[] allowed = {false};
        ReloadChain chain = ReloadChain.create()
                .add("unlock", (Reloadable) () -> allowed[0] = true)
                .addIf("guarded", () -> allowed[0], (Reloadable) () -> calls.add("guarded"));

        // Act
        ReloadReport report = chain.run();

        // Assert
        assertEquals(List.of("guarded"), calls);
        assertTrue(report.ok());
    }

    @Test
    void reloadableFormRethrowsSoChainsCanNest() {
        // Arrange
        ReloadChain inner = ReloadChain.create()
                .add("boom", (Reloadable) () -> {
                    throw new IllegalStateException("inner failed");
                });
        ReloadChain outer = ReloadChain.create().add("inner", inner);

        // Act
        ReloadReport report = outer.run();

        // Assert
        assertFalse(report.ok());
        assertEquals("inner", report.failures().get(0).stage());
        assertEquals("inner failed", report.failures().get(0).cause().getMessage());
    }
}
