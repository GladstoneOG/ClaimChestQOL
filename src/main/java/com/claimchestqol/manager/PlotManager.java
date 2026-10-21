package com.claimchestqol.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.claimchestqol.config.PlotConfig;
import com.claimchestqol.map.PlotWorldMapService;
import com.claimchestqol.model.ClaimHeightMode;
import com.claimchestqol.model.Plot;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages plot claims, plot merging, corner selections, admin config enforcement,
 * plot decay, world map updates, persistence, and spatial container queries.
 */
public class PlotManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PLOT_LIST_TYPE = new TypeToken<List<Plot>>() {}.getType();

    private final Path storageFile;
    private final PlotConfig config;
    private final List<Plot> plots = new CopyOnWriteArrayList<>();
    private final Map<UUID, CornerSelection> selections = new ConcurrentHashMap<>();
    private final AtomicLong plotsVersion = new AtomicLong(1L);
    private ScheduledExecutorService maintenanceExecutor;

    public static class CornerSelection {
        public String worldName;
        public Vector3i pos1;
        public Vector3i pos2;
        public ClaimHeightMode heightMode = ClaimHeightMode.BEDROCK_TO_CEILING;
        public boolean toolModeEnabled = false;
        public long lastActionTimestampMs = 0L;
        public long pos1TimestampMs = 0L;

        public void clearCorners() {
            this.pos1 = null;
            this.pos2 = null;
            this.pos1TimestampMs = 0L;
        }
    }

    public record ClaimOutcome(boolean success, String message, Plot plot) {}

    public PlotManager(Path dataDirectory) {
        this.storageFile = dataDirectory.resolve("plots.json");
        this.config = PlotConfig.loadOrCreate(dataDirectory);
        load();
    }

    public PlotConfig getConfig() {
        return config;
    }

    public long getPlotsVersion() {
        return plotsVersion.get();
    }

    public synchronized void startMaintenanceTask() {
        if (maintenanceExecutor != null && !maintenanceExecutor.isShutdown()) {
            return;
        }
        maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClaimChest-Maintenance");
            t.setDaemon(true);
            return t;
        });
        // Run every 30 seconds to keep online owners' decay timers fresh, hook world maps, and check plot decay
        maintenanceExecutor.scheduleAtFixedRate(this::runPeriodicMaintenance, 10L, 30L, TimeUnit.SECONDS);
    }

    public synchronized void shutdownMaintenanceTask() {
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdownNow();
            maintenanceExecutor = null;
        }
    }

    private void runPeriodicMaintenance() {
        try {
            // 1. Ensure all loaded worlds have the World Map territory generator & marker provider installed
            PlotWorldMapService.installOnAllWorlds(this);

            // 2. Refresh activity timestamps for all online plot owners
            Universe universe = Universe.get();
            if (universe != null) {
                Collection<PlayerRef> onlinePlayers = universe.getPlayers();
                if (onlinePlayers != null) {
                    for (PlayerRef playerRef : onlinePlayers) {
                        if (playerRef != null && playerRef.getUuid() != null) {
                            touchOwnerActivity(playerRef.getUuid());
                        }
                    }
                }
            }

            // 3. Check for expired plots if plot decay is enabled
            if (config.isPlotDecayEnabled()) {
                checkAndExpireDecayedPlots();
            }
        } catch (Exception ignored) {
        }
    }

    public void touchOwnerActivity(UUID ownerUuid) {
        if (ownerUuid == null) {
            return;
        }
        for (Plot plot : plots) {
            if (plot.isOwner(ownerUuid)) {
                plot.touchActivity();
            }
        }
    }

    public synchronized int checkAndExpireDecayedPlots() {
        if (!config.isPlotDecayEnabled()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long decayDurationMs = config.getPlotDecayDurationMs();
        List<Plot> expired = new ArrayList<>();
        for (Plot plot : plots) {
            if (plot.isExpired(now, decayDurationMs)) {
                expired.add(plot);
            }
        }
        if (expired.isEmpty()) {
            return 0;
        }
        for (Plot plot : expired) {
            plots.remove(plot);
            PlotWorldMapService.invalidatePlotChunks(plot, this);
            System.out.println("[ClaimChestQOL] Plot owned by " + plot.getOwnerName()
                    + " at " + plot.formatBounds() + " in world '" + plot.getWorldName()
                    + "' decayed due to inactivity (" + config.getPlotDecayHours() + "h).");
        }
        save();
        return expired.size();
    }

    public synchronized void load() {
        plots.clear();
        plotsVersion.incrementAndGet();
        if (!Files.exists(storageFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(storageFile, StandardCharsets.UTF_8)) {
            List<Plot> loaded = GSON.fromJson(reader, PLOT_LIST_TYPE);
            if (loaded != null) {
                for (Plot p : loaded) {
                    if (p != null) {
                        p.normalizeAfterLoad();
                        plots.add(p);
                    }
                }
            }
            plotsVersion.incrementAndGet();
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to load plots.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        plotsVersion.incrementAndGet();
        try {
            if (storageFile.getParent() != null) {
                Files.createDirectories(storageFile.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(storageFile, StandardCharsets.UTF_8)) {
                GSON.toJson(new ArrayList<>(plots), PLOT_LIST_TYPE, writer);
            }
        } catch (IOException e) {
            System.err.println("[ClaimChestQOL] Failed to save plots.json: " + e.getMessage());
        }
    }

    public CornerSelection getSelection(UUID playerUuid) {
        return selections.computeIfAbsent(playerUuid, k -> new CornerSelection());
    }

    public Map<UUID, CornerSelection> getAllSelections() {
        return selections;
    }

    public List<Plot> getAllPlots() {
        return Collections.unmodifiableList(plots);
    }

    public List<Plot> getPlotsByOwner(UUID ownerUuid) {
        List<Plot> owned = new ArrayList<>();
        for (Plot plot : plots) {
            if (plot.isOwner(ownerUuid)) {
                owned.add(plot);
            }
        }
        return owned;
    }

    public List<Plot> getPlotsInWorldNear(String worldName, int x, int z, int maxHorizontalDistance) {
        if (worldName == null) {
            return Collections.emptyList();
        }
        List<Plot> nearby = new ArrayList<>();
        for (Plot plot : plots) {
            if (!worldName.equalsIgnoreCase(plot.getWorldName())) {
                continue;
            }
            if (x >= plot.getMinX() - maxHorizontalDistance && x <= plot.getMaxX() + maxHorizontalDistance
                    && z >= plot.getMinZ() - maxHorizontalDistance && z <= plot.getMaxZ() + maxHorizontalDistance) {
                nearby.add(plot);
            }
        }
        return nearby;
    }

    public Plot getPlotAt(String worldName, int x, int y, int z) {
        for (Plot plot : plots) {
            if (plot.contains(worldName, x, y, z)) {
                return plot;
            }
        }
        return null;
    }

    public List<Plot> getPlotsAt2D(String worldName, int x, int z) {
        List<Plot> result = new ArrayList<>(2);
        for (Plot plot : plots) {
            if (plot.contains2D(worldName, x, z)) {
                result.add(plot);
            }
        }
        return result;
    }

    public Plot getPlotAt2D(String worldName, int x, int z) {
        for (Plot plot : plots) {
            if (plot.contains2D(worldName, x, z)) {
                return plot;
            }
        }
        return null;
    }

    public Plot getPlotWithinBuffer(String worldName, int x, int y, int z, int buffer) {
        for (Plot plot : plots) {
            if (plot.isWithinBuffer(worldName, x, y, z, buffer)) {
                return plot;
            }
        }
        return null;
    }

    public synchronized ClaimOutcome claimPlot(UUID ownerUuid, String ownerName, String worldName,
                                               Vector3i c1, Vector3i c2) {
        CornerSelection sel = ownerUuid != null ? getSelection(ownerUuid) : null;
        ClaimHeightMode mode = (sel != null && sel.heightMode != null)
                ? sel.heightMode
                : ClaimHeightMode.BEDROCK_TO_CEILING;
        return claimPlot(ownerUuid, ownerName, worldName, c1, c2, mode);
    }

    public synchronized ClaimOutcome claimPlot(UUID ownerUuid, String ownerName, String worldName,
                                               Vector3i c1, Vector3i c2, ClaimHeightMode heightMode) {
        if (c1 == null || c2 == null || worldName == null) {
            return new ClaimOutcome(false, "Both Corner 1 and Corner 2 must be selected in the same world first.", null);
        }
        boolean fullHeight = (heightMode != ClaimHeightMode.SPECIFIC_HEIGHT);
        Plot candidatePlot = new Plot(UUID.randomUUID(), worldName, ownerUuid, ownerName,
                c1.x, c1.y, c1.z, c2.x, c2.y, c2.z, fullHeight);

        // 1. Check Specific-Height vertical limit on the new selection
        int maxPlotHeightY = config.getMaxPlotHeightY();
        if (!fullHeight && maxPlotHeightY > 0 && candidatePlot.getHeightY() > maxPlotHeightY) {
            return new ClaimOutcome(false,
                    "Selection height (" + candidatePlot.getHeightY() + " blocks) exceeds server maximum height of "
                            + maxPlotHeightY + " blocks!",
                    null);
        }

        // 2. Check for 3D overlap with existing plots
        //    - If overlapping another player's plot in 3D -> reject
        //    - If overlapping the same player's existing plot(s) in 3D -> merge them together!
        List<Plot> ownOverlapping = new ArrayList<>();
        for (Plot existing : plots) {
            if (existing.intersectsPlot3D(candidatePlot)) {
                if (!existing.isOwner(ownerUuid)) {
                    return new ClaimOutcome(false,
                            "This area overlaps with " + existing.getOwnerName() + "'s plot at " + existing.formatBounds() + "!",
                            null);
                } else {
                    ownOverlapping.add(existing);
                }
            }
        }

        // 3. Check maxPlotsPerPlayer limit (merging with own existing plot(s) does not add to plot count!)
        int maxPlotsPerPlayer = config.getMaxPlotsPerPlayer();
        if (maxPlotsPerPlayer > 0) {
            int currentOwnedCount = getPlotsByOwner(ownerUuid).size();
            int resultingOwnedCount = currentOwnedCount - ownOverlapping.size() + 1;
            if (resultingOwnedCount > maxPlotsPerPlayer) {
                return new ClaimOutcome(false,
                        "You have reached the maximum of " + maxPlotsPerPlayer + " plots per player! "
                                + "(Tip: Overlapping a new claim with your existing plot merges them into 1 plot, or use /unclaim.)",
                        null);
            }
        }

        // 4. If overlapping one or more of the player's own plots, merge them into a single unified plot
        Plot finalPlot;
        boolean wasMerged = !ownOverlapping.isEmpty();
        if (wasMerged) {
            Plot base = ownOverlapping.get(0);
            // Create a working copy so we can validate size limits before mutating state
            Plot mergedPreview = new Plot(
                    base.getId(),
                    worldName,
                    ownerUuid,
                    ownerName,
                    base.getMinX(), base.getMinY(), base.getMinZ(),
                    base.getMaxX(), base.getMaxY(), base.getMaxZ(),
                    base.isFullHeight()
            );
            mergedPreview.mergeWith(base);
            for (int i = 1; i < ownOverlapping.size(); i++) {
                mergedPreview.mergeWith(ownOverlapping.get(i));
            }
            mergedPreview.mergeWith(candidatePlot);
            finalPlot = mergedPreview;
        } else {
            finalPlot = candidatePlot;
        }

        // 5. Validate side length and 2D area limits against PlotConfig
        int maxSide = config.getMaxPlotSideLength();
        if (maxSide > 0 && (finalPlot.getWidthX() > maxSide || finalPlot.getLengthZ() > maxSide)) {
            return new ClaimOutcome(false,
                    String.format("Plot dimensions (%dx%d) exceed the server maximum side length of %d blocks!",
                            finalPlot.getWidthX(), finalPlot.getLengthZ(), maxSide),
                    null);
        }

        int maxArea2D = config.getMaxPlotArea2D();
        int plotArea2D = finalPlot.calculateArea2D();
        if (maxArea2D > 0 && plotArea2D > maxArea2D) {
            return new ClaimOutcome(false,
                    String.format("Plot 2D area (%d blocks) exceeds the server maximum area of %d blocks!",
                            plotArea2D, maxArea2D),
                    null);
        }

        // 6. Commit merged/new plot
        if (wasMerged) {
            for (Plot oldPlot : ownOverlapping) {
                plots.remove(oldPlot);
            }
        }
        finalPlot.touchActivity();
        plots.add(finalPlot);
        save();

        // 7. Refresh World Map chunks for the claimed/merged plot
        PlotWorldMapService.invalidatePlotChunks(finalPlot, this);

        String mergeNote;
        if (!wasMerged) {
            mergeNote = "";
        } else if (ownOverlapping.size() == 1) {
            mergeNote = " (Merged with your overlapping plot)";
        } else {
            mergeNote = " (Merged " + ownOverlapping.size() + " of your overlapping plots into 1)";
        }

        return new ClaimOutcome(true,
                "Plot claimed" + mergeNote + "! Area: " + finalPlot.formatBounds(),
                finalPlot);
    }

    public synchronized boolean removePlot(Plot plot) {
        if (plot == null) {
            return false;
        }
        boolean removed = plots.remove(plot);
        if (removed) {
            save();
            PlotWorldMapService.invalidatePlotChunks(plot, this);
        }
        return removed;
    }

    /**
     * Collects all ItemContainers (chests/storage blocks) located inside the given plot.
     * If priorityFirst is non-null, it is placed first in the returned list.
     */
    public List<ItemContainer> getContainersInPlot(World world, Plot plot, ItemContainer priorityFirst) {
        List<ItemContainer> result = new ArrayList<>();
        if (priorityFirst != null) {
            result.add(priorityFirst);
        }
        if (world == null || plot == null || !world.getName().equalsIgnoreCase(plot.getWorldName())) {
            return result;
        }

        try {
            Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
            SpatialResource<Ref<ChunkStore>, ChunkStore> spatialResource =
                    chunkStore.getResource(BlockModule.get().getItemContainerSpatialResourceType());
            if (spatialResource == null) {
                return result;
            }

            List<Ref<ChunkStore>> refs = SpatialResource.getThreadLocalReferenceList();
            refs.clear();

            double centerX = plot.getCenterX();
            double centerY = plot.isFullHeight() ? 64.0 : plot.getCenterY();
            double centerZ = plot.getCenterZ();
            double halfWidthX = (plot.getWidthX() * 0.5) + 4.0;
            double halfHeightY = plot.isFullHeight() ? 2048.0 : Math.min(2048.0, (plot.getHeightY() * 0.5) + 4.0);
            double halfLengthZ = (plot.getLengthZ() * 0.5) + 4.0;

            Vector3d centerPos = new Vector3d(centerX, centerY, centerZ);
            spatialResource.getSpatialStructure().ordered3DAxis(
                    centerPos,
                    halfWidthX,
                    halfHeightY,
                    halfLengthZ,
                    refs
            );

            for (Ref<ChunkStore> ref : refs) {
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                ItemContainerBlock containerBlock = chunkStore.getComponent(ref, ItemContainerBlock.getComponentType());
                BlockModule.BlockStateInfo stateInfo = chunkStore.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
                if (containerBlock == null || stateInfo == null) {
                    continue;
                }
                Ref<ChunkStore> sectionRef = stateInfo.getSectionRef();
                if (sectionRef == null || !sectionRef.isValid()) {
                    continue;
                }
                ChunkSection section = chunkStore.getComponent(sectionRef, ChunkSection.getComponentType());
                if (section == null) {
                    continue;
                }
                int index = stateInfo.getIndex();
                int bx = ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(index));
                int by = ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(index));
                int bz = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(index));

                if (plot.contains(world.getName(), bx, by, bz)) {
                    SimpleItemContainer container = containerBlock.getItemContainer();
                    if (container != null && !result.contains(container)) {
                        result.add(container);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Error querying containers in plot: " + e.getMessage());
        }

        return result;
    }

    /**
     * Collects nearby containers around a bench outside a player's plot,
     * filtering out any containers that belong to another player's plot where the user is not trusted.
     */
    public List<ItemContainer> getAuthorizedNearbyContainers(World world, int benchX, int benchY, int benchZ,
                                                             UUID playerUuid, String playerName) {
        List<ItemContainer> result = new ArrayList<>();
        if (world == null) {
            return result;
        }
        try {
            var craftingConfig = world.getGameplayConfig().getCraftingConfig();
            int limit = Math.max(250, craftingConfig.getBenchMaterialChestLimit());
            double hRadius = craftingConfig.getBenchMaterialHorizontalChestSearchRadius();
            double vRadius = craftingConfig.getBenchMaterialVerticalChestSearchRadius();

            Store<ChunkStore> chunkStore = world.getChunkStore().getStore();
            SpatialResource<Ref<ChunkStore>, ChunkStore> spatialResource =
                    chunkStore.getResource(BlockModule.get().getItemContainerSpatialResourceType());
            if (spatialResource == null) {
                return result;
            }

            List<Ref<ChunkStore>> refs = SpatialResource.getThreadLocalReferenceList();
            refs.clear();

            Vector3d benchPos = new Vector3d(benchX, benchY, benchZ);
            spatialResource.getSpatialStructure().ordered3DAxis(benchPos, hRadius, vRadius, hRadius, refs);

            double minX = benchX - hRadius;
            double maxX = benchX + hRadius + 1.0;
            double minY = benchY - vRadius;
            double maxY = benchY + vRadius + 1.0;
            double minZ = benchZ - hRadius;
            double maxZ = benchZ + hRadius + 1.0;

            for (Ref<ChunkStore> ref : refs) {
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                ItemContainerBlock containerBlock = chunkStore.getComponent(ref, ItemContainerBlock.getComponentType());
                BlockModule.BlockStateInfo stateInfo = chunkStore.getComponent(ref, BlockModule.BlockStateInfo.getComponentType());
                if (containerBlock == null || stateInfo == null) {
                    continue;
                }
                Ref<ChunkStore> sectionRef = stateInfo.getSectionRef();
                if (sectionRef == null || !sectionRef.isValid()) {
                    continue;
                }
                ChunkSection section = chunkStore.getComponent(sectionRef, ChunkSection.getComponentType());
                if (section == null) {
                    continue;
                }
                int index = stateInfo.getIndex();
                int bx = ChunkUtil.worldCoordFromLocalCoord(section.getX(), ChunkUtil.xFromIndex(index));
                int by = ChunkUtil.worldCoordFromLocalCoord(section.getY(), ChunkUtil.yFromIndex(index));
                int bz = ChunkUtil.worldCoordFromLocalCoord(section.getZ(), ChunkUtil.zFromIndex(index));

                if (bx >= minX && bx <= maxX && by >= minY && by <= maxY && bz >= minZ && bz <= maxZ) {
                    Plot chestPlot = getPlotAt(world.getName(), bx, by, bz);
                    if (chestPlot != null && !chestPlot.isAuthorized(playerUuid, playerName)) {
                        // Do not allow an outsider bench to pull items from someone else's claimed plot
                        continue;
                    }
                    SimpleItemContainer container = containerBlock.getItemContainer();
                    if (container != null && !result.contains(container)) {
                        result.add(container);
                        if (result.size() >= limit) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Error querying nearby containers: " + e.getMessage());
        }
        return result;
    }
}

