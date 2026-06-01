package org.cubexmc.mountlicense.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownTracker {

    private final Map<UUID, Long> until = new ConcurrentHashMap<>();

    public boolean tryAcquire(UUID player, int seconds) {
        if (seconds <= 0) return true;
        long now = System.currentTimeMillis();
        Long expiresAt = until.get(player);
        if (expiresAt != null && expiresAt > now) return false;
        until.put(player, now + seconds * 1000L);
        return true;
    }

    public long remainingSeconds(UUID player) {
        Long expiresAt = until.get(player);
        if (expiresAt == null) return 0;
        long remaining = expiresAt - System.currentTimeMillis();
        return remaining <= 0 ? 0 : Math.max(1, remaining / 1000L);
    }

    public void clear(UUID player) {
        until.remove(player);
    }
}
