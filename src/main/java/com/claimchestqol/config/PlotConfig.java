package com.claimchestqol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server administrator configuration for ClaimChestQOL, persisted in config.json.
 * Supports:
 * - Plot decay active/inactive and decay duration (in hours)
 * - Maximum plot side length, 2D area, vertical height, and max plots per player
 * - Excluded block types (wildcards, substrings, or exact IDs) that bypass plot protection
 * - World map territory & overlap rendering toggle
 */
public class PlotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path configFile;

    // 1. Plot Decay Settings
    private boolean plotDecayEnabled = false;
    private double plotDecayHours = 168.0; // 7 days default when enabled

    // 2. Plot Size & Count Limits (<= 0 means unlimited)
    private int maxPlotsPerPlayer = 5;
    private int maxPlotSideLength = 128;
    private int maxPlotArea2D = 16384; // 128 x 128
    private int maxPlotHeightY = 256;

    // 3. Excluded Block Types (blocks that are NOT protected inside plots)
    private List<String> excludedBlockTypes = new CopyOnWriteArrayList<>();

    // 4. World Map Territory Display
    private boolean showPlotsOnWorldMap = true;

    public PlotConfig() {
    }

    public static PlotConfig loadOrCreate(Path dataDirectory) {
        Path file = dataDirectory.resolve("config.json");
        PlotConfig config = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config = GSON.fromJson(reader, PlotConfig.class);
            } catch (Exception e) {
                System.err.println("[ClaimChestQOL] Failed to read config.json, using defaults: " + e.getMessage());
            }
        }
        if (config == null) {
            config = new PlotConfig();
        }
        config.configFile = file;
        config.normalize();
        config.save();
        return config;
    }

    public synchronized void reload() {
        if (configFile == null || !Files.exists(configFile)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            PlotConfig loaded = GSON.fromJson(reader, PlotConfig.class);
            if (loaded != null) {
                this.plotDecayEnabled = loaded.plotDecayEnabled;
                this.plotDecayHours = loaded.plotDecayHours;
                this.maxPlotsPerPlayer = loaded.maxPlotsPerPlayer;
                this.maxPlotSideLength = loaded.maxPlotSideLength;
                this.maxPlotArea2D = loaded.maxPlotArea2D;
                this.maxPlotHeightY = loaded.maxPlotHeightY;
                this.showPlotsOnWorldMap = loaded.showPlotsOnWorldMap;
                this.excludedBlockTypes = new CopyOnWriteArrayList<>(
                        loaded.excludedBlockTypes != null ? loaded.excludedBlockTypes : Collections.emptyList()
                );
                normalize();
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to reload config.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        if (configFile == null) {
            return;
        }
        try {
            if (configFile.getParent() != null) {
                Files.createDirectories(configFile.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                GSON.toJson(this, PlotConfig.class, writer);
            }
        } catch (IOException e) {
            System.err.println("[ClaimChestQOL] Failed to save config.json: " + e.getMessage());
        }
    }

    private void normalize() {
        if (plotDecayHours <= 0.0) {
            plotDecayHours = 168.0;
        }
        if (excludedBlockTypes == null) {
            excludedBlockTypes = new CopyOnWriteArrayList<>();
        } else if (!(excludedBlockTypes instanceof CopyOnWriteArrayList)) {
            excludedBlockTypes = new CopyOnWriteArrayList<>(excludedBlockTypes);
        }
    }

    public boolean isPlotDecayEnabled() {
        return plotDecayEnabled;
    }

    public void setPlotDecayEnabled(boolean plotDecayEnabled) {
        this.plotDecayEnabled = plotDecayEnabled;
    }

    public double getPlotDecayHours() {
        return plotDecayHours;
    }

    public void setPlotDecayHours(double plotDecayHours) {
        this.plotDecayHours = Math.max(0.01, plotDecayHours);
    }

    public long getPlotDecayDurationMs() {
        return (long) (plotDecayHours * 3600_000.0);
    }

    public int getMaxPlotsPerPlayer() {
        return maxPlotsPerPlayer;
    }

    public void setMaxPlotsPerPlayer(int maxPlotsPerPlayer) {
        this.maxPlotsPerPlayer = maxPlotsPerPlayer;
    }

    public int getMaxPlotSideLength() {
        return maxPlotSideLength;
    }

    public void setMaxPlotSideLength(int maxPlotSideLength) {
        this.maxPlotSideLength = maxPlotSideLength;
    }

    public int getMaxPlotArea2D() {
        return maxPlotArea2D;
    }

    public void setMaxPlotArea2D(int maxPlotArea2D) {
        this.maxPlotArea2D = maxPlotArea2D;
    }

    public int getMaxPlotHeightY() {
        return maxPlotHeightY;
    }

    public void setMaxPlotHeightY(int maxPlotHeightY) {
        this.maxPlotHeightY = maxPlotHeightY;
    }

    public boolean isShowPlotsOnWorldMap() {
        return showPlotsOnWorldMap;
    }

    public void setShowPlotsOnWorldMap(boolean showPlotsOnWorldMap) {
        this.showPlotsOnWorldMap = showPlotsOnWorldMap;
    }

    public List<String> getExcludedBlockTypes() {
        return Collections.unmodifiableList(new ArrayList<>(excludedBlockTypes));
    }

    public boolean addExcludedBlockType(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String trimmed = pattern.trim();
        for (String existing : excludedBlockTypes) {
            if (existing.equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        excludedBlockTypes.add(trimmed);
        return true;
    }

    public boolean removeExcludedBlockType(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String trimmed = pattern.trim();
        return excludedBlockTypes.removeIf(existing -> existing.equalsIgnoreCase(trimmed));
    }

    /**
     * Checks whether a given block type ID is excluded from plot protection.
     * Supports:
     * - Exact case-insensitive match (e.g. "Soil_Grass")
     * - Wildcards with '*' (e.g. "*Crop*", "*Door*", "Plant_*")
     * - Case-insensitive substring match if pattern is prefixed/suffixed or matches token
     */
    public boolean isBlockExcluded(String blockTypeId) {
        if (blockTypeId == null || blockTypeId.isEmpty() || excludedBlockTypes == null || excludedBlockTypes.isEmpty()) {
            return false;
        }
        String idLower = blockTypeId.toLowerCase(Locale.ROOT);
        if ("empty".equals(idLower)) {
            return false;
        }
        for (String rawPattern : excludedBlockTypes) {
            if (rawPattern == null || rawPattern.isBlank()) {
                continue;
            }
            String pat = rawPattern.trim().toLowerCase(Locale.ROOT);
            if (pat.equals(idLower)) {
                return true;
            }
            if (pat.contains("*")) {
                if (wildcardMatch(idLower, pat)) {
                    return true;
                }
            } else if (idLower.contains(pat)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcardMatch(String text, String pattern) {
        String[] parts = pattern.split("\\*", -1);
        int pos = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            int idx = text.indexOf(part, pos);
            if (idx == -1) {
                return false;
            }
            if (i == 0 && !pattern.startsWith("*") && idx != 0) {
                return false;
            }
            pos = idx + part.length();
        }
        if (!pattern.endsWith("*") && parts.length > 0) {
            String last = parts[parts.length - 1];
            return text.endsWith(last);
        }
        return true;
    }
}

