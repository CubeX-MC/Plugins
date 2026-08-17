package org.cubexmc.integrations;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionalServiceConnectorTest {
    private PluginManager plugins;
    private ServicesManager services;
    private Plugin provider;
    private OptionalServiceConnector connector;
    private OptionalServiceDescriptor descriptor;

    @BeforeEach
    void setUp() {
        plugins = mock(PluginManager.class);
        services = mock(ServicesManager.class);
        provider = mock(Plugin.class);
        connector = new OptionalServiceConnector(plugins, services);
        descriptor = new OptionalServiceDescriptor("Provider", TestService.class.getName());
    }

    @Test
    void missingPluginIsAnExpectedUnavailableState() {
        var result = connector.connect(descriptor);

        assertUnavailable(result, ServiceUnavailableReason.PLUGIN_MISSING);
    }

    @Test
    void disabledPluginDoesNotExposeItsService() {
        when(plugins.getPlugin("Provider")).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(false);

        var result = connector.connect(descriptor);

        assertUnavailable(result, ServiceUnavailableReason.PLUGIN_DISABLED);
    }

    @Test
    void missingRegistrationDoesNotCreateAPluginDependency() {
        enableProvider();

        var result = connector.connect(descriptor);

        assertUnavailable(result, ServiceUnavailableReason.SERVICE_NOT_REGISTERED);
    }

    @Test
    void resolvesTheServiceWithTheProviderClassLoader() {
        enableProvider();
        TestService service = () -> "connected";
        when(services.load(TestService.class)).thenReturn(service);

        var result = connector.connect(descriptor);

        assertTrue(result instanceof OptionalServiceConnection.Connected);
        var connected = (OptionalServiceConnection.Connected) result;
        assertSame(TestService.class, connected.getApiType());
        assertSame(service, connected.getService());
        assertSame(provider, connected.getProvider());
    }

    @Test
    void missingProviderApiIsReportedWithoutLinkingIt() {
        enableProvider();
        var unknown = new OptionalServiceDescriptor("Provider", "example.missing.ProviderApi");

        var result = connector.connect(unknown);

        assertUnavailable(result, ServiceUnavailableReason.API_CLASS_MISSING);
    }

    private void enableProvider() {
        when(plugins.getPlugin("Provider")).thenReturn(provider);
        when(provider.isEnabled()).thenReturn(true);
    }

    private static void assertUnavailable(
        OptionalServiceConnection connection,
        ServiceUnavailableReason expected
    ) {
        assertTrue(connection instanceof OptionalServiceConnection.Unavailable);
        assertEquals(expected, ((OptionalServiceConnection.Unavailable) connection).getReason());
    }

    interface TestService {
        String value();
    }
}
