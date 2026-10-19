package com.claimchestqol.qol;

import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Custom WindowManager that automatically injects PlotMaterialExtraResourcesSection
 * into any BenchWindow opened by a player.
 */
public class PlotWindowManager extends WindowManager {
    private static Field EXTRA_RESOURCES_FIELD;

    static {
        try {
            EXTRA_RESOURCES_FIELD = BenchWindow.class.getDeclaredField("extraResourcesSection");
            EXTRA_RESOURCES_FIELD.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to access BenchWindow.extraResourcesSection: " + e.getMessage());
        }
    }

    private final PlotManager plotManager;

    public PlotWindowManager(PlotManager plotManager) {
        super();
        this.plotManager = plotManager;
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
        return super.clientOpenWindow(ref, window, store);
    }

    @Override
    public OpenWindow openWindow(Ref<EntityStore> ref, Window window, Store<EntityStore> store) {
        enhanceWindow(window);
        return super.openWindow(ref, window, store);
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
        super.updateWindow(window);
    }
}

