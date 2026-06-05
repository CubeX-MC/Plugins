package org.cubexmc.clarity;

import com.google.common.collect.Multimap;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 清理核心。所有对玩家属性/效果的读写都通过该玩家的 {@code EntityScheduler} 调度,
 * 在 Paper 与 Folia 上均线程安全。命令与进服监听共用本服务。
 */
public final class ClarityService {

    private final ClarityPlugin plugin;

    public ClarityService(ClarityPlugin plugin) {
        this.plugin = plugin;
    }

    // ==================== 对外入口(负责调度到玩家所在区域线程)====================

    public void scan(CommandSender sender, Player p) {
        p.getScheduler().run(plugin, t -> doScan(sender, p), null);
    }

    public void purgeAttr(CommandSender sender, Player p, String pattern) {
        p.getScheduler().run(plugin, t -> doPurgeAttr(sender, p, pattern), null);
    }

    public void purgeEffect(CommandSender sender, Player p, String what) {
        p.getScheduler().run(plugin, t -> doPurgeEffect(sender, p, what), null);
    }

    public void scanItems(CommandSender sender, Player p, ItemScope scope) {
        p.getScheduler().run(plugin, t -> doScanItems(sender, p, scope), null);
    }

    public void sweepItems(CommandSender sender, Player p, ItemScope scope) {
        p.getScheduler().run(plugin, t -> doSweepItems(sender, p, scope), null);
    }

    public void purgeLevelToolsItems(CommandSender sender, Player p, ItemScope scope) {
        p.getScheduler().run(plugin, t -> doPurgeLevelToolsItems(sender, p, scope), null);
    }

    /** 按当前 config 黑名单清扫;delayTicks>=1 时延迟执行(进服用),honours dry-run。sender 可为 null。 */
    public void sweep(CommandSender sender, Player p, long delayTicks) {
        if (delayTicks <= 0) {
            p.getScheduler().run(plugin, t -> doSweep(sender, p), null);
        } else {
            p.getScheduler().runDelayed(plugin, t -> doSweep(sender, p), null, delayTicks);
        }
    }

    // ==================== 实际逻辑(已在玩家区域线程上)====================

    private void doScan(CommandSender sender, Player p) {
        msg(sender, "§6=== [" + p.getName() + "] attribute modifiers ===");
        boolean any = false;
        for (Attribute attr : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(attr);
            if (inst == null || inst.getModifiers().isEmpty()) {
                continue;
            }
            any = true;
            msg(sender, String.format(Locale.ROOT, "§e%s §7base=%.4f total=%.4f",
                    attr.getKey(), inst.getBaseValue(), inst.getValue()));
            for (AttributeModifier m : inst.getModifiers()) {
                msg(sender, String.format(Locale.ROOT, "   §f- %s §7amount=%.4f op=%s",
                        m.getKey(), m.getAmount(), m.getOperation()));
            }
        }
        if (!any) {
            msg(sender, "§a(none — attributes are clean)");
        }

        msg(sender, "§6=== [" + p.getName() + "] potion effects ===");
        var effects = p.getActivePotionEffects();
        if (effects.isEmpty()) {
            msg(sender, "§a(none)");
            return;
        }
        for (PotionEffect e : effects) {
            boolean inf = ClarityMatcher.isInfiniteDuration(e.getDuration());
            String dur = inf ? "INFINITE/long(" + e.getDuration() + ")" : String.valueOf(e.getDuration());
            msg(sender, String.format(Locale.ROOT, "   §f- %s §7amp=%d dur=%s%s",
                    e.getType().getKey(), e.getAmplifier(), dur, inf ? " §c<-- infinite/very long" : ""));
        }
    }

    private void doPurgeAttr(CommandSender sender, Player p, String pattern) {
        if (pattern.equalsIgnoreCase("minecraft")) {
            msg(sender, "§cRefusing to purge the whole 'minecraft' namespace. Target a specific id instead.");
            return;
        }
        List<String> blacklist = Collections.singletonList(pattern);
        List<String> removed = removeMatchingAttr(p, blacklist, false);
        p.saveData();
        msg(sender, "§aRemoved §f" + removed.size() + "§a attribute modifier(s) matching '" + pattern + "' from " + p.getName() + ".");
        removed.forEach(l -> msg(sender, "   §7" + l));
        plugin.getLogger().info("[purge attr] player=" + p.getName() + " pattern='" + pattern + "' removed=" + removed.size() + " " + removed);
    }

    private void doPurgeEffect(CommandSender sender, Player p, String what) {
        boolean allInfinite = what.equalsIgnoreCase("all-infinite");
        List<String> types = allInfinite ? Collections.emptyList() : Collections.singletonList(what);
        List<String> removed = removeMatchingEffects(p, types, allInfinite, false);
        p.saveData();
        msg(sender, "§aRemoved §f" + removed.size() + "§a potion effect(s) [" + what + "] from " + p.getName() + ".");
        removed.forEach(l -> msg(sender, "   §7" + l));
        plugin.getLogger().info("[purge effect] player=" + p.getName() + " what='" + what + "' removed=" + removed.size() + " " + removed);
    }

    private void doSweep(CommandSender sender, Player p) {
        ClarityConfig cfg = plugin.config();
        boolean dry = cfg.dryRun();
        String tag = dry ? "[DRY-RUN] " : "";

        List<String> attrHits = removeMatchingAttr(p, cfg.attributeModifierIds(), dry);
        List<String> effectHits = removeMatchingEffects(p, cfg.effectTypes(), false, dry);

        if (!attrHits.isEmpty() || !effectHits.isEmpty()) {
            if (!dry) {
                p.saveData();
            }
            plugin.getLogger().info(tag + "sweep player=" + p.getName()
                    + " attrRemoved=" + attrHits.size() + " effectRemoved=" + effectHits.size()
                    + " attrs=" + attrHits + " effects=" + effectHits);
            msg(sender, "§e" + tag + "§aSweep " + p.getName() + ": "
                    + attrHits.size() + " modifier(s), " + effectHits.size() + " effect(s)"
                    + (dry ? " §7(would be removed)" : " §aremoved") + ".");
            attrHits.forEach(l -> msg(sender, "   §7attr: " + l));
            effectHits.forEach(l -> msg(sender, "   §7effect: " + l));
        } else {
            msg(sender, "§aSweep " + p.getName() + ": nothing matched the blacklist.");
        }
    }

    private void doScanItems(CommandSender sender, Player p, ItemScope scope) {
        ItemRunSummary summary = runItemCleanup(sender, p, scope, ItemCleanupMode.SWEEP, true);
        if (summary.matches == 0) {
            msg(sender, "§aItem scan " + p.getName() + " [" + scope.id() + "]: nothing matched configured rules.");
        } else {
            msg(sender, "§eItem scan " + p.getName() + " [" + scope.id() + "]: "
                    + summary.matches + " suspicious item(s) across " + summary.scanned + " non-empty slot(s).");
        }
    }

    private void doSweepItems(CommandSender sender, Player p, ItemScope scope) {
        boolean dry = plugin.config().dryRun();
        ItemRunSummary summary = runItemCleanup(sender, p, scope, ItemCleanupMode.SWEEP, dry);
        String tag = dry ? "[DRY-RUN] " : "";
        if (summary.matches == 0) {
            msg(sender, "§a" + tag + "Item sweep " + p.getName() + " [" + scope.id() + "]: nothing matched configured rules.");
            return;
        }
        if (!dry) {
            p.updateInventory();
            p.saveData();
        }
        plugin.getLogger().info(tag + "item sweep player=" + p.getName() + " scope=" + scope.id()
                + " matched=" + summary.matches + " changed=" + summary.changed + " details=" + summary.details);
        msg(sender, "§e" + tag + "§aItem sweep " + p.getName() + " [" + scope.id() + "]: "
                + summary.matches + " item(s), " + summary.changed + " changed"
                + (dry ? " §7(would be changed)" : " §aremoved") + ".");
    }

    private void doPurgeLevelToolsItems(CommandSender sender, Player p, ItemScope scope) {
        ItemRunSummary summary = runItemCleanup(sender, p, scope, ItemCleanupMode.LEVELTOOLS_PURGE, false);
        if (summary.matches == 0) {
            msg(sender, "§aItem purge " + p.getName() + " [" + scope.id() + "]: no LevelTools data found.");
            return;
        }
        p.updateInventory();
        p.saveData();
        plugin.getLogger().info("item purge leveltools player=" + p.getName() + " scope=" + scope.id()
                + " matched=" + summary.matches + " changed=" + summary.changed + " details=" + summary.details);
        msg(sender, "§aItem purge " + p.getName() + " [" + scope.id() + "]: removed LevelTools data from "
                + summary.changed + " item(s).");
    }

    // ==================== 共用的扫描/移除 ====================

    /** 移除(或 dryRun 仅列出)命中黑名单的 attribute modifier;返回命中明细。 */
    private List<String> removeMatchingAttr(Player p, List<String> blacklist, boolean dryRun) {
        List<String> hits = new ArrayList<>();
        for (Attribute attr : Registry.ATTRIBUTE) {
            AttributeInstance inst = p.getAttribute(attr);
            if (inst == null) {
                continue;
            }
            for (AttributeModifier m : new ArrayList<>(inst.getModifiers())) {
                NamespacedKey key = m.getKey();
                if (ClarityMatcher.matchesModifier(key.toString(), key.getNamespace(), blacklist)) {
                    hits.add(attr.getKey() + " <- " + key + " (amount=" + m.getAmount() + ", op=" + m.getOperation() + ")");
                    if (!dryRun) {
                        inst.removeModifier(m);
                    }
                }
            }
        }
        return hits;
    }

    /** 移除(或 dryRun 仅列出)命中的药水效果;allInfinite=true 时清所有无限/超长效果。 */
    private List<String> removeMatchingEffects(Player p, List<String> types, boolean allInfinite, boolean dryRun) {
        boolean infiniteOnly = plugin.config().effectsInfiniteOnly();
        List<String> hits = new ArrayList<>();
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            NamespacedKey key = e.getType().getKey();
            boolean infinite = ClarityMatcher.isInfiniteDuration(e.getDuration());
            boolean typeMatch = allInfinite
                    ? infinite
                    : ClarityMatcher.matchesEffect(key.toString(), key.getKey(), types);
            if (!typeMatch) {
                continue;
            }
            // 非 all-infinite 的常规清理:若开启 infinite-only,则跳过有限时长效果(保护玩家喝的药水)。
            if (!allInfinite && infiniteOnly && !infinite) {
                continue;
            }
            String dur = infinite ? "INFINITE/long(" + e.getDuration() + ")" : String.valueOf(e.getDuration());
            hits.add(key + " (amp=" + e.getAmplifier() + ", dur=" + dur + ")");
            if (!dryRun) {
                p.removePotionEffect(e.getType());
            }
        }
        return hits;
    }

    private ItemRunSummary runItemCleanup(CommandSender sender, Player p, ItemScope scope,
                                          ItemCleanupMode mode, boolean dryRun) {
        ClarityConfig cfg = plugin.config();
        ItemRunSummary summary = new ItemRunSummary();
        for (ItemSlot slot : itemSlots(p, scope)) {
            ItemStack item = slot.stack();
            if (item == null || item.getType().isAir()) {
                continue;
            }
            summary.scanned++;
            ItemCleanupResult result = cleanItem(item, cfg, mode, dryRun);
            if (result.hits().isEmpty()) {
                continue;
            }
            summary.matches++;
            summary.details.add(slot.label() + " " + item.getType() + " x" + item.getAmount() + " -> " + result.hits());
            msg(sender, "§e" + slot.label() + " §7" + item.getType() + " x" + item.getAmount());
            result.hits().forEach(hit -> msg(sender, "   §7- " + hit));
            if (!dryRun && result.changed()) {
                slot.setter().accept(result.stack());
                summary.changed++;
            }
        }
        return summary;
    }

    private ItemCleanupResult cleanItem(ItemStack original, ClarityConfig cfg,
                                        ItemCleanupMode mode, boolean dryRun) {
        ItemMeta originalMeta = original.getItemMeta();
        if (originalMeta == null) {
            return ItemCleanupResult.empty(original);
        }

        ItemStack working = dryRun ? original : original.clone();
        ItemMeta meta = dryRun ? originalMeta : working.getItemMeta();
        if (meta == null) {
            return ItemCleanupResult.empty(original);
        }

        List<String> hits = new ArrayList<>();
        boolean changed = false;

        boolean levelToolsRules = mode == ItemCleanupMode.LEVELTOOLS_PURGE || cfg.itemLevelToolsEnabled();
        boolean levelToolsMarked = false;
        if (levelToolsRules) {
            PdcResult pdc = cleanLevelToolsPdc(meta, cfg.itemLevelToolsPdcKeys(), dryRun);
            hits.addAll(pdc.hits());
            changed |= pdc.changed();
            levelToolsMarked |= pdc.marked();

            if (mode == ItemCleanupMode.LEVELTOOLS_PURGE || cfg.itemLevelToolsRemoveLore()) {
                LoreResult lore = cleanLevelToolsLore(meta, dryRun);
                hits.addAll(lore.hits());
                changed |= lore.changed();
                levelToolsMarked |= lore.marked();
            }
        }

        AttributeResult attrs = cleanItemAttributes(meta, cfg, mode, levelToolsMarked, dryRun);
        hits.addAll(attrs.hits());
        changed |= attrs.changed();

        if (!dryRun && changed) {
            working.setItemMeta(meta);
        }
        return new ItemCleanupResult(hits, changed, working);
    }

    private PdcResult cleanLevelToolsPdc(ItemMeta meta, List<String> keys, boolean dryRun) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        List<String> hits = new ArrayList<>();
        boolean changed = false;
        boolean marked = false;
        for (NamespacedKey key : new ArrayList<>(pdc.getKeys())) {
            if (!ClarityMatcher.matchesNamespacedKey(key.toString(), key.getNamespace(), keys, false)) {
                continue;
            }
            marked = true;
            hits.add("LevelTools PDC key " + key);
            if (!dryRun) {
                pdc.remove(key);
                changed = true;
            }
        }
        return new PdcResult(hits, changed, marked);
    }

    private LoreResult cleanLevelToolsLore(ItemMeta meta, boolean dryRun) {
        if (!meta.hasLore() || meta.getLore() == null) {
            return new LoreResult(List.of(), false, false);
        }
        List<String> lore = meta.getLore();
        List<String> kept = new ArrayList<>();
        int removed = 0;
        for (String line : lore) {
            if (ClarityMatcher.isLevelToolsLoreLine(line)) {
                removed++;
            } else {
                kept.add(line);
            }
        }
        if (removed == 0) {
            return new LoreResult(List.of(), false, false);
        }
        if (!dryRun) {
            meta.setLore(kept.isEmpty() ? null : kept);
        }
        return new LoreResult(List.of("LevelTools lore line(s) " + removed), true, true);
    }

    private AttributeResult cleanItemAttributes(ItemMeta meta, ClarityConfig cfg, ItemCleanupMode mode,
                                                boolean levelToolsMarked, boolean dryRun) {
        Multimap<Attribute, AttributeModifier> modifiers = meta.getAttributeModifiers();
        if (modifiers == null || modifiers.isEmpty()) {
            return new AttributeResult(List.of(), false);
        }

        List<String> hits = new ArrayList<>();
        boolean changed = false;
        for (Map.Entry<Attribute, AttributeModifier> entry : new ArrayList<>(modifiers.entries())) {
            Attribute attr = entry.getKey();
            AttributeModifier modifier = entry.getValue();
            String reason = attributeRemovalReason(attr, modifier, cfg, mode, levelToolsMarked);
            if (reason == null) {
                continue;
            }
            hits.add(attr.getKey() + " <- " + modifier.getKey()
                    + " (amount=" + modifier.getAmount() + ", op=" + modifier.getOperation() + ", reason=" + reason + ")");
            if (!dryRun) {
                meta.removeAttributeModifier(attr, modifier);
                changed = true;
            }
        }
        return new AttributeResult(hits, changed);
    }

    private String attributeRemovalReason(Attribute attr, AttributeModifier modifier, ClarityConfig cfg,
                                          ItemCleanupMode mode, boolean levelToolsMarked) {
        if (mode == ItemCleanupMode.LEVELTOOLS_PURGE) {
            if (levelToolsMarked && cfg.itemLevelToolsRemoveAttributesOnMarkedItems()) {
                return "leveltools-marked-item";
            }
            return null;
        }

        NamespacedKey modifierKey = modifier.getKey();
        if (modifierKey != null) {
            if (ClarityMatcher.matchesModifier(modifierKey.toString(), modifierKey.getNamespace(),
                    cfg.itemAttributeModifierIds())) {
                return "modifier-id";
            }
        }
        Double max = configuredMaxAmount(attr, cfg.itemAttributeMaxAmounts());
        if (max != null && Math.abs(modifier.getAmount()) > max) {
            return "amount>" + max;
        }
        if (levelToolsMarked && cfg.itemLevelToolsRemoveAttributesOnMarkedItems()) {
            return "leveltools-marked-item";
        }
        return null;
    }

    private Double configuredMaxAmount(Attribute attr, Map<String, Double> maxAmounts) {
        if (maxAmounts.isEmpty()) {
            return null;
        }
        String full = attr.getKey().toString().toLowerCase(Locale.ROOT);
        String path = attr.getKey().getKey().toLowerCase(Locale.ROOT);
        Double fullValue = maxAmounts.get(full);
        if (fullValue != null) {
            return fullValue;
        }
        return maxAmounts.get(path);
    }

    private List<ItemSlot> itemSlots(Player p, ItemScope scope) {
        List<ItemSlot> slots = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        switch (scope) {
            case HAND -> slots.add(new ItemSlot("hand", inv.getItemInMainHand(), inv::setItemInMainHand));
            case INVENTORY -> addStorageSlots(slots, inv);
            case EQUIPMENT -> addEquipmentSlots(slots, inv);
            case ENDER -> addInventorySlots(slots, "ender", p.getEnderChest());
            case ALL -> {
                addStorageSlots(slots, inv);
                addEquipmentSlots(slots, inv);
                addInventorySlots(slots, "ender", p.getEnderChest());
            }
        }
        return slots;
    }

    private void addStorageSlots(List<ItemSlot> slots, PlayerInventory inv) {
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            int slot = i;
            slots.add(new ItemSlot("inventory[" + slot + "]", contents[i], item -> inv.setItem(slot, item)));
        }
    }

    private void addEquipmentSlots(List<ItemSlot> slots, PlayerInventory inv) {
        slots.add(new ItemSlot("equipment[offhand]", inv.getItemInOffHand(), inv::setItemInOffHand));
        slots.add(new ItemSlot("equipment[helmet]", inv.getHelmet(), inv::setHelmet));
        slots.add(new ItemSlot("equipment[chestplate]", inv.getChestplate(), inv::setChestplate));
        slots.add(new ItemSlot("equipment[leggings]", inv.getLeggings(), inv::setLeggings));
        slots.add(new ItemSlot("equipment[boots]", inv.getBoots(), inv::setBoots));
    }

    private void addInventorySlots(List<ItemSlot> slots, String label, Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            int slot = i;
            slots.add(new ItemSlot(label + "[" + slot + "]", inv.getItem(slot), item -> inv.setItem(slot, item)));
        }
    }

    private void msg(CommandSender sender, String text) {
        if (sender != null) {
            sender.sendMessage(text);
        }
    }

    private enum ItemCleanupMode {
        SWEEP,
        LEVELTOOLS_PURGE
    }

    private record ItemSlot(String label, ItemStack stack, Consumer<ItemStack> setter) {
    }

    private record ItemCleanupResult(List<String> hits, boolean changed, ItemStack stack) {
        static ItemCleanupResult empty(ItemStack stack) {
            return new ItemCleanupResult(List.of(), false, stack);
        }
    }

    private record PdcResult(List<String> hits, boolean changed, boolean marked) {
    }

    private record LoreResult(List<String> hits, boolean changed, boolean marked) {
    }

    private record AttributeResult(List<String> hits, boolean changed) {
    }

    private static final class ItemRunSummary {
        private int scanned;
        private int matches;
        private int changed;
        private final List<String> details = new ArrayList<>();
    }
}
