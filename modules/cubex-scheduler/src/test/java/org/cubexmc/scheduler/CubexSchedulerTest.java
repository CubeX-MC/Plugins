package org.cubexmc.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class CubexSchedulerTest {

    @Test
    void foliaDetectionIsStableAcrossCalls() {
        boolean first = CubexScheduler.detectFolia();

        for (int i = 0; i < 1000; i++) {
            assertEquals(first, CubexScheduler.detectFolia());
        }
    }

    @Test
    void foliaDetectionReportsFalseWithoutFoliaOnTheClasspath() {
        assertFalse(CubexScheduler.detectFolia(),
                "the test classpath has no Folia, so a correct probe must say so");
    }

    @Test
    void legacyAdapterAgreesWithSchedulerFoliaDetection() {
        Plugin plugin = mock(Plugin.class);

        LegacySchedulerAdapter adapter = LegacySchedulerAdapter.builder(CubexScheduler.create(plugin)).build();

        assertEquals(CubexScheduler.detectFolia(), adapter.isFolia());
    }

    @Test
    void repeatingConsumerCanCancelItself() {
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
            BukkitTask bukkitTask = mock(BukkitTask.class);
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(bukkitScheduler);
            when(bukkitScheduler.runTaskTimer(eq(plugin), taskCaptor.capture(), eq(1L), eq(5L)))
                    .thenReturn(bukkitTask);

            CubexTask task = CubexScheduler.create(plugin).runGlobalTimer(CubexTask::cancel, 1L, 5L);
            taskCaptor.getValue().run();

            assertTrue(task.isCancelled());
            verify(bukkitTask).cancel();
        }
    }

    @Test
    void tickCounterFallbackIncrementsWhenCurrentTickReflectionIsUnavailable() {
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            BukkitScheduler bukkitScheduler = mock(BukkitScheduler.class);
            BukkitTask bukkitTask = mock(BukkitTask.class);
            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(bukkitScheduler);
            when(bukkitScheduler.runTaskTimer(eq(plugin), taskCaptor.capture(), eq(0L), eq(1L)))
                    .thenReturn(bukkitTask);
            LegacySchedulerAdapter adapter = LegacySchedulerAdapter.builder(plugin)
                    .tickAccessEnabled(true)
                    .build();

            adapter.ensureTickCounter();
            long initial = adapter.getCurrentTick();
            taskCaptor.getValue().run();
            long afterOneTick = adapter.getCurrentTick();
            taskCaptor.getValue().run();
            long afterTwoTicks = adapter.getCurrentTick();

            org.junit.jupiter.api.Assertions.assertEquals(0L, initial);
            org.junit.jupiter.api.Assertions.assertEquals(1L, afterOneTick);
            org.junit.jupiter.api.Assertions.assertEquals(2L, afterTwoTicks);
        }
    }

    @Test
    void tickAccessDisabledThrows() {
        Plugin plugin = mock(Plugin.class);
        LegacySchedulerAdapter adapter = LegacySchedulerAdapter.builder(plugin)
                .tickAccessEnabled(false)
                .build();

        assertThrows(UnsupportedOperationException.class, adapter::getCurrentTick);
        assertThrows(UnsupportedOperationException.class, adapter::ensureTickCounter);
    }
}
