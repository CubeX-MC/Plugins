package org.cubexmc.booklite.listener;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.cubexmc.booklite.BookLitePlugin;
import org.cubexmc.booklite.lang.LanguageManager;
import org.cubexmc.booklite.model.BookRecord;
import org.cubexmc.booklite.service.BookCodec;
import org.cubexmc.booklite.service.BookRestorer;
import org.cubexmc.booklite.service.BookService;

public class BookListener implements Listener {

    private final BookLitePlugin plugin;
    private final BookService books;
    private final BookCodec codec;
    private final BookRestorer restorer;
    private final LanguageManager lang;

    public BookListener(BookLitePlugin plugin, BookService books,
                        BookCodec codec, BookRestorer restorer, LanguageManager lang) {
        this.plugin = plugin;
        this.books = books;
        this.codec = codec;
        this.restorer = restorer;
        this.lang = lang;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(PlayerEditBookEvent event) {
        if (!event.isSigning()) return;
        if (!plugin.configManager().isAutoConvertSignedBooks()) return;
        if (plugin.configManager().isUninstallMode()) return;

        Player player = event.getPlayer();
        BookMeta originalMeta = event.getNewBookMeta();
        BookRecord draft = codec.createRecord(originalMeta, player.getName());
        BookCodec.ValidationResult validation = codec.validate(draft);
        if (!validation.ok()) {
            event.setCancelled(true);
            sendValidation(player, validation);
            return;
        }

        BookRecord record;
        try {
            record = books.saveOrGet(draft);
        } catch (SQLException ex) {
            books.logStorageFailure("sign", ex);
            lang.send(player, "book.fail_storage");
            return;
        }
        int generation = codec.generationToInt(originalMeta);
        int slot = event.getSlot();
        // Spigot strips PDC from the meta passed to setNewBookMeta() during the
        // writable→written conversion, so swap on the next tick where
        // setItemMeta keeps PDC intact.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || current.getType() != Material.WRITTEN_BOOK) return;
            if (codec.isBookLite(current)) return;
            player.getInventory().setItem(slot,
                    codec.createShell(record, generation, current.getAmount()));
            if (plugin.configManager().isLogConversions()) {
                plugin.getLogger().info(player.getName() + " signed BookLite book " + record.id());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onReadSuppressVanilla(PlayerInteractEvent event) {
        if (heldBookReadAttempt(event) == null) return;
        cancelVanillaBookOpen(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRead(PlayerInteractEvent event) {
        ReadAttempt attempt = heldBookReadAttempt(event);
        if (attempt != null) {
            cancelVanillaBookOpen(event);
            openHeldBookLite(event, attempt);
            return;
        }

        if (!isMainHandRightClick(event)) return;
        if (event.isCancelled()) return;
        tryOpenLectern(event);
    }

    private void openHeldBookLite(PlayerInteractEvent event, ReadAttempt attempt) {
        if (!event.getPlayer().hasPermission("booklite.use")) {
            lang.send(event.getPlayer(), "commands.no_permission");
            return;
        }
        openBookLite(event.getPlayer(), attempt.id(), attempt.generation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!plugin.configManager().isAllowCraftingCopy()) return;
        CraftingInventory inv = event.getInventory();
        ItemStack shell = null;
        int blankBooks = 0;
        boolean hasOtherIngredient = false;

        for (ItemStack item : inv.getMatrix()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (codec.isBookLite(item)) {
                if (shell != null) {
                    inv.setResult(null);
                    return;
                }
                shell = item;
            } else if (item.getType() == Material.WRITABLE_BOOK) {
                blankBooks++;
            } else {
                hasOtherIngredient = true;
            }
        }
        if (hasOtherIngredient) return;
        if (shell == null || blankBooks <= 0) return;

        int generation = codec.readGeneration(shell);
        if (!codec.canCopyGeneration(generation)) {
            inv.setResult(null);
            return;
        }

        try {
            BookRecord record = books.find(codec.readBookId(shell));
            if (record == null) {
                inv.setResult(null);
                return;
            }
            inv.setResult(codec.createShell(record, codec.nextGeneration(generation), blankBooks));
        } catch (SQLException ex) {
            books.logStorageFailure("craft", ex);
            inv.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.configManager().isUninstallMode()) return;
        if (!plugin.configManager().isPassiveOnPlayerJoin()) return;
        restorer.restoreInventoryAsync(event.getPlayer().getInventory(),
                plugin.configManager().getMaxItemsPerTick());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!plugin.configManager().isUninstallMode()) return;
        if (!plugin.configManager().isPassiveOnInventoryOpen()) return;
        restorer.restoreInventoryAsync(event.getInventory(),
                plugin.configManager().getMaxItemsPerTick());
    }

    private boolean shouldOpenHeldBook(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return true;
        Block block = event.getClickedBlock();
        if (block == null) return true;
        // Leave interactive blocks (empty lecterns, chests, doors) to vanilla so a
        // shell book can still be placed into a lectern and containers open normally.
        return !block.getType().isInteractable();
    }

    private ReadAttempt heldBookReadAttempt(PlayerInteractEvent event) {
        if (!isMainHandRightClick(event)) return null;
        if (!shouldOpenHeldBook(event)) return null;
        ItemStack item = event.getItem();
        String id = codec.readResolvableBookId(item);
        if (id == null) return null;
        return new ReadAttempt(id, codec.readGeneration(item));
    }

    private boolean isMainHandRightClick(PlayerInteractEvent event) {
        return event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                && (event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK);
    }

    private boolean tryOpenLectern(PlayerInteractEvent event) {
        if (!plugin.configManager().isLecternEnabled()) return false;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return false;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.LECTERN) return false;
        if (!(block.getState() instanceof Lectern lectern)) return false;
        ItemStack book = lectern.getInventory().getItem(0);
        String id = codec.readResolvableBookId(book);
        if (id == null) return false;
        if (!event.getPlayer().hasPermission("booklite.use")) {
            lang.send(event.getPlayer(), "commands.no_permission");
            cancelVanillaBookOpen(event);
            return true;
        }
        cancelVanillaBookOpen(event);
        openBookLite(event.getPlayer(), id, codec.readGeneration(book));
        return true;
    }

    private void cancelVanillaBookOpen(PlayerInteractEvent event) {
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private void openBookLite(Player player, String id, int generation) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            BookService.Resolution resolution = BookService.Resolution.notFound();
            try {
                resolution = books.resolveAndMarkAccessed(id);
            } catch (SQLException ex) {
                books.logStorageFailure("read", ex);
            }
            BookService.Resolution finalResolution = resolution;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                if (finalResolution.ambiguous()) {
                    lang.send(player, "commands.ambiguous_id");
                    return;
                }
                BookRecord finalRecord = finalResolution.record();
                displayBook(player, id, finalRecord, generation);
                if (finalRecord == null) {
                    lang.send(player, "book.fail_missing");
                }
            });
        });
    }

    private void displayBook(Player player, String id, BookRecord record, int generation) {
        ItemStack readable = codec.createReadable(record, generation);
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack original = player.getInventory().getItem(slot);

        // Refresh shell title/author so any record update is reflected after
        // the book GUI closes and the shell becomes visible again.
        if (record != null
                && original != null && codec.isBookLite(original)
                && matchesResolvedId(codec.readResolvableBookId(original), id, record.id())) {
            BookMeta meta = (BookMeta) original.getItemMeta();
            codec.applyShellMeta(meta, record, codec.readGeneration(original));
            original.setItemMeta(meta);
        }

        // ClientboundOpenBookPacket only carries the hand, not item NBT — the
        // client renders whatever is in main hand. Put the full-content book in
        // the slot for the open packet, then restore on the next tick. The book
        // GUI blocks inventory interaction, so the swap is invisible.
        ItemStack restore = original;
        player.closeInventory();
        player.getInventory().setItem(slot, readable);
        player.openBook(player.getInventory().getItem(slot));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack current = player.getInventory().getItem(slot);
            if (current != null && current.isSimilar(readable)) {
                player.getInventory().setItem(slot, restore);
            }
        }, 1L);
    }

    private boolean matchesResolvedId(String currentId, String inputId, String resolvedId) {
        if (currentId == null) return false;
        String current = currentId.toLowerCase();
        String input = inputId == null ? "" : inputId.toLowerCase();
        String resolved = resolvedId.toLowerCase();
        return current.equals(input) || current.equals(resolved) || resolved.startsWith(current);
    }

    private record ReadAttempt(String id, int generation) {}

    private void sendValidation(Player player, BookCodec.ValidationResult validation) {
        Map<String, String> p = new HashMap<>();
        p.put("actual", String.valueOf(validation.actual()));
        p.put("max", String.valueOf(validation.max()));
        lang.send(player, validation.key(), p);
    }
}
