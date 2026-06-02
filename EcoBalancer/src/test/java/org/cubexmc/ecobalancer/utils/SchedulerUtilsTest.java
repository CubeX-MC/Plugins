package org.cubexmc.ecobalancer.utils;

import static org.junit.jupiter.api.Assertions.assertNull;
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

class SchedulerUtilsTest {

    @Test
    void globalRunExecutesInlineWhenPrimaryThreadNoDelay() {
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            mockedBukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            int[] calls = {0};

            Object handle = SchedulerUtils.globalRun(plugin, () -> calls[0]++, 0L, -1L);

            assertNull(handle);
            org.junit.jupiter.api.Assertions.assertEquals(1, calls[0]);
        }
    }

    @Test
    void cancelAllCancelsBukkitTasksAndAllowsReschedule() {
        try (MockedStatic<Bukkit> mockedBukkit = mockStatic(Bukkit.class)) {
            Plugin plugin = mock(Plugin.class);
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            BukkitTask first = mock(BukkitTask.class);
            BukkitTask second = mock(BukkitTask.class);
            mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(10L), eq(20L)))
                    .thenReturn(first)
                    .thenReturn(second);

            Object firstHandle = SchedulerUtils.runTaskTimer(plugin, () -> {
            }, 10L, 20L);
            SchedulerUtils.cancelAllTasks(plugin);
            Object secondHandle = SchedulerUtils.runTaskTimer(plugin, () -> {
            }, 10L, 20L);

            assertSame(first, firstHandle);
            assertSame(second, secondHandle);
            verify(scheduler).cancelTasks(plugin);
            verify(scheduler, org.mockito.Mockito.times(2))
                    .runTaskTimer(eq(plugin), any(Runnable.class), eq(10L), eq(20L));
        }
    }
}
