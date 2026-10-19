package com.claimchestqol.map;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.protocol.packets.worldmap.MapMarker;
import com.hypixel.hytale.protocol.packets.worldmap.PlacedByMarkerComponent;
import com.hypixel.hytale.protocol.packets.worldmap.TintComponent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.palette.BitFieldArr;
import com.hypixel.hytale.server.core.universe.world.map.WorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.IWorldMap;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapSettings;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Renders claimed plot territories and multi-height overlapping plot zones on Hytale's in-game World Map:
 * 1. Wraps each World's IWorldMap generator to shade claimed plot areas and draw crisp borders on MapImage tiles.
 * 2. Draws a distinct diagonal striped dual-color pattern + Gold/Magenta border where 2+ plots overlap horizontally at different heights.
 * 3. Registers a WorldMapManager.MarkerProvider ("claimchestqol_claims") for plot owner labels and multi-height overlap markers.
 * 4. Invalidates affected map chunks live whenever plots are claimed, merged, or unclaimed.
 */
public final class PlotWorldMapService {
    private static final String MARKER_PROVIDER_ID = "claimchestqol_claims";

    // Vibrant territory RGB palette (R, G, B)
    private static final int[][] OWNER_PALETTE = {
            {40, 235, 95},    // Emerald Green
            {45, 205, 250},   // Sky Cyan
            {255, 180, 40},   // Amber Gold
            {250, 85, 95},    // Coral Red
            {165, 100, 255},  // Royal Purple
            {50, 235, 195},   // Mint Teal
            {255, 125, 50},   // Sunset Orange
            {180, 240, 55}    // Lime Chartreuse
    };

    // High-contrast colors for multi-height 2D plot overlap zones
    private static final int[] OVERLAP_MAGENTA = {255, 50, 215};
    private static final int[] OVERLAP_GOLD = {255, 215, 25};

    private PlotWorldMapService() {
    }

    /**
     * Ensures all currently loaded worlds have the PlotWorldMapWrapper and MarkerProvider installed.
     */
    public static void installOnAllWorlds(PlotManager plotManager) {
        if (plotManager == null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                installOnWorld(world, plotManager);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Installs the world map territory generator wrapper and marker provider on a specific World.
     */
    public static void installOnWorld(World world, PlotManager plotManager) {
        if (world == null || plotManager == null) {
            return;
        }
        try {
            WorldMapManager mapManager = world.getWorldMapManager();
            if (mapManager == null) {
                return;
            }

            // 1. Register MarkerProvider for plot labels and overlap indicators
            if (!mapManager.getMarkerProviders().containsKey(MARKER_PROVIDER_ID)) {
                mapManager.addMarkerProvider(MARKER_PROVIDER_ID, new PlotMarkerProvider(plotManager));
            }

            // 2. Wrap IWorldMap chunk image generator if not already wrapped
            IWorldMap currentGen = mapManager.getGenerator();
            if (currentGen != null && !(currentGen instanceof PlotWorldMapWrapper)) {
                PlotWorldMapWrapper wrapper = new PlotWorldMapWrapper(currentGen, plotManager);
                world.execute(() -> {
                    try {
                        IWorldMap latest = mapManager.getGenerator();
                        if (latest != null && !(latest instanceof PlotWorldMapWrapper)) {
                            mapManager.setGenerator(new PlotWorldMapWrapper(latest, plotManager));
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Invalidates the World Map chunk images covering the given Plot (plus a 1-chunk margin)
     * so all online players see updated territory borders immediately.
     */
    public static void invalidatePlotChunks(Plot plot, PlotManager plotManager) {
        if (plot == null) {
            return;
        }
        invalidateBoundsInWorld(
                plot.getWorldName(),
                plot.getMinX(), plot.getMaxX(),
                plot.getMinZ(), plot.getMaxZ(),
                plotManager
        );
    }

    public static void invalidateBoundsInWorld(String worldName,
                                               int minX, int maxX,
                                               int minZ, int maxZ,
                                               PlotManager plotManager) {
        if (worldName == null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world != null && worldName.equalsIgnoreCase(world.getName())) {
                    installOnWorld(world, plotManager);
                    int minChunkX = ChunkUtil.chunkCoordinate(minX) - 1;
                    int maxChunkX = ChunkUtil.chunkCoordinate(maxX) + 1;
                    int minChunkZ = ChunkUtil.chunkCoordinate(minZ) - 1;
                    int maxChunkZ = ChunkUtil.chunkCoordinate(maxZ) + 1;

                    LongSet chunkSet = new LongOpenHashSet();
                    for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                        for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                            chunkSet.add(ChunkUtil.indexChunk(cx, cz));
                        }
                    }

                    world.execute(() -> {
                        try {
                            WorldMapManager wmm = world.getWorldMapManager();
                            if (wmm != null) {
                                wmm.clearImagesInChunks(chunkSet);
                            }
                            List<Player> players = world.getPlayers();
                            if (players != null) {
                                for (Player p : players) {
                                    if (p != null && p.getWorldMapTracker() != null) {
                                        p.getWorldMapTracker().clearChunks(chunkSet);
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Clears and refreshes all World Map images across all worlds (e.g. when /plotconfig map is toggled).
     */
    public static void refreshAllWorldMaps(PlotManager plotManager) {
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world == null) continue;
                installOnWorld(world, plotManager);
                world.execute(() -> {
                    try {
                        WorldMapManager wmm = world.getWorldMapManager();
                        if (wmm != null) {
                            wmm.clearImages();
                        }
                        List<Player> players = world.getPlayers();
                        if (players != null) {
                            for (Player p : players) {
                                if (p != null && p.getWorldMapTracker() != null) {
                                    p.getWorldMapTracker().clear();
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    public static int[] getOwnerColorRgb(UUID ownerUuid) {
        if (ownerUuid == null) {
            return OWNER_PALETTE[0];
        }
        int idx = Math.floorMod(ownerUuid.hashCode(), OWNER_PALETTE.length);
        return OWNER_PALETTE[idx];
    }

    /**
     * Wraps a World's IWorldMap generator to render claimed plot territories and multi-height overlaps onto MapImage tiles.
     */
    public static class PlotWorldMapWrapper implements IWorldMap {
        private final IWorldMap delegate;
        private final PlotManager plotManager;

        public PlotWorldMapWrapper(IWorldMap delegate, PlotManager plotManager) {
            this.delegate = delegate;
            this.plotManager = plotManager;
        }

        @Override
        public WorldMapSettings getWorldMapSettings() {
            return delegate.getWorldMapSettings();
        }

        @Override
        public CompletableFuture<WorldMap> generate(World world, int imageWidth, int imageHeight, LongSet chunksToGenerate) {
            return delegate.generate(world, imageWidth, imageHeight, chunksToGenerate)
                    .thenApply(worldMap -> {
                        try {
                            if (worldMap != null && plotManager != null
                                    && plotManager.getConfig() != null
                                    && plotManager.getConfig().isShowPlotsOnWorldMap()) {
                                overlayPlotsOnWorldMap(world, worldMap, plotManager);
                            }
                        } catch (Throwable ignored) {
                        }
                        return worldMap;
                    });
        }

        @Override
        public CompletableFuture<Map<String, MapMarker>> generatePointsOfInterest(World world) {
            return delegate.generatePointsOfInterest(world);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    private static void overlayPlotsOnWorldMap(World world, WorldMap worldMap, PlotManager plotManager) {
        if (world == null || worldMap == null || worldMap.getChunks() == null || worldMap.getChunks().isEmpty()) {
            return;
        }
        String worldName = world.getName();
        List<Plot> worldPlots = new ArrayList<>();
        for (Plot p : plotManager.getAllPlots()) {
            if (p != null && worldName.equalsIgnoreCase(p.getWorldName())) {
                worldPlots.add(p);
            }
        }
        if (worldPlots.isEmpty()) {
            return;
        }

        for (Long2ObjectMap.Entry<MapImage> entry : worldMap.getChunks().long2ObjectEntrySet()) {
            long chunkIndex = entry.getLongKey();
            MapImage image = entry.getValue();
            if (image == null || image.palette == null || image.palette.length == 0) {
                continue;
            }

            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            int chunkMinX = ChunkUtil.minBlock(chunkX);
            int chunkMaxX = ChunkUtil.maxBlock(chunkX);
            int chunkMinZ = ChunkUtil.minBlock(chunkZ);
            int chunkMaxZ = ChunkUtil.maxBlock(chunkZ);

            // Filter plots that intersect this chunk (with 2-block margin for border detection)
            List<Plot> chunkPlots = new ArrayList<>(2);
            for (Plot plot : worldPlots) {
                if (plot.intersects2D(worldName, chunkMinX - 2, chunkMaxX + 2, chunkMinZ - 2, chunkMaxZ + 2)) {
                    chunkPlots.add(plot);
                }
            }
            if (chunkPlots.isEmpty()) {
                continue;
            }

            // Sort deterministically by minY then ID so multi-height overlap stripe order is stable
            chunkPlots.sort(Comparator.comparingInt(Plot::getMinY)
                    .thenComparing(p -> p.getId() != null ? p.getId().toString() : ""));

            MapImage modified = renderChunkOverlay(worldName, chunkMinX, chunkMinZ, image, chunkPlots);
            if (modified != null) {
                entry.setValue(modified);
            }
        }
    }

    private static MapImage renderChunkOverlay(String worldName,
                                               int chunkMinX,
                                               int chunkMinZ,
                                               MapImage image,
                                               List<Plot> chunkPlots) {
        int width = image.width;
        int height = image.height;
        int totalPixels = width * height;
        if (width <= 0 || height <= 0) {
            return null;
        }

        // 1. Decode existing MapImage pixels
        int[] pixels = new int[totalPixels];
        if (image.packedIndices != null && image.bitsPerIndex > 0) {
            BitFieldArr bitField = new BitFieldArr(image.bitsPerIndex & 0xFF, totalPixels);
            bitField.set(image.packedIndices);
            for (int i = 0; i < totalPixels; i++) {
                int pIdx = bitField.get(i);
                if (pIdx >= 0 && pIdx < image.palette.length) {
                    pixels[i] = image.palette[pIdx];
                } else {
                    pixels[i] = image.palette[0];
                }
            }
        } else {
            int baseColor = image.palette[0];
            for (int i = 0; i < totalPixels; i++) {
                pixels[i] = baseColor;
            }
        }

        boolean anyChanged = false;
        List<Plot> covering = new ArrayList<>(4);

        for (int iz = 0; iz < height; iz++) {
            int worldZ = chunkMinZ + (iz * ChunkUtil.SIZE) / height;
            for (int ix = 0; ix < width; ix++) {
                int worldX = chunkMinX + (ix * ChunkUtil.SIZE) / width;

                covering.clear();
                for (Plot p : chunkPlots) {
                    if (p.contains2D(worldName, worldX, worldZ)) {
                        covering.add(p);
                    }
                }
                if (covering.isEmpty()) {
                    continue;
                }

                int pixelIdx = iz * width + ix;
                int baseRgba = pixels[pixelIdx];

                if (covering.size() == 1) {
                    // Single plot at this (worldX, worldZ)
                    Plot plot = covering.get(0);
                    int[] rgb = getOwnerColorRgb(plot.getOwnerUuid());
                    boolean isBorder = isOnPlot2DBorder(worldName, plot, worldX, worldZ);
                    float alpha = isBorder ? 0.80f : 0.34f;
                    pixels[pixelIdx] = blendRgba(baseRgba, rgb[0], rgb[1], rgb[2], alpha);
                    anyChanged = true;
                } else {
                    // 2 or more plots overlap at this (worldX, worldZ) at different heights!
                    Plot plotA = covering.get(0);
                    Plot plotB = covering.get(1);

                    boolean isOverlapBorder = isOnOverlap2DBorder(worldName, covering, worldX, worldZ);
                    if (isOverlapBorder) {
                        // Alternating high-contrast Gold / Magenta border around the overlap zone
                        int[] borderRgb = (((worldX + worldZ) & 1) == 0) ? OVERLAP_GOLD : OVERLAP_MAGENTA;
                        pixels[pixelIdx] = blendRgba(baseRgba, borderRgb[0], borderRgb[1], borderRgb[2], 0.88f);
                    } else {
                        // Diagonal striped hatch inside the overlapping zone
                        boolean evenStripe = (((worldX + worldZ) >> 1) & 1) == 0;
                        int[] colorA = getOwnerColorRgb(plotA.getOwnerUuid());
                        int[] colorB = getOwnerColorRgb(plotB.getOwnerUuid());
                        if (plotA.getOwnerUuid() != null && plotA.getOwnerUuid().equals(plotB.getOwnerUuid())) {
                            // If same owner has two stacked plots at different heights, stripe Owner Color with Gold/Magenta
                            colorB = OVERLAP_MAGENTA;
                        }
                        int[] stripeRgb = evenStripe ? colorA : colorB;
                        pixels[pixelIdx] = blendRgba(baseRgba, stripeRgb[0], stripeRgb[1], stripeRgb[2], 0.52f);
                    }
                    anyChanged = true;
                }
            }
        }

        if (!anyChanged) {
            return null;
        }

        return encodePixelsToMapImage(width, height, pixels);
    }

    private static boolean isOnPlot2DBorder(String worldName, Plot plot, int x, int z) {
        // 2-block thick border so it remains crisp and visible on the World Map
        return !plot.contains2D(worldName, x - 1, z)
                || !plot.contains2D(worldName, x + 1, z)
                || !plot.contains2D(worldName, x, z - 1)
                || !plot.contains2D(worldName, x, z + 1)
                || !plot.contains2D(worldName, x - 2, z)
                || !plot.contains2D(worldName, x + 2, z)
                || !plot.contains2D(worldName, x, z - 2)
                || !plot.contains2D(worldName, x, z + 2);
    }

    private static boolean isOnOverlap2DBorder(String worldName, List<Plot> overlappingHere, int x, int z) {
        for (Plot p : overlappingHere) {
            if (!p.contains2D(worldName, x - 1, z)
                    || !p.contains2D(worldName, x + 1, z)
                    || !p.contains2D(worldName, x, z - 1)
                    || !p.contains2D(worldName, x, z + 1)) {
                return true;
            }
        }
        return false;
    }

    private static int blendRgba(int baseRgba, int overlayR, int overlayG, int overlayB, float alpha) {
        int baseR = (baseRgba >>> 24) & 0xFF;
        int baseG = (baseRgba >>> 16) & 0xFF;
        int baseB = (baseRgba >>> 8) & 0xFF;
        int baseA = baseRgba & 0xFF;
        if (baseA == 0) {
            baseA = 255;
        }

        float inv = 1.0f - alpha;
        int outR = Math.clamp(Math.round(baseR * inv + overlayR * alpha), 0, 255);
        int outG = Math.clamp(Math.round(baseG * inv + overlayG * alpha), 0, 255);
        int outB = Math.clamp(Math.round(baseB * inv + overlayB * alpha), 0, 255);

        return ((outR & 0xFF) << 24)
                | ((outG & 0xFF) << 16)
                | ((outB & 0xFF) << 8)
                | (baseA & 0xFF);
    }

    private static MapImage encodePixelsToMapImage(int width, int height, int[] pixels) {
        int totalPixels = width * height;
        Int2IntOpenHashMap colorToIndex = new Int2IntOpenHashMap(128);
        colorToIndex.defaultReturnValue(-1);
        IntArrayList paletteList = new IntArrayList(128);

        for (int i = 0; i < totalPixels; i++) {
            int color = pixels[i];
            if (colorToIndex.get(color) == -1) {
                int newIdx = paletteList.size();
                colorToIndex.put(color, newIdx);
                paletteList.add(color);
            }
        }

        int[] palette = paletteList.toIntArray();
        int bitsRequired = calculateBitsRequired(palette.length);
        BitFieldArr bitField = new BitFieldArr(bitsRequired, totalPixels);
        for (int i = 0; i < totalPixels; i++) {
            bitField.set(i, colorToIndex.get(pixels[i]));
        }

        return new MapImage(width, height, palette, (byte) bitsRequired, bitField.get());
    }

    private static int calculateBitsRequired(int uniqueColorCount) {
        if (uniqueColorCount <= 16) {
            return 4;
        }
        if (uniqueColorCount <= 256) {
            return 8;
        }
        if (uniqueColorCount <= 4096) {
            return 12;
        }
        return 16;
    }

    /**
     * Provides World Map markers for each claimed plot and for any multi-height 2D overlapping plot pairs.
     */
    public static class PlotMarkerProvider implements WorldMapManager.MarkerProvider {
        private final PlotManager plotManager;

        public PlotMarkerProvider(PlotManager plotManager) {
            this.plotManager = plotManager;
        }

        @Override
        public void update(World world, Player player, MarkersCollector collector) {
            if (world == null || collector == null || plotManager == null) {
                return;
            }
            if (plotManager.getConfig() != null && !plotManager.getConfig().isShowPlotsOnWorldMap()) {
                return;
            }

            try {
                String worldName = world.getName();
                List<Plot> worldPlots = new ArrayList<>();
                for (Plot plot : plotManager.getAllPlots()) {
                    if (plot != null && worldName.equalsIgnoreCase(plot.getWorldName())) {
                        worldPlots.add(plot);
                    }
                }
                if (worldPlots.isEmpty()) {
                    return;
                }

                // 1. Add a marker at the center of each claimed plot
                for (Plot plot : worldPlots) {
                    int[] rgb = getOwnerColorRgb(plot.getOwnerUuid());
                    String heightInfo = plot.isFullHeight()
                            ? (plot.getWidthX() + "x" + plot.getLengthZ())
                            : (plot.getWidthX() + "x" + plot.getLengthZ() + ", Y:" + plot.getMinY() + ".." + plot.getMaxY());
                    String mergedInfo = plot.isMergedMultiBox() ? " [Merged]" : "";
                    String label = plot.getOwnerName() + "'s Plot (" + heightInfo + ")" + mergedInfo;

                    double cx = plot.getCenterX();
                    double cy = plot.isFullHeight() ? 100.0 : plot.getCenterY();
                    double cz = plot.getCenterZ();

                    String markerId = "claimchestqol_plot_" + (plot.getId() != null ? plot.getId() : (plot.getMinX() + "_" + plot.getMinZ()));
                    MapMarkerBuilder builder = new MapMarkerBuilder(
                            markerId,
                            "Home.png",
                            new Transform(cx, cy, cz)
                    )
                            .withCustomName(label)
                            .withComponent(new TintComponent(new Color((byte) rgb[0], (byte) rgb[1], (byte) rgb[2])));

                    if (plot.getOwnerName() != null && plot.getOwnerUuid() != null) {
                        builder.withComponent(new PlacedByMarkerComponent(
                                Message.raw(plot.getOwnerName()).getFormattedMessage(),
                                plot.getOwnerUuid()
                        ));
                    }

                    collector.addIgnoreViewDistance(builder.build());
                }

                // 2. Detect pairs of plots that occupy the same 2D spot at different Y heights and add an Overlap Marker
                for (int i = 0; i < worldPlots.size(); i++) {
                    Plot a = worldPlots.get(i);
                    for (int j = i + 1; j < worldPlots.size(); j++) {
                        Plot b = worldPlots.get(j);
                        int ovMinX = Math.max(a.getMinX(), b.getMinX());
                        int ovMaxX = Math.min(a.getMaxX(), b.getMaxX());
                        int ovMinZ = Math.max(a.getMinZ(), b.getMinZ());
                        int ovMaxZ = Math.min(a.getMaxZ(), b.getMaxZ());

                        if (ovMinX <= ovMaxX && ovMinZ <= ovMaxZ
                                && a.intersects2D(worldName, b.getMinX(), b.getMaxX(), b.getMinZ(), b.getMaxZ())) {
                            double ovCx = (ovMinX + ovMaxX) * 0.5;
                            double ovCz = (ovMinZ + ovMaxZ) * 0.5;
                            String aY = a.isFullHeight() ? "Full" : ("Y:" + a.getMinY() + ".." + a.getMaxY());
                            String bY = b.isFullHeight() ? "Full" : ("Y:" + b.getMinY() + ".." + b.getMaxY());
                            String overlapLabel = "OVERLAP: " + a.getOwnerName() + " (" + aY + ") / "
                                    + b.getOwnerName() + " (" + bY + ")";
                            String overlapId = "claimchestqol_overlap_" + i + "_" + j;

                            MapMarker overlapMarker = new MapMarkerBuilder(
                                    overlapId,
                                    "Home.png",
                                    new Transform(ovCx, 110.0, ovCz)
                            )
                                    .withCustomName(overlapLabel)
                                    .withComponent(new TintComponent(new Color(
                                            (byte) OVERLAP_MAGENTA[0],
                                            (byte) OVERLAP_MAGENTA[1],
                                            (byte) OVERLAP_MAGENTA[2]
                                    )))
                                    .build();
                            collector.addIgnoreViewDistance(overlapMarker);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}

