package org.cubexmc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class CubexCommandSuggestionsTest {
    @Test
    void emptyPaperArgumentsReturnAllRootCandidates() {
        assertEquals(
                List.of("game", "help"),
                CubexCommandSuggestions.root(new String[0], List.of("game", "help")));
    }

    @Test
    void onePartialArgumentFiltersCaseInsensitively() {
        assertEquals(
                List.of("game"),
                CubexCommandSuggestions.root(new String[] {"GA"}, List.of("game", "help")));
    }

    @Test
    void deeperArgumentsRemainOwnedByThePluginRouter() {
        assertNull(CubexCommandSuggestions.root(new String[] {"game", ""}, List.of("game", "help")));
    }
}
