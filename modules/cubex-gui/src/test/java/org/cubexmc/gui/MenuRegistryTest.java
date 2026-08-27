package org.cubexmc.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class MenuRegistryTest {

    private Menu newMenu(Inventory inventory) {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(), anyInt(), anyString())).thenReturn(inventory);
            return new Menu("Hall", 3);
        }
    }

    @Test
    void shouldRouteClickToTheButtonOwningThatSlot() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        Menu menu = newMenu(inventory);

        AtomicInteger clicked = new AtomicInteger();
        menu.button(4, mock(ItemStack.class), event -> {
            clicked.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        });
        menu.decoration(5, mock(ItemStack.class));

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);
        assertSame(menu, registry.openMenu(playerId));

        registry.onClick(clickEvent(player, inventory, 4));
        assertEquals(1, clicked.get());

        // A decoration slot is still cancelled but runs no action.
        registry.onClick(clickEvent(player, inventory, 5));
        assertEquals(1, clicked.get());
    }

    @Test
    void shouldCancelClicksInsideTheMenuAndIgnoreClicksBelowIt() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        Menu menu = newMenu(inventory);

        AtomicInteger clicked = new AtomicInteger();
        menu.button(0, mock(ItemStack.class), event -> {
            clicked.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        });

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);

        InventoryClickEvent inside = clickEvent(player, inventory, 0);
        registry.onClick(inside);
        assertTrue(inside.isCancelled());
        assertEquals(1, clicked.get());

        // rawSlot beyond the top inventory is the player's own inventory: cancelled, but not routed.
        InventoryClickEvent below = clickEvent(player, inventory, 30);
        registry.onClick(below);
        assertTrue(below.isCancelled());
        assertEquals(1, clicked.get());
    }

    @Test
    void shouldNotTouchEventsForAnInventoryThatIsNotTheOpenMenu() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        Menu menu = newMenu(inventory);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);

        Inventory other = mock(Inventory.class);
        when(other.getSize()).thenReturn(27);
        InventoryClickEvent event = clickEvent(player, other, 0);
        registry.onClick(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void shouldRefuseDragsOverMenuSlots() {
        Inventory inventory = mock(Inventory.class);
        Menu menu = newMenu(inventory);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);

        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getView()).thenReturn(view);

        registry.onDrag(drag);
        org.mockito.Mockito.verify(drag).setCancelled(true);
    }

    @Test
    void shouldRunOnCloseOnlyForAGenuineCloseOfThatMenu() {
        Inventory inventory = mock(Inventory.class);
        Menu menu = newMenu(inventory);
        AtomicInteger closed = new AtomicInteger();
        menu.setOnClose(() -> {
            closed.incrementAndGet();
            return kotlin.Unit.INSTANCE;
        });

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);

        // A synchronous reopen swapped in a different inventory: the old close must not fire.
        InventoryCloseEvent stale = mock(InventoryCloseEvent.class);
        when(stale.getPlayer()).thenReturn(player);
        when(stale.getInventory()).thenReturn(mock(Inventory.class));
        registry.onClose(stale);
        assertEquals(0, closed.get());
        assertSame(menu, registry.openMenu(playerId));

        InventoryCloseEvent real = mock(InventoryCloseEvent.class);
        when(real.getPlayer()).thenReturn(player);
        when(real.getInventory()).thenReturn(inventory);
        registry.onClose(real);
        assertEquals(1, closed.get());
        assertNull(registry.openMenu(playerId));
    }

    @Test
    void shouldDropTrackingWhenThePlayerQuits() {
        Inventory inventory = mock(Inventory.class);
        Menu menu = newMenu(inventory);

        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        MenuRegistry registry = new MenuRegistry();
        registry.open(player, menu);

        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        registry.onQuit(quit);

        assertNull(registry.openMenu(playerId));
    }

    @Test
    void closeAllOnlyClosesTheInventoryOwnedByTheRegistry() {
        Inventory managed = mock(Inventory.class);
        Inventory other = mock(Inventory.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(other);
        MenuRegistry registry = new MenuRegistry();
        registry.open(player, new Menu(managed));
        try (org.mockito.MockedStatic<Bukkit> bukkit = org.mockito.Mockito.mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            registry.closeAll();
            org.mockito.Mockito.verify(player, org.mockito.Mockito.never()).closeInventory();
            assertNull(registry.openMenu(id));

            registry.open(player, new Menu(managed));
            when(view.getTopInventory()).thenReturn(managed);
            registry.closeAll();
            org.mockito.Mockito.verify(player).closeInventory();
        }
    }

    private InventoryClickEvent clickEvent(Player player, Inventory topInventory, int rawSlot) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(topInventory);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getRawSlot()).thenReturn(rawSlot);

        boolean[] cancelled = { false };
        org.mockito.Mockito.doAnswer(invocation -> {
            cancelled[0] = invocation.getArgument(0);
            return null;
        }).when(event).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());
        when(event.isCancelled()).thenAnswer(invocation -> cancelled[0]);
        return event;
    }
}
