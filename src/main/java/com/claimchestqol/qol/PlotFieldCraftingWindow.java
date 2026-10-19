package com.claimchestqol.qol;

import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.builtin.crafting.window.FieldCraftingWindow;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.ItemQuantity;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.window.CraftRecipeAction;
import com.hypixel.hytale.protocol.packets.window.WindowAction;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.player.windows.MaterialContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.MaterialExtraResourcesSection;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.DelegateItemContainer;
import com.hypixel.hytale.server.core.inventory.container.EmptyItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TempAssetIdUtil;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enhanced Pocket/Field Crafting window that also pulls ingredients from any chest
 * in the player's claimed plot while standing inside the plot.
 */
public class PlotFieldCraftingWindow extends FieldCraftingWindow implements MaterialContainerWindow {
    private final PlotManager plotManager;
    private final MaterialExtraResourcesSection extraResourcesSection = new MaterialExtraResourcesSection();

    public PlotFieldCraftingWindow(PlotManager plotManager) {
        super();
        this.plotManager = plotManager;
    }

    @Override
    public boolean onOpen0(Ref<EntityStore> ref, Store<EntityStore> store) {
        extraResourcesSection.setValid(false);
        getExtraResourcesSection();
        return super.onOpen0(ref, store);
    }

    @Override
    public void handleAction(Ref<EntityStore> ref, Store<EntityStore> store, WindowAction action) {
        if (action instanceof CraftRecipeAction craftAction) {
            String recipeId = craftAction.recipeId;
            int quantity = craftAction.quantity;
            if (recipeId == null) {
                return;
            }
            CraftingRecipe recipe = CraftingRecipe.getAssetMap().getAsset(recipeId);
            if (recipe == null) {
                super.handleAction(ref, store, action);
                return;
            }
            CraftingManager craftingManager = store.getComponent(ref, CraftingManager.getComponentType());
            if (craftingManager == null) {
                return;
            }
            CombinedItemContainer playerInv = InventoryComponent.getCombined(
                    store, ref, InventoryComponent.BACKPACK_STORAGE_HOTBAR);
            ItemContainer extraContainer = getExtraResourcesSection().getItemContainer();
            ItemContainer combined = (extraContainer != null && extraContainer != EmptyItemContainer.INSTANCE)
                    ? new CombinedItemContainer(new ItemContainer[]{playerInv, extraContainer})
                    : playerInv;

            if (craftingManager.craftItem(ref, store, recipe, quantity, combined)) {
                SoundUtil.playSoundEvent2d(
                        ref,
                        TempAssetIdUtil.getSoundEventIndex("SFX_Player_Craft_Item_Inventory"),
                        SoundCategory.UI,
                        store
                );
                invalidateExtraResources();
            }
            return;
        }
        super.handleAction(ref, store, action);
    }

    @Override
    public MaterialExtraResourcesSection getExtraResourcesSection() {
        if (!extraResourcesSection.isValid()) {
            populateFromPlotChests();
            extraResourcesSection.setValid(true);
        }
        return extraResourcesSection;
    }

    private void populateFromPlotChests() {
        extraResourcesSection.setItemContainer(EmptyItemContainer.INSTANCE);
        extraResourcesSection.setExtraMaterials(new ItemQuantity[0]);

        PlayerRef playerRef = getPlayerRef();
        if (playerRef == null || plotManager == null) {
            return;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        World world = ((EntityStore) ref.getStore().getExternalData()).getWorld();
        Transform transform = playerRef.getTransform();
        if (world == null || transform == null || transform.getPosition() == null) {
            return;
        }

        int px = (int) Math.floor(transform.getPosition().x);
        int py = (int) Math.floor(transform.getPosition().y);
        int pz = (int) Math.floor(transform.getPosition().z);

        Plot plot = plotManager.getPlotAt(world.getName(), px, py, pz);
        if (plot == null || !plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
            return;
        }

        List<ItemContainer> containers = plotManager.getContainersInPlot(world, plot, null);
        if (containers.isEmpty()) {
            return;
        }

        ItemContainer[] delegates = new ItemContainer[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            DelegateItemContainer<ItemContainer> delegate = new DelegateItemContainer<>(containers.get(i));
            delegate.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
            delegates[i] = delegate;
        }
        CombinedItemContainer combined = new CombinedItemContainer(delegates);

        Map<String, ItemQuantity> quantities = new LinkedHashMap<>();
        short capacity = combined.getCapacity();
        for (short s = 0; s < capacity; s++) {
            ItemStack stack = combined.getItemStack(s);
            if (stack == null || stack.isEmpty() || !stack.isValid()) {
                continue;
            }
            String itemId = stack.getItemId();
            ItemQuantity q = quantities.computeIfAbsent(itemId, k -> new ItemQuantity(k, 0));
            q.quantity += stack.getQuantity();
        }

        extraResourcesSection.setItemContainer(combined);
        extraResourcesSection.setExtraMaterials(quantities.values().toArray(new ItemQuantity[0]));
    }

    @Override
    public void invalidateExtraResources() {
        extraResourcesSection.setValid(false);
        invalidate();
    }

    @Override
    public boolean isValid() {
        return extraResourcesSection.isValid();
    }
}

