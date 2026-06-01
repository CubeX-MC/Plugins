package org.cubexmc.metro.bedrock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.cubexmc.metro.util.SchedulerUtil;
import org.junit.jupiter.api.Test;

class BedrockArrivalSyncTest {

    @Test
    void tickShouldResendZeroVelocityWhilePassengerMounted() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Plugin plugin = mock(Plugin.class);
        Player passenger = mock(Player.class);
        Minecart minecart = stationaryCart(passenger);
        Runnable tick = captureScheduledTick(sync, plugin, passenger, minecart);

        tick.run();

        verify(minecart).setVelocity(argThat(v -> v.getX() == 0 && v.getY() == 0 && v.getZ() == 0));
    }

    @Test
    void tickShouldCancelTaskWhenCartIsDead() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Plugin plugin = mock(Plugin.class);
        Player passenger = mock(Player.class);
        Minecart minecart = stationaryCart(passenger);
        Object scheduledHandle = new Object();

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            AtomicReference<Runnable> captured = new AtomicReference<>();
            scheduler.when(() -> SchedulerUtil.entityRun(eq(plugin), eq(minecart), any(Runnable.class),
                    anyLong(), anyLong())).thenAnswer(invocation -> {
                        captured.set(invocation.getArgument(2));
                        return scheduledHandle;
                    });

            sync.start(plugin, passenger, minecart);
            when(minecart.isDead()).thenReturn(true);
            captured.get().run();

            scheduler.verify(() -> SchedulerUtil.cancelTask(scheduledHandle));
            verify(minecart, never()).setVelocity(any(Vector.class));
        }
    }

    @Test
    void tickShouldCancelTaskWhenPassengerDismounts() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Plugin plugin = mock(Plugin.class);
        Player passenger = mock(Player.class);
        Minecart minecart = stationaryCart(passenger);
        Object scheduledHandle = new Object();

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            AtomicReference<Runnable> captured = new AtomicReference<>();
            scheduler.when(() -> SchedulerUtil.entityRun(eq(plugin), eq(minecart), any(Runnable.class),
                    anyLong(), anyLong())).thenAnswer(invocation -> {
                        captured.set(invocation.getArgument(2));
                        return scheduledHandle;
                    });

            sync.start(plugin, passenger, minecart);
            when(passenger.getVehicle()).thenReturn(null);
            captured.get().run();

            scheduler.verify(() -> SchedulerUtil.cancelTask(scheduledHandle));
            verify(minecart, never()).setVelocity(any(Vector.class));
        }
    }

    @Test
    void tickShouldCancelTaskWhenVelocityBecomesNonZero() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Plugin plugin = mock(Plugin.class);
        Player passenger = mock(Player.class);
        Minecart minecart = stationaryCart(passenger);
        Object scheduledHandle = new Object();

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            AtomicReference<Runnable> captured = new AtomicReference<>();
            scheduler.when(() -> SchedulerUtil.entityRun(eq(plugin), eq(minecart), any(Runnable.class),
                    anyLong(), anyLong())).thenAnswer(invocation -> {
                        captured.set(invocation.getArgument(2));
                        return scheduledHandle;
                    });

            sync.start(plugin, passenger, minecart);
            when(minecart.getVelocity()).thenReturn(new Vector(0.4, 0, 0));
            captured.get().run();

            scheduler.verify(() -> SchedulerUtil.cancelTask(scheduledHandle));
            verify(minecart, never()).setVelocity(any(Vector.class));
        }
    }

    @Test
    void startShouldCancelExistingTaskForSameCart() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Plugin plugin = mock(Plugin.class);
        Player passenger = mock(Player.class);
        Minecart minecart = stationaryCart(passenger);
        Object firstHandle = new Object();
        Object secondHandle = new Object();

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.entityRun(eq(plugin), eq(minecart), any(Runnable.class),
                    anyLong(), anyLong())).thenReturn(firstHandle, secondHandle);

            sync.start(plugin, passenger, minecart);
            sync.start(plugin, passenger, minecart);

            scheduler.verify(() -> SchedulerUtil.cancelTask(firstHandle), times(1));
            scheduler.verify(() -> SchedulerUtil.cancelTask(secondHandle), never());
        }
    }

    @Test
    void stopShouldBeSafeWithoutActiveTask() {
        BedrockArrivalSync sync = new BedrockArrivalSync();
        Minecart minecart = mock(Minecart.class);
        when(minecart.getUniqueId()).thenReturn(UUID.randomUUID());

        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            sync.stop(minecart);

            scheduler.verify(() -> SchedulerUtil.cancelTask(any()), never());
        }
    }

    private Minecart stationaryCart(Player passenger) {
        Minecart minecart = mock(Minecart.class);
        when(minecart.getUniqueId()).thenReturn(UUID.randomUUID());
        when(minecart.isDead()).thenReturn(false);
        when(minecart.isValid()).thenReturn(true);
        when(minecart.getVelocity()).thenReturn(new Vector(0, 0, 0));
        when(passenger.getVehicle()).thenReturn(minecart);
        return minecart;
    }

    private Runnable captureScheduledTick(BedrockArrivalSync sync, Plugin plugin, Player passenger,
            Minecart minecart) {
        AtomicReference<Runnable> captured = new AtomicReference<>();
        try (var scheduler = mockStatic(SchedulerUtil.class)) {
            scheduler.when(() -> SchedulerUtil.entityRun(eq(plugin), eq(minecart), any(Runnable.class),
                    anyLong(), anyLong())).thenAnswer(invocation -> {
                        captured.set(invocation.getArgument(2));
                        return new Object();
                    });
            sync.start(plugin, passenger, minecart);
        }
        return captured.get();
    }
}
