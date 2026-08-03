package org.cubexmc.metro.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class TrainLeafHelpersTest {

    @Test
    void navigatorDecisionsPreserveDepartureAndLoopArrivalRules() {
        TrainNavigatorDecisions.DepartureDecision terminal =
                TrainNavigatorDecisions.resolveDeparture(1, 0, -1);
        assertTrue(terminal.shouldTerminate);
        assertEquals(-1, terminal.targetIndex);

        TrainNavigatorDecisions.DepartureDecision departure =
                TrainNavigatorDecisions.resolveDeparture(3, 1, -1);
        assertFalse(departure.shouldTerminate);
        assertEquals(2, departure.targetIndex);

        TrainNavigatorDecisions.ArrivalDecision invalid =
                TrainNavigatorDecisions.resolveArrival(null, 0, null, false);
        assertFalse(invalid.valid);

        TrainNavigatorDecisions.ArrivalDecision loopArrival =
                TrainNavigatorDecisions.resolveArrival(List.of("alpha", "beta", "alpha"), 2, "alpha", true);
        assertTrue(loopArrival.valid);
        assertEquals(0, loopArrival.currentIndex);
        assertEquals(-1, loopArrival.targetIndex);
        assertEquals(TrainInstance.TrainState.WAITING, loopArrival.nextState);
        assertFalse(loopArrival.terminal);
        assertEquals(1, loopArrival.nextStopIndex);

        TrainNavigatorDecisions.ArrivalDecision terminalArrival =
                TrainNavigatorDecisions.resolveArrival(List.of("alpha", "beta"), 1, "beta", false);
        assertTrue(terminalArrival.terminal);
        assertEquals(TrainInstance.TrainState.TERMINATING, terminalArrival.nextState);
        assertEquals(-1, terminalArrival.nextStopIndex);
    }

    @Test
    void passengerRegistryPreservesNullableAndSnapshotBehavior() {
        TrainPassengerRegistry registry = new TrainPassengerRegistry();
        Player player = mock(Player.class);
        Minecart cart = mock(Minecart.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);

        assertFalse(registry.add(null, cart));
        assertTrue(registry.add(player, cart));
        assertFalse(registry.add(player, cart));
        assertTrue(registry.contains(player));
        assertTrue(registry.contains(playerId));
        assertSame(cart, registry.cartFor(player));
        assertEquals(List.of(player), registry.onlinePassengers());
        assertTrue(registry.hasOnlinePassengers());

        Set<UUID> snapshot = registry.snapshotPassengerIds();
        registry.remove(player);
        assertEquals(Set.of(playerId), snapshot);
        assertFalse(registry.contains(playerId));
        assertNull(registry.cartFor(null));
        assertFalse(registry.contains((HumanEntity) null));
        assertFalse(registry.contains((UUID) null));

        registry.add(player, cart);
        registry.clear();
        assertFalse(registry.hasOnlinePassengers());
    }

    @Test
    void stateMathPreservesEtaProgressAndRepeatedTerminalLoopRules() {
        List<String> stops = List.of("alpha", "beta", "gamma");
        TrainStateMath.SegmentSecondsLookup seconds = (from, to) -> {
            if ("alpha".equals(from) && "beta".equals(to)) {
                return 10.0;
            }
            if ("beta".equals(from) && "gamma".equals(to)) {
                return 20.0;
            }
            return 30.0;
        };

        assertEquals(0.0, TrainStateMath.estimateEtaSecondsToStop(
                TrainInstance.TrainState.WAITING, stops, 0, -1, false, "alpha", 0.0, seconds));
        assertEquals(6.0, TrainStateMath.estimateEtaSecondsToStop(
                TrainInstance.TrainState.MOVING, stops, 0, 1, false, "beta", 4.0, seconds));
        assertEquals(26.0, TrainStateMath.estimateEtaSecondsToStop(
                TrainInstance.TrainState.MOVING, stops, 0, 1, false, "gamma", 4.0, seconds));
        assertEquals(0.4, TrainStateMath.estimateVirtualProgress(
                TrainInstance.TrainState.MOVING, 0, 1, stops, 4.0, seconds));

        assertEquals(10.0, TrainStateMath.estimateEtaSecondsToStop(
                TrainInstance.TrainState.WAITING,
                List.of("alpha", "beta", "alpha"), 2, -1, true, "beta", 0.0, seconds));
        assertTrue(Double.isInfinite(TrainStateMath.estimateEtaSecondsToStop(
                TrainInstance.TrainState.WAITING, stops, -1, -1, false, "gamma", 0.0, seconds)));
        assertEquals(0.0, TrainStateMath.estimateVirtualProgress(
                TrainInstance.TrainState.WAITING, 0, 1, stops, 4.0, seconds));
        assertTrue(TrainStateMath.isVirtualWaitingState(TrainInstance.TrainState.WAITING));
        assertTrue(TrainStateMath.isVirtualWaitingState(TrainInstance.TrainState.TERMINATING));
        assertFalse(TrainStateMath.isVirtualWaitingState(TrainInstance.TrainState.MOVING));
    }
}
