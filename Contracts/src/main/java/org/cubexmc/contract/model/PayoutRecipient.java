package org.cubexmc.contract.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PayoutRecipient {
    public enum Kind {
        PARTICIPANT,
        SYSTEM_SINK,
        ARBITER
    }

    private final Kind kind;
    private final ParticipantRole role;

    private PayoutRecipient(Kind kind, ParticipantRole role) {
        this.kind = kind;
        this.role = role;
    }

    public static PayoutRecipient participant(ParticipantRole role) {
        return new PayoutRecipient(Kind.PARTICIPANT, role);
    }

    public static PayoutRecipient systemSink() {
        return new PayoutRecipient(Kind.SYSTEM_SINK, null);
    }

    public static PayoutRecipient arbiter() {
        return new PayoutRecipient(Kind.ARBITER, null);
    }

    public Kind kind() {
        return kind;
    }

    public ParticipantRole role() {
        return role;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind.name());
        if (role != null) {
            map.put("role", role.name());
        }
        return map;
    }

    public static PayoutRecipient fromMap(Map<?, ?> map) {
        Kind kind = Kind.valueOf(Objects.toString(map.get("kind"), "SYSTEM_SINK"));
        if (kind == Kind.PARTICIPANT) {
            return participant(ParticipantRole.valueOf(Objects.toString(map.get("role"), "OWNER")));
        }
        if (kind == Kind.ARBITER) {
            return arbiter();
        }
        return systemSink();
    }
}
