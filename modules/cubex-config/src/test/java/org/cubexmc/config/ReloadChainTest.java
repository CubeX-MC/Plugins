package org.cubexmc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReloadChainTest {

    @Test
    void reloadsEntriesInRegistrationOrder() throws Exception {
        // Arrange
        List<String> calls = new ArrayList<>();
        ReloadChain chain = ReloadChain.create()
                .add("config", (Runnable) () -> calls.add("config"))
                .add("language", (Runnable) () -> calls.add("language"))
                .add("storage", (Runnable) () -> calls.add("storage"));

        // Act
        chain.reload();

        // Assert
        assertEquals(List.of("config", "language", "storage"), calls);
        assertEquals(List.of("config", "language", "storage"), chain.names());
    }
}
