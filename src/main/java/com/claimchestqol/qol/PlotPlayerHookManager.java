package com.claimchestqol.qol;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.InventoryActionType;
import com.hypixel.hytale.protocol.packets.inventory.InventoryAction;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.BlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ItemContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.InventoryUtils;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.InventoryPacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;
import com.claimchestqol.util.ClaimToolUtil;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Installs per-player QoL hooks:
 * 1. PlotWindowManager on Player (enables plot-wide crafting on all BenchWindows).
 * 2. InventoryAction packet interceptor on GenericPacketHandler (hijacks single-chest 'R' Quick Stack
 *    when inside the player's plot to quick-stack into ALL chests across the plot).
 */
public final class PlotPlayerHookManager {
    private static Field PLAYER_WINDOW_MANAGER_FIELD;
    private static final Set<PacketHandler> HOOKED_PACKET_HANDLERS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    static {
        try {
            PLAYER_WINDOW_MANAGER_FIELD = Player.class.getDeclaredField("windowManager");
            PLAYER_WINDOW_MANAGER_FIELD.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to reflect Player.windowManager: " + e.getMessage());
        }
    }

    private PlotPlayerHookManager() {
    }

    public static void ensureHooks(Player player, PlayerRef playerRef, PlotManager plotManager) {
        if (player != null && playerRef != null && plotManager != null) {
            installWindowManager(player, playerRef, plotManager);
        }
        if (playerRef != null && plotManager != null) {
            installQuickStackPacketHook(playerRef, plotManager);
        }
    }

    private static void installWindowManager(Player player, PlayerRef playerRef, PlotManager plotManager) {
        if (player.getWindowManager() instanceof PlotWindowManager) {
            return;
        }
        if (PLAYER_WINDOW_MANAGER_FIELD == null) {
            return;
        }
        try {
            PlotWindowManager pwm = new PlotWindowManager(plotManager);
            pwm.init(playerRef);
            PLAYER_WINDOW_MANAGER_FIELD.set(player, pwm);
            if (player.getPageManager() != null) {
                player.getPageManager().init(playerRef, pwm);
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to install PlotWindowManager: " + e.getMessage());
        }
    }

    private static void installQuickStackPacketHook(PlayerRef playerRef, PlotManager plotManager) {
        PacketHandler packetHandler = playerRef.getPacketHandler();
        if (!(packetHandler instanceof GenericPacketHandler genericHandler)) {
            return;
        }
        if (HOOKED_PACKET_HANDLERS.contains(packetHandler)) {
            return;
        }
        InventoryPacketHandler invHandler = genericHandler.getSubPacketHandler(InventoryPacketHandler.class);
        if (invHandler == null) {
            return;
        }
        HOOKED_PACKET_HANDLERS.add(packetHandler);

        genericHandler.registerHandler(InventoryAction.PACKET_ID, packet -> {
            if (!(packet instanceof InventoryAction action)) {
                return;
            }
            if (action.inventoryActionType == InventoryActionType.QuickStack && action.inventorySectionId >= 0) {
                if (tryHandlePlotQuickStack(playerRef, action, plotManager)) {
                    return;
                }
            }
            invHandler.handle(action);
        });
    }

    private static boolean tryHandlePlotQuickStack(PlayerRef playerRef, InventoryAction action, PlotManager plotManager) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<EntityStore> store = ref.getStore();
        World world = ((EntityStore) store.getExternalData()).getWorld();
        if (world == null) {
            return false;
        }

        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                return;
            }
            Window window = player.getWindowManager().getWindow(action.inventorySectionId);
            if (!(window instanceof ItemContainerWindow containerWindow)) {
                return;
            }

            Plot plot = null;
            if (window instanceof BlockWindow blockWindow) {
                plot = plotManager.getPlotAt(world.getName(), blockWindow.getX(), blockWindow.getY(), blockWindow.getZ());
            }
            if (plot == null) {
                Transform transform = playerRef.getTransform();
                if (transform != null && transform.getPosition() != null) {
                    int px = (int) Math.floor(transform.getPosition().x);
                    int py = (int) Math.floor(transform.getPosition().y);
                    int pz = (int) Math.floor(transform.getPosition().z);
                    plot = plotManager.getPlotAt(world.getName(), px, py, pz);
                }
            }

            // If inside an authorized plot, hijack Quick Stack (R) to quick-stack into ALL chests in the plot!
            if (plot != null && plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                ItemContainer currentChest = containerWindow.getItemContainer();
                List<ItemContainer> plotChests = plotManager.getContainersInPlot(world, plot, currentChest);
                if (!plotChests.isEmpty()) {
                    CombinedItemContainer playerInventory = InventoryComponent.getCombined(
                            store, ref, InventoryComponent.HOTBAR_FIRST);
                    if (playerInventory != null) {
                        playerInventory.quickStackTo(plotChests.toArray(new ItemContainer[0]));
                        player.getWindowManager().markWindowChanged(action.inventorySectionId);
                        ClaimToolUtil.sendMsg(playerRef,
                                "Quick-stacked matching items across " + plotChests.size() + " chest(s) in your plot!",
                                "#55FF55");
                        return;
                    }
                }
            }

            // Default fallback for chests outside your plot
            InventoryUtils.quickStack(ref, action.inventorySectionId, store);
        });

        return true;
    }
}

