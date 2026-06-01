package org.cubexmc.metro.physics;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;
import org.cubexmc.metro.Metro;
import org.cubexmc.metro.train.TrainInstance;
import org.cubexmc.metro.util.SchedulerUtil;

/**
 * Manages invisible leashable entities between cars for visual coupling.
 */
public class LeashCoupler {

    private final Metro plugin;
    private final TrainInstance train;
    private final List<LivingEntity> dummies = new ArrayList<>();
    private double offsetY;

    public LeashCoupler(Metro plugin, TrainInstance train) {
        this.plugin = plugin;
        this.train = train;
        this.offsetY = plugin.getLeashOffsetY();
    }

    public void start() {
        cleanup();
        List<Minecart> cars = train.getConsist().getCars();
        if (cars.size() < 2) return;

        EntityType type = parseEntityType(plugin.getLeashMobTypeRaw());
        if (type == null) return;

        for (int i = 1; i < cars.size(); i++) {
            Minecart prev = cars.get(i - 1);
            Minecart cur = cars.get(i);
            if (prev == null || cur == null || prev.isDead() || cur.isDead()) continue;

            Location base = midpoint(prev.getLocation(), cur.getLocation()).add(0, offsetY, 0);
            LivingEntity dummy = base.getWorld().spawn(base, type.getEntityClass().asSubclass(LivingEntity.class), ent -> {
                ent.setInvisible(true);
                ent.setSilent(true);
                ent.setInvulnerable(true);
                ent.setGravity(false);
                ent.setCollidable(false);
                if (ent instanceof Mob) {
                    ((Mob) ent).setAI(false);
                }
            });
            // Set leash holder to previous cart (visual rope between prev and dummy)
            try {
                dummy.setLeashHolder(prev);
            } catch (Throwable ignored) {}
            dummies.add(dummy);
        }
    }

    public void update() {
        List<Minecart> cars = train.getConsist().getCars();
        if (dummies.isEmpty() || cars.size() < 2) return;
        int count = Math.min(dummies.size(), Math.max(0, cars.size() - 1));
        for (int i = 0; i < count; i++) {
            LivingEntity dummy = dummies.get(i);
            Minecart prev = cars.get(i);
            Minecart cur = cars.get(i + 1);
            if (dummy == null || dummy.isDead() || prev == null || cur == null || prev.isDead() || cur.isDead()) continue;
            Location target = midpoint(prev.getLocation(), cur.getLocation()).add(0, offsetY, 0);
            SchedulerUtil.teleportEntity(dummy, target);
            // Ensure leash still tied to prev
            if (!dummy.isLeashed() || dummy.getLeashHolder() != prev) {
                try { dummy.setLeashHolder(prev); } catch (Throwable ignored) {}
            }
        }
    }

    public void cleanup() {
        for (LivingEntity le : new ArrayList<>(dummies)) {
            if (le != null && !le.isDead()) {
                try { le.setLeashHolder(null); } catch (Throwable ignored) {}
                le.remove();
            }
        }
        dummies.clear();
    }

    private static Location midpoint(Location a, Location b) {
        if (a == null) return b;
        if (b == null) return a;
        Vector va = a.toVector();
        Vector vb = b.toVector();
        Vector vm = va.clone().add(vb).multiply(0.5);
        return new Location(a.getWorld(), vm.getX(), vm.getY(), vm.getZ());
    }

    private static EntityType parseEntityType(String raw) {
        if (raw == null) return null;
        try {
            EntityType t = EntityType.valueOf(raw.trim().toUpperCase());
            if (!t.isAlive()) return null;
            return t;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}


