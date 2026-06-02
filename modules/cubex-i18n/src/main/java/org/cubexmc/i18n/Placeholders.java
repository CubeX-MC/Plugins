package org.cubexmc.i18n;

import java.util.HashMap;
import java.util.Map;

public final class Placeholders {

    private Placeholders() {
    }

    public static Map<String, Object> empty() {
        return new HashMap<>();
    }

    public static Map<String, Object> of(String key, Object value) {
        Map<String, Object> placeholders = empty();
        placeholders.put(key, value);
        return placeholders;
    }

    public static Map<String, Object> put(Map<String, Object> args, String key, Object value) {
        args.put(key, value);
        return args;
    }
}
