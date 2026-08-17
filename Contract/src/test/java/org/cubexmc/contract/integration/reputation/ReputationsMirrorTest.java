package org.cubexmc.contract.integration.reputation;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.cubexmc.core.CubexLogger;
import org.cubexmc.integrations.OptionalServiceConnector;
import org.cubexmc.reputations.api.ReputationField;
import org.cubexmc.reputations.api.ReputationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReputationsMirrorTest {
    @Test
    void registersProviderFieldsAndMirrorsDeltasThroughProviderClassLoader() {
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        Plugin provider = mock(Plugin.class);
        RecordingService service = new RecordingService();
        when(pluginManager.getPlugin("Reputations")).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(true);
        when(servicesManager.load(ReputationService.class)).thenReturn(service);

        ReputationsMirror mirror = new ReputationsMirror(
            new OptionalServiceConnector(pluginManager, servicesManager),
            new CubexLogger(Logger.getLogger("ReputationsMirrorTest"))
        );

        assertTrue(mirror.available());
        assertEquals(
            List.of("Contract:completed", "Contract:cancelled", "Contract:expired", "Contract:disputed"),
            service.fields.stream().map(ReputationField::key).toList()
        );
        assertTrue(service.fields.get(0).higherIsBetter());
        assertFalse(service.fields.get(1).higherIsBetter());

        UUID player = UUID.randomUUID();
        mirror.add(player, "completed", 1.0);

        assertEquals(List.of(new Delta(player, "Contract:completed", 1.0)), service.deltas);
        assertEquals(4, service.fields.size(), "the same service binding must not re-register fields");
    }

    @Test
    void missingProviderIsAQuietNoOp() {
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        ReputationsMirror mirror = new ReputationsMirror(
            new OptionalServiceConnector(pluginManager, servicesManager),
            new CubexLogger(Logger.getLogger("ReputationsMirrorTest"))
        );

        assertFalse(mirror.available());
        mirror.add(UUID.randomUUID(), "completed", 1.0);

        verifyNoInteractions(servicesManager);
    }

    @Test
    void providerDiscoveryFailureNeverEscapesTheOptionalBridge() {
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager servicesManager = mock(ServicesManager.class);
        when(pluginManager.getPlugin("Reputations")).thenThrow(new IllegalStateException("registry unavailable"));
        ReputationsMirror mirror = new ReputationsMirror(
            new OptionalServiceConnector(pluginManager, servicesManager),
            new CubexLogger(Logger.getLogger("ReputationsMirrorTest"))
        );

        assertFalse(mirror.available());
        mirror.add(UUID.randomUUID(), "completed", 1.0);
    }

    private static final class RecordingService implements ReputationService {
        private final List<ReputationField> fields = new ArrayList<>();
        private final List<Delta> deltas = new ArrayList<>();

        @Override
        public void registerField(ReputationField field) {
            fields.add(field);
        }

        @Override
        public double add(UUID playerId, String fieldKey, double delta) {
            deltas.add(new Delta(playerId, fieldKey, delta));
            return delta;
        }
    }

    private record Delta(UUID playerId, String fieldKey, double value) {}
}
