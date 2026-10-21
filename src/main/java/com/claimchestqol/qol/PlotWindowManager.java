package com.claimchestqol.qol;

import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ItemContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Custom WindowManager that automatically injects PlotMaterialExtraResourcesSection
 * into any BenchWindow opened by a player, lifting vanilla's 10-chest bench limit
 * and synchronizing full plot materials to the client.
 */
public class PlotWindowManager extends WindowManager {
    private static Field EXTRA_RESOURCES_FIELD;
    private static Method CONSUME_NEED_REBUILD_METHOD;

    static {
        try {
            EXTRA_RESOURCES_FIELD = BenchWindow.class.getDeclaredField("extraResourcesSection");
            EXTRA_RESOURCES_FIELD.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to access BenchWindow.extraResourcesSection: " + e.getMessage());
        }
        try {
            CONSUME_NEED_REBUILD_METHOD = Window.class.getDeclaredMethod("consumeNeedRebuild");
            CONSUME_NEED_REBUILD_METHOD.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to access Window.consumeNeedRebuild: " + e.getMessage());
        }
    }

    private final PlotManager plotManager;
    private PlayerRef localPlayerRef;

    public PlotWindowManager(PlotManager plotManager) {
        super();
        this.plotManager = plotManager;
    }

    @Override
    public void init(PlayerRef playerRef) {
        this.localPlayerRef = playerRef;
        super.init(playerRef);
    }

    public static PlotMaterialExtraResourcesSection getPlotSection(BenchWindow benchWindow) {
        if (EXTRA_RESOURCES_FIELD != null) {
            try {
                Object obj = EXTRA_RESOURCES_FIELD.get(benchWindow);
                if (obj instanceof PlotMaterialExtraResourcesSection plotSection) {
                    return plotSection;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void enhanceWindow(Window window) {
        if (window instanceof BenchWindow benchWindow && EXTRA_RESOURCES_FIELD != null) {
            try {
                Object current = EXTRA_RESOURCES_FIELD.get(benchWindow);
                if (!(current instanceof PlotMaterialExtraResourcesSection)) {
                    EXTRA_RESOURCES_FIELD.set(benchWindow, new PlotMaterialExtraResourcesSection(benchWindow, plotManager));
                }
            } catch (Exception e) {
                System.err.println("[ClaimChestQOL] Failed to inject PlotMaterialExtraResourcesSection: " + e.getMessage());
            }
        }
    }

    @Override
    public UpdateWindow clientOpenWindow(Ref<EntityStore> ref, Window window, Store<EntityStore> store) {
        enhanceWindow(window);
        UpdateWindow updateWindow = super.clientOpenWindow(ref, window, store);
        if (updateWindow != null && window instanceof BenchWindow benchWindow) {
            PlotMaterialExtraResourcesSection extra = getPlotSection(benchWindow);
            if (extra != null) {
                extra.refreshPlotResources();
                return new UpdateWindow(
                        updateWindow.id,
                        benchWindow.getData().toString(),
                        updateWindow.inventory,
                        extra.toPacket()
                );
            }
        }
        return updateWindow;
    }

    @Override
    public OpenWindow openWindow(Ref<EntityStore> ref, Window window, Store<EntityStore> store) {
        enhanceWindow(window);
        OpenWindow openWindow = super.openWindow(ref, window, store);
        if (openWindow != null && window instanceof BenchWindow benchWindow) {
            PlotMaterialExtraResourcesSection extra = getPlotSection(benchWindow);
            if (extra != null) {
                extra.refreshPlotResources();
                return new OpenWindow(
                        openWindow.id,
                        openWindow.windowType,
                        benchWindow.getData().toString(),
                        openWindow.inventory,
                        extra.toPacket()
                );
            }
        }
        return openWindow;
    }

    @Override
    public List<OpenWindow> openWindows(Ref<EntityStore> ref, Store<EntityStore> store, Window... windows) {
        if (windows != null) {
            for (Window w : windows) {
                enhanceWindow(w);
            }
        }
        return super.openWindows(ref, store, windows);
    }

    @Override
    public void setWindow(int id, Window window) {
        enhanceWindow(window);
        super.setWindow(id, window);
    }

    @Override
    public void updateWindow(Window window) {
        enhanceWindow(window);
        if (window instanceof BenchWindow benchWindow) {
            PlotMaterialExtraResourcesSection extra = getPlotSection(benchWindow);
            if (extra != null && !benchWindow.isValid()) {
                extra.refreshPlotResources();
                InventorySection inventorySection = null;
                if (window instanceof ItemContainerWindow icw) {
                    inventorySection = icw.getItemContainer().toPacket();
                }
                PlayerRef pRef = benchWindow.getPlayerRef();
                if (pRef == null) {
                    pRef = this.localPlayerRef;
                }
                if (pRef != null && pRef.getPacketHandler() != null) {
                    UpdateWindow updateWindow = new UpdateWindow(
                            benchWindow.getId(),
                            benchWindow.getData().toString(),
                            inventorySection,
                            extra.toPacket()
                    );
                    pRef.getPacketHandler().writeNoCache(updateWindow);
                    if (CONSUME_NEED_REBUILD_METHOD != null) {
                        try {
                            CONSUME_NEED_REBUILD_METHOD.invoke(benchWindow);
                        } catch (Exception ignored) {
                        }
                    }
                    return;
                }
            }
        }
        super.updateWindow(window);
    }
}
