package org.cubexmc.metro.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LanguageManagerInteropTest {

    @Test
    void shouldKeepStaticPlaceholderBuilderForJavaCallers() {
        Map<String, Object> arguments = LanguageManager.args();

        Map<String, Object> returned = LanguageManager.put(arguments, "line_id", "red");

        assertSame(arguments, returned);
        assertEquals("red", arguments.get("line_id"));
    }
}
