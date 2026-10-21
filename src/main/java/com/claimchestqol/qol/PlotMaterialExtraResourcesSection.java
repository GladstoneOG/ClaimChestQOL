package com.claimchestqol.qol;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.window.BenchWindow;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ExtraResources;
import com.hypixel.hytale.protocol.ItemQuantity;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.entity.entities.player.windows.MaterialExtraResourcesSection;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.DelegateItemContainer;
import com.hypixel.hytale.server.core.inventory.container.EmptyItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom MaterialExtraResourcesSection injected into BenchWindow instances.
 * Connects ALL chests in the player's claimed plot regardless of distance or vanilla limits,
 * and prevents outsiders from pulling materials from protected plots.
 */
public class PlotMaterialExtraResourcesSection extends MaterialExtraResourcesSection {
    private static Field BENCH_FIELD;
    private static Method GET_TIER_LEVEL_METHOD;

    static {
        try {
            BENCH_FIELD = BenchWindow.class.getDeclaredField("bench");
            BENCH_FIELD.setAccessible(true);
            GET_TIER_LEVEL_METHOD = BenchWindow.class.getDeclaredMethod("getBenchTierLevel");
            GET_TIER_LEVEL_METHOD.setAccessible(true);
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to reflect BenchWindow fields: " + e.getMessage());
        }
    }

    private final BenchWindow benchWindow;
    private final PlotManager plotManager;
    private volatile boolean isPopulating = false;

    public PlotMaterialExtraResourcesSection(BenchWindow benchWindow, PlotManager plotManager) {
        super();
        this.benchWindow = benchWindow;
        this.plotManager = plotManager;
    }

    @Override
    public void setValid(boolean valid) {
        if (!valid) {
            super.setValid(false);
            return;
        }
        refreshPlotResources();
    }

    @Override
    public void setItemContainer(ItemContainer container) {
        if (isPopulating) {
            super.setItemContainer(container);
        }
        // Blocks vanilla CraftingManager.feedExtraResourcesSection from overwriting with its 10-chest list
    }

    @Override
    public void setExtraMaterials(ItemQuantity[] materials) {
        if (isPopulating) {
            super.setExtraMaterials(materials);
        }
        // Blocks vanilla CraftingManager.feedExtraResourcesSection from overwriting with its 10-chest list
    }

    @Override
    public ItemContainer getItemContainer() {
        if (!super.isValid() || super.getItemContainer() == null) {
            refreshPlotResources();
        }
        return super.getItemContainer();
    }

    @Override
    public ExtraResources toPacket() {
        if (!super.isValid()) {
            refreshPlotResources();
        }
        return super.toPacket();
    }

    public synchronized void refreshPlotResources() {
        if (isPopulating) {
            return;
        }
        isPopulating = true;
        try {
            PlayerRef playerRef = benchWindow.getPlayerRef();
            if (playerRef == null) {
                return;
            }
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            EntityStore entityStore = (EntityStore) ref.getStore().getExternalData();
            World world = entityStore.getWorld();
            if (world == null) {
                return;
            }

            int bx = benchWindow.getX();
            int by = benchWindow.getY();
            int bz = benchWindow.getZ();

            Plot plot = plotManager.getPlotAt(world.getName(), bx, by, bz);
            if (plot == null) {
                Transform transform = playerRef.getTransform();
                if (transform != null && transform.getPosition() != null) {
                    int px = (int) Math.floor(transform.getPosition().x);
                    int py = (int) Math.floor(transform.getPosition().y);
                    int pz = (int) Math.floor(transform.getPosition().z);
                    plot = plotManager.getPlotAt(world.getName(), px, py, pz);
                }
            }

            List<ItemContainer> containers;
            boolean inAuthorizedPlot = (plot != null && plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername()));
            if (inAuthorizedPlot) {
                containers = plotManager.getContainersInPlot(world, plot, null);
            } else {
                containers = plotManager.getAuthorizedNearbyContainers(
                        world, bx, by, bz, playerRef.getUuid(), playerRef.getUsername());
            }

            ItemContainer combinedContainer;
            if (containers.isEmpty()) {
                combinedContainer = EmptyItemContainer.INSTANCE;
            } else {
                ItemContainer[] delegates = new ItemContainer[containers.size()];
                for (int i = 0; i < containers.size(); i++) {
                    DelegateItemContainer<ItemContainer> delegate = new DelegateItemContainer<>(containers.get(i));
                    delegate.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
                    delegates[i] = delegate;
                }
                combinedContainer = new CombinedItemContainer(delegates);
            }

            Bench bench = BENCH_FIELD != null ? (Bench) BENCH_FIELD.get(benchWindow) : null;
            int tierLevel = GET_TIER_LEVEL_METHOD != null ? (int) GET_TIER_LEVEL_METHOD.invoke(benchWindow) : 1;

            Map<String, ItemQuantity> quantities = new LinkedHashMap<>();
            for (ItemContainer container : containers) {
                if (container == null) continue;
                short cap = container.getCapacity();
                for (short s = 0; s < cap; s++) {
                    ItemStack stack = container.getItemStack(s);
                    if (stack == null || stack.isEmpty() || !stack.isValid()) {
                        continue;
                    }
                    boolean validForBench = true;
                    if (bench != null) {
                        validForBench = CraftingPlugin.isValidUpgradeMaterialForBench(bench, tierLevel, stack)
                                || CraftingPlugin.isValidCraftingMaterialForBench(bench, stack);
                    }
                    if (validForBench) {
                        String itemId = stack.getItemId();
                        ItemQuantity q = quantities.computeIfAbsent(itemId, k -> new ItemQuantity(k, 0));
                        q.quantity += stack.getQuantity();
                    }
                }
            }

            super.setItemContainer(combinedContainer);
            super.setExtraMaterials(quantities.values().toArray(new ItemQuantity[0]));
            super.setValid(true);

            if (benchWindow.getData() != null) {
                benchWindow.getData().addProperty("nearbyChestCount", containers.size());
                int maxLimit = Math.max(500, Math.max(containers.size(), 100));
                benchWindow.getData().addProperty("maxChestCount", maxLimit);
                if (inAuthorizedPlot && plot != null) {
                    benchWindow.getData().addProperty("chestHorizontalRadius", (int) Math.max(plot.getWidthX(), plot.getLengthZ()));
                    benchWindow.getData().addProperty("chestVerticalRadius", (int) plot.getHeightY());
                }
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Error populating plot crafting resources: " + e.getMessage());
        } finally {
            isPopulating = false;
        }
    }
}
