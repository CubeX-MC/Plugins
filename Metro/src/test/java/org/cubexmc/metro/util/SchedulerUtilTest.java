package org.cubexmc.metro.util;

import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.mockito.MockedStatic;

class SchedulerUtilTest {

    @Test
    void globalRunSchedulesImmediateOneShotInsteadOfInlining() {
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            BukkitTask bukkitTask = mock(BukkitTask.class);
            mockedBukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenReturn(bukkitTask);
            int[] calls = {0};

            Object handle = SchedulerUtil.globalRun(plugin, () -> calls[0]++, 0L, -1L);

            assertSame(bukkitTask, handle);
            org.junit.jupiter.api.Assertions.assertEquals(0, calls[0]);
            verify(scheduler).runTask(eq(plugin), any(Runnable.class));
        }
    }
}
