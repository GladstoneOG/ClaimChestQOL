package com.claimchestqol.util;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.joml.Vector3i;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helper for creating, detecting, and handling interactions with the Plot Claim Tool.
 */
public final class ClaimToolUtil {
    public static final String CLAIM_TOOL_META_KEY = "PlotClaimTool";

    private static final String[] PREFERRED_TOOL_ITEMS = {
            "Weapon_Staff_Wood",
            "Tool_Hammer_Wood",
            "Ingredient_Stick",
            "Tool_Pickaxe_Wood",
            "Weapon_Mace_Wood",
            "Weapon_Club_Wood",
            "Weapon_Sword_Wood"
    };

    private ClaimToolUtil() {
    }

    public static boolean isClaimToolItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        BsonDocument meta = stack.getMetadata();
        if (meta == null) {
            return false;
        }
        BsonValue val = meta.get(CLAIM_TOOL_META_KEY);
        return val != null && val.isBoolean() && val.asBoolean().getValue();
    }

    public static boolean isUsingClaimTool(UUID playerUuid, ItemStack heldItem, PlotManager plotManager) {
        if (isClaimToolItem(heldItem)) {
            return true;
        }
        if (playerUuid != null && plotManager != null) {
            PlotManager.CornerSelection sel = plotManager.getSelection(playerUuid);
            return sel.toolModeEnabled;
        }
        return false;
    }

    public static ItemStack createClaimToolStack(ItemStack fallbackHeldItem) {
        String itemId = findBestToolItemId(fallbackHeldItem);
        if (itemId == null) {
            return null;
        }
        ItemStack base = new ItemStack(itemId, 1);
        return base.withMetadata(CLAIM_TOOL_META_KEY, BsonBoolean.TRUE);
    }

    private static String findBestToolItemId(ItemStack fallbackHeldItem) {
        try {
            Map<String, Item> map = Item.getAssetMap().getAssetMap();
            if (map != null && !map.isEmpty()) {
                for (String preferred : PREFERRED_TOOL_ITEMS) {
                    if (map.containsKey(preferred)) {
                        return preferred;
                    }
                }
                for (String key : map.keySet()) {
                    String lower = key.toLowerCase();
                    if (lower.contains("stick") || lower.contains("staff") || lower.contains("wand") || lower.contains("hammer")) {
                        return key;
                    }
                }
                for (Map.Entry<String, Item> entry : map.entrySet()) {
                    Item item = entry.getValue();
                    if (item != null && !item.hasBlockType()) {
                        return entry.getKey();
                    }
                }
                return map.keySet().iterator().next();
            }
        } catch (Exception ignored) {
        }
        if (fallbackHeldItem != null && !fallbackHeldItem.isEmpty()) {
            return fallbackHeldItem.getItemId();
        }
        return null;
    }

    public static boolean giveClaimTool(Ref<EntityStore> ref,
                                        ComponentAccessor<EntityStore> accessor,
                                        PlayerRef playerRef,
                                        PlotManager plotManager) {
        ItemStack held = InventoryComponent.getItemInHand(accessor, ref);
        if (isClaimToolItem(held)) {
            sendMsg(playerRef, "You are already holding a Plot Claim Tool!", "#55FF55");
            sendToolInstructions(playerRef, plotManager);
            return true;
        }

        ItemStack toolStack = createClaimToolStack(held);
        if (toolStack != null) {
            var tx = Player.giveItem(toolStack, ref, accessor);
            if (tx != null && tx.succeeded()) {
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.toolModeEnabled = false;
                sendMsg(playerRef, "Given Plot Claim Tool (" + toolStack.getItemId() + ")!", "#55FF55");
                sendToolInstructions(playerRef, plotManager);
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                return true;
            }
        }

        // Fallback if inventory is full: toggle hand Claim Tool Mode
        PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
        sel.toolModeEnabled = !sel.toolModeEnabled;
        if (sel.toolModeEnabled) {
            sendMsg(playerRef, "Claim Tool Mode ENABLED (inventory was full). Any item/hand click will now select corners!", "#55FF55");
            sendToolInstructions(playerRef, plotManager);
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
        } else {
            sendMsg(playerRef, "Claim Tool Mode DISABLED.", "#FFAA00");
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
        }
        return true;
    }

    public static void sendToolInstructions(PlayerRef playerRef, PlotManager plotManager) {
        String currentMode = "Bedrock-to-Ceiling";
        if (playerRef != null && plotManager != null) {
            currentMode = plotManager.getSelection(playerRef.getUuid()).heightMode.getDisplayName();
        }
        sendMsg(playerRef, " - Current Height Mode: " + currentMode, "#55FFFF");
        sendMsg(playerRef, " - Holding Claim Stick shows your plots in GREEN and others' plots in RED/YELLOW", "#55FF55");
        sendMsg(playerRef, " - Left-Click (Block or Air): Select Corner 1 (starts glowing green outline)", "#FFFF55");
        sendMsg(playerRef, " - Right-Click / Use (Block or Air): Select Corner 2 & Claim Plot", "#FFFF55");
        sendMsg(playerRef, " - Sneak + Left-Click (or /claim bedrock | /claim height): Switch Height Mode", "#55FF55");
        sendMsg(playerRef, " - Sneak + Right-Click (or Right-Click in your plot): Undo / Unclaim Plot", "#FFAA00");
    }

    /**
     * Handles a click when the player is holding the Claim Tool.
     *
     * @return true if the event was handled and should be cancelled
     */
    public static boolean handleToolClick(Ref<EntityStore> ref,
                                          ComponentAccessor<EntityStore> accessor,
                                          PlayerRef playerRef,
                                          World world,
                                          int x, int y, int z,
                                          boolean isLeftClick,
                                          PlotManager plotManager) {
        if (playerRef == null || world == null || plotManager == null) {
            return false;
        }
        UUID playerUuid = playerRef.getUuid();
        String playerName = playerRef.getUsername();
        String worldName = world.getName();

        PlotManager.CornerSelection sel = plotManager.getSelection(playerUuid);
        long now = System.currentTimeMillis();
        if (now - sel.lastActionTimestampMs < 220L) {
            // Debounce duplicate events from the same click while still cancelling block break/place
            return true;
        }
        sel.lastActionTimestampMs = now;

        boolean crouching = isCrouching(ref, accessor);

        // 1a. Crouching (Sneak) + Left-Click with Claim Tool => Toggle Height Mode (Bedrock-to-Ceiling vs Specific-Height)
        if (crouching && isLeftClick) {
            sel.heightMode = sel.heightMode.toggle();
            sendMsg(playerRef,
                    "Claim Mode switched to: " + sel.heightMode.getDisplayName() + " — " + sel.heightMode.getDescription(),
                    "#55FF55");
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            return true;
        }

        // 1b. Crouching (Sneak) + Right-Click with Claim Tool => Cancel pending selection or Undo claim
        if (crouching) {
            if (sel.pos1 != null || sel.pos2 != null) {
                sel.clearCorners();
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                sendMsg(playerRef, "Cleared pending plot corner selection.", "#FFAA00");
                return true;
            }
            Plot plotAtClick = plotManager.getPlotAt(worldName, x, y, z);
            if (plotAtClick != null && plotAtClick.isOwner(playerUuid)) {
                plotManager.removePlot(plotAtClick);
                sel.clearCorners();
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                sendMsg(playerRef, "Plot claim undone! Unclaimed your plot at " + plotAtClick.formatBounds() + ".", "#FFAA00");
                return true;
            }
            List<Plot> ownedPlots = plotManager.getPlotsByOwner(playerUuid);
            if (!ownedPlots.isEmpty()) {
                Plot lastPlot = ownedPlots.get(ownedPlots.size() - 1);
                plotManager.removePlot(lastPlot);
                sel.clearCorners();
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                sendMsg(playerRef, "Plot claim undone! Unclaimed your plot at " + lastPlot.formatBounds() + ".", "#FFAA00");
                return true;
            }
            sendMsg(playerRef, "You do not have an active plot claim at this location to undo.", "#FF5555");
            return true;
        }

        // 2. Left-Click => Set Corner 1 & activate live glowing green outline
        if (isLeftClick) {
            sel.worldName = worldName;
            sel.pos1 = new Vector3i(x, y, z);
            sel.pos2 = null;
            sel.pos1TimestampMs = now;
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            sendMsg(playerRef,
                    String.format("Corner 1 set at (%d, %d, %d) [Mode: %s]. Walk to the other corner and Right-Click to claim!",
                            x, y, z, sel.heightMode.getDisplayName()),
                    "#55FFFF");
            sendMsg(playerRef,
                    "Tip: Sneak + Left-Click to switch between Bedrock-to-Ceiling and Specific-Height (3D /fill) mode.",
                    "#AAAAAA");
            return true;
        }

        // 3. Right-Click or Use =>
        // If no Corner 1 is pending AND clicking inside an already-owned plot, undo the claim!
        if (sel.pos1 == null || !worldName.equalsIgnoreCase(sel.worldName)) {
            Plot existingPlot = plotManager.getPlotAt(worldName, x, y, z);
            if (existingPlot != null && existingPlot.isOwner(playerUuid)) {
                plotManager.removePlot(existingPlot);
                sel.clearCorners();
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                sendMsg(playerRef,
                        "Plot claim undone! Unclaimed your plot at " + existingPlot.formatBounds() + ".",
                        "#FFAA00");
                return true;
            }
            // Otherwise, start selection with Corner 1 if they right-clicked first
            sel.worldName = worldName;
            sel.pos1 = new Vector3i(x, y, z);
            sel.pos2 = null;
            sel.pos1TimestampMs = now;
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            sendMsg(playerRef,
                    String.format("Corner 1 set at (%d, %d, %d) [Mode: %s]. Walk to the opposite corner and Right-Click to finish claiming!",
                            x, y, z, sel.heightMode.getDisplayName()),
                    "#55FFFF");
            return true;
        }

        // Corner 1 is already set in this world -> Set Corner 2 and create the plot claim!
        sel.pos2 = new Vector3i(x, y, z);
        PlotManager.ClaimOutcome outcome = plotManager.claimPlot(
                playerUuid, playerName, worldName, sel.pos1, sel.pos2, sel.heightMode);
        if (outcome.success()) {
            sel.clearCorners();
            SelectionVisualizer.showClaimedPlot(playerRef, outcome.plot(), 5.0f);
            int chestCount = plotManager.getContainersInPlot(world, outcome.plot(), null).size();
            sendMsg(playerRef,
                    String.format("Corner 2 set at (%d, %d, %d).", x, y, z),
                    "#55FFFF");
            sendMsg(playerRef,
                    outcome.message() + " | Connected Chests: " + chestCount,
                    "#55FF55");
            sendMsg(playerRef,
                    "Tip: Right-click inside your plot with the Claim Tool (or Sneak+Right-Click / /unclaim) anytime to undo.",
                    "#AAAAAA");
        } else {
            sendMsg(playerRef, outcome.message(), "#FF5555");
        }
        return true;
    }

    private static boolean isCrouching(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid() || accessor == null) {
            return false;
        }
        try {
            MovementStatesComponent mov = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
            if (mov != null && mov.getMovementStates() != null) {
                return mov.getMovementStates().crouching || mov.getMovementStates().forcedCrouching;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void sendMsg(PlayerRef playerRef, String text, String hexColor) {
        if (playerRef == null) {
            return;
        }
        Message prefix = Message.raw("[ClaimChestQOL] ").color("#FFAA00").bold(true);
        Message body = Message.raw(text).color(hexColor != null ? hexColor : "#FFFFFF").bold(false);
        playerRef.sendMessage(Message.join(prefix, body));
    }
}

