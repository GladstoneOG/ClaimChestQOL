package com.claimchestqol.util;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.ClaimHeightMode;
import com.claimchestqol.model.Plot;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Renders 3D boundary outlines for players:
 * 1. While holding the Claim Stick (or in Claim Tool Mode):
 *    - Player's own claimed plots are shown in Glowing GREEN.
 *    - Other players' claimed plots are shown in Glowing RED / YELLOW.
 * 2. After placing Corner 1:
 *    - Renders a live Glowing GREEN 3D selection outline that follows the player as they walk to Corner 2,
 *      alongside all nearby claimed plot boundaries.
 * 3. When a claim is completed, inspected (/claim show | /claim info), or when a player tries to break/interact
 *    without permission:
 *    - Flashes the plot boundary in Green (if owner) or Red/Yellow (if owned by another player).
 */
public final class SelectionVisualizer {
    // Own Plot & Active Selection Colors (Green)
    private static final Vector3fc COLOR_GLOW_GREEN = new Vector3f(0.10f, 1.00f, 0.25f);
    private static final Vector3fc COLOR_FILL_GREEN = new Vector3f(0.05f, 0.92f, 0.20f);
    private static final Vector3fc COLOR_CORNER_MARKER = new Vector3f(0.25f, 1.00f, 0.55f);

    // Other Players' Plot Colors (Red / Yellow)
    private static final Vector3fc COLOR_GLOW_RED = new Vector3f(1.00f, 0.16f, 0.14f);
    private static final Vector3fc COLOR_FILL_RED = new Vector3f(0.95f, 0.14f, 0.12f);
    private static final Vector3fc COLOR_GLOW_YELLOW = new Vector3f(1.00f, 0.88f, 0.10f);
    private static final Vector3fc COLOR_FILL_YELLOW = new Vector3f(0.95f, 0.78f, 0.10f);
    private static final Vector3fc COLOR_GLOW_ORANGE_RED = new Vector3f(1.00f, 0.42f, 0.12f);

    private static final double BEAM_THICKNESS = 0.075;
    private static final int NEARBY_PLOT_RADIUS = 160;
    private static final long SELECTION_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes max idle selection

    private static final Map<UUID, RenderState> PLAYER_STATES = new ConcurrentHashMap<>();
    private static ScheduledExecutorService executor;
    private static volatile PlotManager plotManagerRef;

    private static class RenderState {
        boolean hasSelection;
        int c1x, c1y, c1z;
        int c2x, c2y, c2z;
        int anchorX, anchorY, anchorZ;
        ClaimHeightMode mode;
        long plotsVersion;
        long lastSentMs;
        long suppressClearUntilMs;
        boolean active;
    }

    private SelectionVisualizer() {
    }

    public static synchronized void start(PlotManager plotManager) {
        plotManagerRef = plotManager;
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClaimChest-SelectionVisualizer");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(SelectionVisualizer::tickAllPlayers, 120L, 120L, TimeUnit.MILLISECONDS);
    }

    public static synchronized void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        PLAYER_STATES.clear();
    }

    /**
     * Immediately updates and renders the active selection and nearby plot outlines for a single player.
     */
    public static void forceRefresh(PlayerRef playerRef, PlotManager plotManager) {
        if (playerRef == null || plotManager == null) {
            return;
        }
        UUID uuid = playerRef.getUuid();
        PlotManager.CornerSelection sel = plotManager.getSelection(uuid);
        boolean hasSelection = (sel != null && sel.pos1 != null);
        boolean holdingTool = ClaimToolUtil.isUsingClaimTool(uuid, getHeldItemConcurrent(playerRef), plotManager);

        if (!hasSelection && !holdingTool) {
            clearForPlayer(playerRef);
            return;
        }

        int fallbackY = hasSelection ? sel.pos1.y : 64;
        Vector3i currentPos = getLivePlayerBlockPos(playerRef, fallbackY);
        String worldName = resolvePlayerWorldName(playerRef, sel);
        long now = System.currentTimeMillis();

        RenderState state = PLAYER_STATES.computeIfAbsent(uuid, k -> new RenderState());
        state.hasSelection = hasSelection;
        if (hasSelection) {
            state.c1x = sel.pos1.x;
            state.c1y = sel.pos1.y;
            state.c1z = sel.pos1.z;
            state.mode = sel.heightMode;
        }
        state.c2x = currentPos.x;
        state.c2y = currentPos.y;
        state.c2z = currentPos.z;
        state.anchorX = currentPos.x;
        state.anchorY = currentPos.y;
        state.anchorZ = currentPos.z;
        state.plotsVersion = plotManager.getPlotsVersion();
        state.lastSentMs = now;
        state.active = true;

        renderPlayerFrame(playerRef, plotManager, worldName, sel, currentPos, 1.4f, false);
    }

    /**
     * Clears any active debug shapes for the given player.
     */
    public static void clearForPlayer(PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }
        RenderState state = PLAYER_STATES.get(playerRef.getUuid());
        if (state != null) {
            state.active = false;
            state.hasSelection = false;
            state.lastSentMs = 0L;
        }
        PacketHandler handler = playerRef.getPacketHandler();
        if (handler != null) {
            handler.write(new ClearDebugShapes());
        }
    }

    /**
     * Highlights a specific Plot for {@code durationSeconds} in Green (if owned by the player)
     * or Red/Yellow (if owned by another player).
     */
    public static void showClaimedPlot(PlayerRef playerRef, Plot plot, float durationSeconds) {
        if (playerRef == null || plot == null) {
            return;
        }
        PacketHandler handler = playerRef.getPacketHandler();
        if (handler == null) {
            return;
        }
        UUID uuid = playerRef.getUuid();
        RenderState state = PLAYER_STATES.computeIfAbsent(uuid, k -> new RenderState());
        long now = System.currentTimeMillis();
        state.suppressClearUntilMs = now + (long) (durationSeconds * 1000.0f);
        state.lastSentMs = now;

        Vector3i playerPos = getLivePlayerBlockPos(playerRef, 64);
        handler.write(new ClearDebugShapes());

        PlotManager pm = plotManagerRef;
        boolean holdingTool = pm != null && ClaimToolUtil.isUsingClaimTool(uuid, getHeldItemConcurrent(playerRef), pm);
        if (holdingTool && pm != null) {
            String worldName = resolvePlayerWorldName(playerRef, pm.getSelection(uuid));
            List<Plot> nearbyPlots = pm.getPlotsInWorldNear(worldName, playerPos.x, playerPos.z, NEARBY_PLOT_RADIUS);
            boolean drewTarget = false;
            for (Plot p : nearbyPlots) {
                if (p == plot || (p.getId() != null && p.getId().equals(plot.getId()))) {
                    drewTarget = true;
                }
                sendPlotOutlineShapes(handler, playerRef, p, playerPos.y, durationSeconds, false);
            }
            if (!drewTarget) {
                sendPlotOutlineShapes(handler, playerRef, plot, playerPos.y, durationSeconds, true);
            }
            state.active = true;
        } else {
            state.active = false;
            sendPlotOutlineShapes(handler, playerRef, plot, playerPos.y, durationSeconds, true);
        }
    }

    private static void tickAllPlayers() {
        PlotManager pm = plotManagerRef;
        if (pm == null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            Collection<PlayerRef> players = universe.getPlayers();
            if (players == null || players.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            long currentPlotsVersion = pm.getPlotsVersion();

            for (PlayerRef playerRef : players) {
                if (playerRef == null || !playerRef.isValid()) {
                    continue;
                }
                UUID uuid = playerRef.getUuid();
                PlotManager.CornerSelection sel = pm.getAllSelections().get(uuid);

                // Auto-expire abandoned Corner 1 selections after 10 minutes
                if (sel != null && sel.pos1 != null && sel.pos1TimestampMs > 0L
                        && (now - sel.pos1TimestampMs) > SELECTION_TIMEOUT_MS) {
                    sel.clearCorners();
                }

                boolean hasSelection = (sel != null && sel.pos1 != null);
                ItemStack heldItem = getHeldItemConcurrent(playerRef);
                boolean holdingTool = ClaimToolUtil.isUsingClaimTool(uuid, heldItem, pm);
                RenderState state = PLAYER_STATES.get(uuid);

                if (!hasSelection && !holdingTool) {
                    if (state != null && state.active && now >= state.suppressClearUntilMs) {
                        clearForPlayer(playerRef);
                    }
                    continue;
                }

                int fallbackY = hasSelection ? sel.pos1.y : 64;
                Vector3i livePos = getLivePlayerBlockPos(playerRef, fallbackY);
                if (state == null) {
                    state = new RenderState();
                    PLAYER_STATES.put(uuid, state);
                }

                boolean needsKeepAlive = (now - state.lastSentMs) >= 850L;
                boolean shouldRender;

                if (hasSelection) {
                    // When Corner 1 is placed, update immediately on every block step so the green selection follows smoothly
                    shouldRender = !state.active
                            || !state.hasSelection
                            || state.c1x != sel.pos1.x
                            || state.c1y != sel.pos1.y
                            || state.c1z != sel.pos1.z
                            || state.c2x != livePos.x
                            || state.c2y != livePos.y
                            || state.c2z != livePos.z
                            || state.mode != sel.heightMode
                            || state.plotsVersion != currentPlotsVersion
                            || needsKeepAlive;
                } else {
                    // Holding Claim Stick with no active Corner 1:
                    // Only redraw when newly activated, plots changed, player moved enough to shift vertical rings, or keep-alive
                    boolean movedSignificantly = Math.abs(livePos.x - state.anchorX) >= 6
                            || Math.abs(livePos.y - state.anchorY) >= 2
                            || Math.abs(livePos.z - state.anchorZ) >= 6;
                    shouldRender = !state.active
                            || state.hasSelection
                            || state.plotsVersion != currentPlotsVersion
                            || movedSignificantly
                            || needsKeepAlive;
                }

                if (shouldRender) {
                    state.hasSelection = hasSelection;
                    if (hasSelection) {
                        state.c1x = sel.pos1.x;
                        state.c1y = sel.pos1.y;
                        state.c1z = sel.pos1.z;
                        state.mode = sel.heightMode;
                    }
                    state.c2x = livePos.x;
                    state.c2y = livePos.y;
                    state.c2z = livePos.z;
                    state.anchorX = livePos.x;
                    state.anchorY = livePos.y;
                    state.anchorZ = livePos.z;
                    state.plotsVersion = currentPlotsVersion;
                    state.lastSentMs = now;
                    state.active = true;

                    String worldName = resolvePlayerWorldName(playerRef, sel);
                    renderPlayerFrame(playerRef, pm, worldName, sel, livePos, 1.4f, false);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void renderPlayerFrame(PlayerRef playerRef,
                                          PlotManager plotManager,
                                          String worldName,
                                          PlotManager.CornerSelection sel,
                                          Vector3i playerPos,
                                          float duration,
                                          boolean fadeOut) {
        PacketHandler handler = playerRef.getPacketHandler();
        if (handler == null) {
            return;
        }

        // Clear previous frame's debug shapes for this player
        handler.write(new ClearDebugShapes());

        // 1. Render all nearby claimed plots in the same world:
        //    - Own plots in Glowing Green
        //    - Others' plots in Glowing Red/Yellow
        if (plotManager != null && worldName != null) {
            List<Plot> nearbyPlots = plotManager.getPlotsInWorldNear(worldName, playerPos.x, playerPos.z, NEARBY_PLOT_RADIUS);
            for (Plot plot : nearbyPlots) {
                sendPlotOutlineShapes(handler, playerRef, plot, playerPos.y, duration, fadeOut);
            }
        }

        // 2. If the player has an active Corner 1 selection, render the live glowing green selection outline
        if (sel != null && sel.pos1 != null) {
            sendActiveSelectionShapes(
                    handler,
                    sel.pos1.x, sel.pos1.y, sel.pos1.z,
                    playerPos.x, playerPos.y, playerPos.z,
                    sel.heightMode,
                    duration,
                    fadeOut
            );
        }
    }

    /**
     * Sends the 3D boundary shapes for an existing claimed plot:
     * - GREEN if owned by {@code playerRef}
     * - RED / YELLOW if owned by another player
     */
    private static void sendPlotOutlineShapes(PacketHandler handler,
                                              PlayerRef playerRef,
                                              Plot plot,
                                              int playerY,
                                              float duration,
                                              boolean fadeOut) {
        UUID viewerUuid = playerRef.getUuid();
        String viewerName = playerRef.getUsername();
        boolean isOwner = plot.isOwner(viewerUuid);
        boolean isTrusted = !isOwner && plot.isAuthorized(viewerUuid, viewerName);

        Vector3fc fillColor;
        Vector3fc wireframeColor;
        Vector3fc pillarColor;
        Vector3fc ringColor;

        if (isOwner) {
            // Player's own plot -> Glowing Green
            fillColor = COLOR_FILL_GREEN;
            wireframeColor = COLOR_GLOW_GREEN;
            pillarColor = COLOR_GLOW_GREEN;
            ringColor = COLOR_GLOW_GREEN;
        } else if (isTrusted) {
            // Other player's plot where viewer is trusted -> Yellow/Gold with Red-Orange corner pillars
            fillColor = COLOR_FILL_YELLOW;
            wireframeColor = COLOR_GLOW_YELLOW;
            pillarColor = COLOR_GLOW_ORANGE_RED;
            ringColor = COLOR_GLOW_YELLOW;
        } else {
            // Other player's plot (untrusted) -> Red walls & vertical pillars + Bright Yellow horizontal rings
            fillColor = COLOR_FILL_RED;
            wireframeColor = COLOR_GLOW_RED;
            pillarColor = COLOR_GLOW_RED;
            ringColor = COLOR_GLOW_YELLOW;
        }

        byte baseFlags = (byte) (fadeOut ? DebugUtils.FLAG_FADE : DebugUtils.FLAG_NONE);
        byte wireframeFlags = (byte) (DebugUtils.FLAG_NO_SOLID | (fadeOut ? DebugUtils.FLAG_FADE : 0));

        for (Plot.PlotBox box : plot.getBoxes()) {
            double minX = box.getMinX() - 0.015;
            double maxX = box.getMaxX() + 1.015;
            double minZ = box.getMinZ() - 0.015;
            double maxZ = box.getMaxZ() + 1.015;

            if (!box.isFullHeight()) {
                // Specific-Height (3D Cuboid) claimed plot box
                double minY = box.getMinY() - 0.015;
                double maxY = box.getMaxY() + 1.015;

                sendBox(handler, minX, minY, minZ, maxX, maxY, maxZ,
                        fillColor, duration, baseFlags, 0.14f);
                sendBox(handler, minX, minY, minZ, maxX, maxY, maxZ,
                        wireframeColor, duration, wireframeFlags, 0.92f);

                sendHorizontalRingBeams(handler, minX, minY, minZ, maxX, maxZ,
                        ringColor, duration, baseFlags, 0.92f);
                sendHorizontalRingBeams(handler, minX, maxY, minZ, maxX, maxZ,
                        ringColor, duration, baseFlags, 0.92f);
                sendVerticalCornerPillars(handler, minX, minY, minZ, maxX, maxY, maxZ,
                        pillarColor, duration, baseFlags, 0.92f);

                // If player's vertical level is inside this cuboid, also draw a ring at their feet
                if (playerY >= box.getMinY() && playerY <= box.getMaxY()) {
                    sendHorizontalRingBeams(handler, minX, playerY + 0.02, minZ, maxX, maxZ,
                            ringColor, duration, baseFlags, 0.88f);
                }
            } else {
                // Bedrock-to-Ceiling (Full Vertical) claimed plot box
                double baseY = playerY - 48.0;
                double topY = playerY + 64.0;

                sendBox(handler, minX, baseY, minZ, maxX, topY, maxZ,
                        fillColor, duration, baseFlags, 0.11f);
                sendBox(handler, minX, baseY, minZ, maxX, topY, maxZ,
                        wireframeColor, duration, wireframeFlags, 0.85f);

                // 4 tall vertical corner pillars
                sendVerticalCornerPillars(handler, minX, baseY, minZ, maxX, topY, maxZ,
                        pillarColor, duration, baseFlags, 0.92f);

                // Horizontal perimeter rings at player's ground level and eye/chest level
                sendHorizontalRingBeams(handler, minX, playerY + 0.02, minZ, maxX, maxZ,
                        ringColor, duration, baseFlags, 0.95f);
                sendHorizontalRingBeams(handler, minX, playerY + 1.50, minZ, maxX, maxZ,
                        pillarColor, duration, baseFlags, 0.85f);
            }
        }
    }

    /**
     * Sends the live glowing green 3D selection outline from Corner 1 (x1,y1,z1) to Player/Corner 2 (x2,y2,z2).
     */
    private static void sendActiveSelectionShapes(PacketHandler handler,
                                                  int x1, int y1, int z1,
                                                  int x2, int y2, int z2,
                                                  ClaimHeightMode mode,
                                                  float duration,
                                                  boolean fadeOut) {
        byte baseFlags = (byte) (fadeOut ? DebugUtils.FLAG_FADE : DebugUtils.FLAG_NONE);
        byte wireframeFlags = (byte) (DebugUtils.FLAG_NO_SOLID | (fadeOut ? DebugUtils.FLAG_FADE : 0));

        // 1. Highlight Corner 1 anchor block (and Corner 2 block if different)
        sendBox(handler, x1 - 0.03, y1 - 0.03, z1 - 0.03, x1 + 1.03, y1 + 1.03, z1 + 1.03,
                COLOR_CORNER_MARKER, duration, wireframeFlags, 0.95f);
        sendBox(handler, x1 - 0.02, y1 - 0.02, z1 - 0.02, x1 + 1.02, y1 + 1.02, z1 + 1.02,
                COLOR_CORNER_MARKER, duration, baseFlags, 0.32f);

        if (x1 != x2 || y1 != y2 || z1 != z2) {
            sendBox(handler, x2 - 0.03, y2 - 0.03, z2 - 0.03, x2 + 1.03, y2 + 1.03, z2 + 1.03,
                    COLOR_CORNER_MARKER, duration, wireframeFlags, 0.90f);
        }

        double minX = Math.min(x1, x2) - 0.015;
        double maxX = Math.max(x1, x2) + 1.015;
        double minZ = Math.min(z1, z2) - 0.015;
        double maxZ = Math.max(z1, z2) + 1.015;

        if (mode == ClaimHeightMode.SPECIFIC_HEIGHT) {
            // Exact 3D cuboid [minX..maxX, minY..maxY, minZ..maxZ] (like Minecraft /fill)
            double minY = Math.min(y1, y2) - 0.015;
            double maxY = Math.max(y1, y2) + 1.015;

            sendBox(handler, minX, minY, minZ, maxX, maxY, maxZ,
                    COLOR_FILL_GREEN, duration, baseFlags, 0.16f);
            sendBox(handler, minX, minY, minZ, maxX, maxY, maxZ,
                    COLOR_GLOW_GREEN, duration, wireframeFlags, 0.95f);

            sendCuboidEdgeBeams(handler, minX, minY, minZ, maxX, maxY, maxZ,
                    COLOR_GLOW_GREEN, duration, baseFlags, 0.92f);
        } else {
            // Bedrock-to-Ceiling mode:
            double baseY = Math.min(y1, y2) - 48.0;
            double topY = Math.max(y1, y2) + 64.0;

            sendBox(handler, minX, baseY, minZ, maxX, topY, maxZ,
                    COLOR_FILL_GREEN, duration, baseFlags, 0.12f);
            sendBox(handler, minX, baseY, minZ, maxX, topY, maxZ,
                    COLOR_GLOW_GREEN, duration, wireframeFlags, 0.85f);

            sendVerticalCornerPillars(handler, minX, baseY, minZ, maxX, topY, maxZ,
                    COLOR_GLOW_GREEN, duration, baseFlags, 0.92f);

            sendHorizontalRingBeams(handler, minX, y1 + 0.02, minZ, maxX, maxZ,
                    COLOR_GLOW_GREEN, duration, baseFlags, 0.95f);

            if (Math.abs(y2 - y1) >= 1) {
                sendHorizontalRingBeams(handler, minX, y2 + 0.02, minZ, maxX, maxZ,
                        COLOR_GLOW_GREEN, duration, baseFlags, 0.95f);
            }
            sendHorizontalRingBeams(handler, minX, y2 + 1.02, minZ, maxX, maxZ,
                    COLOR_GLOW_GREEN, duration, baseFlags, 0.85f);
        }
    }

    public static ItemStack getHeldItemConcurrent(PlayerRef playerRef) {
        if (playerRef == null) {
            return null;
        }
        try {
            InventoryComponent.Tool tool = playerRef.getComponentConcurrent(InventoryComponent.Tool.getComponentType());
            if (tool != null && tool.isUsingToolsItem()) {
                return tool.getActiveItem();
            }
            InventoryComponent.Hotbar hotbar = playerRef.getComponentConcurrent(InventoryComponent.Hotbar.getComponentType());
            if (hotbar != null) {
                return hotbar.getActiveItem();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String resolvePlayerWorldName(PlayerRef playerRef, PlotManager.CornerSelection sel) {
        if (playerRef != null) {
            try {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null && Universe.get() != null) {
                    World world = Universe.get().getWorld(worldUuid);
                    if (world != null) {
                        return world.getName();
                    }
                }
            } catch (Exception ignored) {
            }
            try {
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref != null && ref.isValid()) {
                    World world = ((EntityStore) ref.getStore().getExternalData()).getWorld();
                    if (world != null) {
                        return world.getName();
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (sel != null && sel.worldName != null) {
            return sel.worldName;
        }
        return "default";
    }

    public static Vector3i getLivePlayerBlockPos(PlayerRef playerRef, int fallbackY) {
        if (playerRef == null) {
            return new Vector3i(0, fallbackY, 0);
        }
        try {
            TransformComponent tc = playerRef.getComponentConcurrent(TransformComponent.getComponentType());
            if (tc != null && tc.getPosition() != null) {
                Vector3d pos = tc.getPosition();
                return new Vector3i(
                        (int) Math.floor(pos.x),
                        (int) Math.floor(pos.y),
                        (int) Math.floor(pos.z)
                );
            }
        } catch (Exception ignored) {
        }
        try {
            Transform transform = playerRef.getTransform();
            if (transform != null && transform.getPosition() != null) {
                Vector3d pos = transform.getPosition();
                return new Vector3i(
                        (int) Math.floor(pos.x),
                        (int) Math.floor(pos.y),
                        (int) Math.floor(pos.z)
                );
            }
        } catch (Exception ignored) {
        }
        return new Vector3i(0, fallbackY, 0);
    }

    private static void sendBox(PacketHandler handler,
                                double x0, double y0, double z0,
                                double x1, double y1, double z1,
                                Vector3fc color,
                                float duration,
                                byte flags,
                                float opacity) {
        double sx = x1 - x0;
        double sy = y1 - y0;
        double sz = z1 - z0;
        double cx = x0 + sx * 0.5;
        double cy = y0 + sy * 0.5;
        double cz = z0 + sz * 0.5;

        Matrix4d matrix = new Matrix4d()
                .translate(cx, cy, cz)
                .scale(sx, sy, sz);

        DisplayDebug packet = new DisplayDebug(
                DebugShape.Cube,
                Matrix4dUtil.asFloatData(matrix),
                color,
                duration,
                flags,
                null,
                opacity
        );
        handler.write(packet);
    }

    private static void sendCuboidEdgeBeams(PacketHandler handler,
                                            double x0, double y0, double z0,
                                            double x1, double y1, double z1,
                                            Vector3fc color,
                                            float duration,
                                            byte flags,
                                            float opacity) {
        sendHorizontalRingBeams(handler, x0, y0, z0, x1, z1, color, duration, flags, opacity);
        sendHorizontalRingBeams(handler, x0, y1, minZ(z0), x1, z1, color, duration, flags, opacity);
        sendVerticalCornerPillars(handler, x0, y0, z0, x1, y1, z1, color, duration, flags, opacity);
    }

    private static double minZ(double z) {
        return z;
    }

    private static void sendHorizontalRingBeams(PacketHandler handler,
                                                double x0, double y, double z0,
                                                double x1, double z1,
                                                Vector3fc color,
                                                float duration,
                                                byte flags,
                                                float opacity) {
        double halfT = BEAM_THICKNESS * 0.5;
        // 2 beams along X axis (at z0 and z1)
        sendBox(handler, x0 - halfT, y - halfT, z0 - halfT, x1 + halfT, y + halfT, z0 + halfT,
                color, duration, flags, opacity);
        sendBox(handler, x0 - halfT, y - halfT, z1 - halfT, x1 + halfT, y + halfT, z1 + halfT,
                color, duration, flags, opacity);
        // 2 beams along Z axis (at x0 and x1)
        sendBox(handler, x0 - halfT, y - halfT, z0 - halfT, x0 + halfT, y + halfT, z1 + halfT,
                color, duration, flags, opacity);
        sendBox(handler, x1 - halfT, y - halfT, z0 - halfT, x1 + halfT, y + halfT, z1 + halfT,
                color, duration, flags, opacity);
    }

    private static void sendVerticalCornerPillars(PacketHandler handler,
                                                  double x0, double y0, double z0,
                                                  double x1, double y1, double z1,
                                                  Vector3fc color,
                                                  float duration,
                                                  byte flags,
                                                  float opacity) {
        double halfT = BEAM_THICKNESS * 0.5;
        sendBox(handler, x0 - halfT, y0, z0 - halfT, x0 + halfT, y1, z0 + halfT,
                color, duration, flags, opacity);
        sendBox(handler, x1 - halfT, y0, z0 - halfT, x1 + halfT, y1, z0 + halfT,
                color, duration, flags, opacity);
        sendBox(handler, x0 - halfT, y0, z1 - halfT, x0 + halfT, y1, z1 + halfT,
                color, duration, flags, opacity);
        sendBox(handler, x1 - halfT, y0, z1 - halfT, x1 + halfT, y1, z1 + halfT,
                color, duration, flags, opacity);
    }
}

