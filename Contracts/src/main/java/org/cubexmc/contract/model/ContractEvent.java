package org.cubexmc.contract.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record ContractEvent(long time, String type, String detail) {
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("time", time);
        map.put("type", type);
        map.put("detail", detail);
        return map;
    }

    public static ContractEvent fromMap(Map<?, ?> map) {
        long time = map.get("time") instanceof Number n ? n.longValue() : 0L;
        Object typeRaw = map.get("type");
        Object detailRaw = map.get("detail");
        String type = typeRaw == null ? "" : typeRaw.toString();
        String detail = detailRaw == null ? "" : detailRaw.toString();
        return new ContractEvent(time, type, detail);
    }

    public static ContractEvent fromLegacyLine(String line) {
        String[] parts = line.split("\\|", 3);
        long time = 0L;
        try {
            time = Long.parseLong(parts[0]);
        } catch (NumberFormatException ignored) {
        }
        String type = parts.length > 1 ? parts[1] : "";
        String detail = parts.length > 2 ? parts[2] : "";
        return new ContractEvent(time, type, detail);
    }
}
