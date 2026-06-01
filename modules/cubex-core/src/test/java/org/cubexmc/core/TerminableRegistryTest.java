package org.cubexmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TerminableRegistryTest {

    @Test
    void closeAll_closesResourcesInLifoOrder() {
        // Arrange
        TerminableRegistry registry = new TerminableRegistry();
        List<String> closed = new ArrayList<>();
        Runnable first = () -> closed.add("first");
        Runnable second = () -> closed.add("second");
        Runnable third = () -> closed.add("third");
        registry.bind(first);
        registry.bind(second);
        registry.bind(third);

        // Act
        registry.closeAll(exception -> {
        });

        // Assert
        assertEquals(List.of("third", "second", "first"), closed);
    }

    @Test
    void closeAll_continuesAfterCloseFailure() {
        // Arrange
        TerminableRegistry registry = new TerminableRegistry();
        List<String> closed = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        Runnable first = () -> closed.add("first");
        Runnable third = () -> closed.add("third");
        registry.bind(first);
        registry.bind((AutoCloseable) () -> {
            throw new IllegalStateException("boom");
        });
        registry.bind(third);

        // Act
        registry.closeAll(failures::add);

        // Assert
        assertEquals(List.of("third", "first"), closed);
        assertEquals(1, failures.size());
        assertEquals("boom", failures.get(0).getMessage());
    }
}
