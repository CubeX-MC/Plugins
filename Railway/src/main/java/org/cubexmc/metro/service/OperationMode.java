package org.cubexmc.metro.service;

public enum OperationMode {
    LOCAL,
    GLOBAL;

    public static OperationMode from(String s, OperationMode def) {
        if (s == null) return def;
        switch (s.trim().toLowerCase()) {
            case "local": return LOCAL;
            case "global": return GLOBAL;
            default: return def;
        }
    }
}


