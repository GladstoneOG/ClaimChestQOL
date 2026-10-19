package com.claimchestqol.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a claimed player plot in a specific world.
 * Supports:
 * - Bedrock-to-Ceiling (full vertical column) or Specific-Height (3D cuboid like /fill)
 * - Merging overlapping claims owned by the same player into a unified multi-box plot
 * - Activity timestamp tracking for optional plot decay
 */
public class Plot {
    /**
     * Represents a single cuboid sub-region within a Plot (a merged plot may contain multiple PlotBoxes).
     */
    public static class PlotBox {
        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private int minZ;
        private int maxZ;
        private boolean fullHeight = true;

        public PlotBox() {
        }

        public PlotBox(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, boolean fullHeight) {
            this.minX = Math.min(minX, maxX);
            this.maxX = Math.max(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.maxY = Math.max(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxZ = Math.max(minZ, maxZ);
            this.fullHeight = fullHeight;
        }

        public int getMinX() {
            return minX;
        }

        public int getMaxX() {
            return maxX;
        }

        public int getMinY() {
            return minY;
        }

        public int getMaxY() {
            return maxY;
        }

        public int getMinZ() {
            return minZ;
        }

        public int getMaxZ() {
            return maxZ;
        }

        public boolean isFullHeight() {
            return fullHeight;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && z >= minZ && z <= maxZ
                    && y >= minY && y <= maxY;
        }

        public boolean contains2D(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        public boolean isWithinBuffer(int x, int y, int z, int buffer) {
            return x >= (minX - buffer) && x <= (maxX + buffer)
                    && z >= (minZ - buffer) && z <= (maxZ + buffer)
                    && y >= (minY - buffer) && y <= (maxY + buffer);
        }

        public boolean intersects2D(int oMinX, int oMaxX, int oMinZ, int oMaxZ) {
            return this.minX <= oMaxX && this.maxX >= oMinX
                    && this.minZ <= oMaxZ && this.maxZ >= oMinZ;
        }

        public boolean intersects3D(int oMinX, int oMaxX, int oMinY, int oMaxY, int oMinZ, int oMaxZ) {
            return this.minX <= oMaxX && this.maxX >= oMinX
                    && this.minY <= oMaxY && this.maxY >= oMinY
                    && this.minZ <= oMaxZ && this.maxZ >= oMinZ;
        }

        public boolean containsBox(PlotBox other) {
            return other != null
                    && this.minX <= other.minX && this.maxX >= other.maxX
                    && this.minY <= other.minY && this.maxY >= other.maxY
                    && this.minZ <= other.minZ && this.maxZ >= other.maxZ;
        }
    }

    private UUID id;
    private String worldName;
    private UUID ownerUuid;
    private String ownerName;
    private int minX;
    private int maxX;
    private int minY;
    private int maxY;
    private int minZ;
    private int maxZ;
    private boolean fullHeight = true;
    private long lastActiveTimestampMs = System.currentTimeMillis();

    // Constituent sub-boxes for merged plots (always contains at least 1 box after normalization)
    private List<PlotBox> boxes = new ArrayList<>();

    // UUID string -> display username
    private Map<String, String> trustedUuidToName = new LinkedHashMap<>();
    // Lowercase usernames (allows trusting friends by username even if offline)
    private Set<String> trustedNamesLower = new LinkedHashSet<>();

    public Plot() {
    }

    public Plot(UUID id, String worldName, UUID ownerUuid, String ownerName,
                int x1, int y1, int z1, int x2, int y2, int z2) {
        this(id, worldName, ownerUuid, ownerName, x1, y1, z1, x2, y2, z2, true);
    }

    public Plot(UUID id, String worldName, UUID ownerUuid, String ownerName,
                int x1, int y1, int z1, int x2, int y2, int z2, boolean fullHeight) {
        this.id = id;
        this.worldName = worldName;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.minX = Math.min(x1, x2);
        this.maxX = Math.max(x1, x2);
        this.fullHeight = fullHeight;
        if (fullHeight) {
            // Protect full vertical column so underground bases & sky builds are fully covered
            this.minY = Math.min(-1024, Math.min(y1, y2) - 256);
            this.maxY = Math.max(1024, Math.max(y1, y2) + 256);
        } else {
            // Specific-Height (3D cuboid like Minecraft /fill command)
            this.minY = Math.min(y1, y2);
            this.maxY = Math.max(y1, y2);
        }
        this.minZ = Math.min(z1, z2);
        this.maxZ = Math.max(z1, z2);
        this.lastActiveTimestampMs = System.currentTimeMillis();
        this.boxes = new ArrayList<>();
        this.boxes.add(new PlotBox(this.minX, this.maxX, this.minY, this.maxY, this.minZ, this.maxZ, this.fullHeight));
    }

    /**
     * Ensures backwards compatibility with older plots.json files where boxes or lastActiveTimestampMs may be missing.
     */
    public void normalizeAfterLoad() {
        if (lastActiveTimestampMs <= 0L) {
            lastActiveTimestampMs = System.currentTimeMillis();
        }
        if (boxes == null) {
            boxes = new ArrayList<>();
        }
        if (boxes.isEmpty()) {
            boxes.add(new PlotBox(minX, maxX, minY, maxY, minZ, maxZ, fullHeight));
        } else {
            recalculateOuterBounds();
        }
        if (trustedUuidToName == null) {
            trustedUuidToName = new LinkedHashMap<>();
        }
        if (trustedNamesLower == null) {
            trustedNamesLower = new LinkedHashSet<>();
        }
    }

    public void recalculateOuterBounds() {
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        PlotBox first = boxes.get(0);
        int nMinX = first.minX;
        int nMaxX = first.maxX;
        int nMinY = first.minY;
        int nMaxY = first.maxY;
        int nMinZ = first.minZ;
        int nMaxZ = first.maxZ;
        boolean allFullHeight = first.fullHeight;

        for (int i = 1; i < boxes.size(); i++) {
            PlotBox b = boxes.get(i);
            if (b.minX < nMinX) nMinX = b.minX;
            if (b.maxX > nMaxX) nMaxX = b.maxX;
            if (b.minY < nMinY) nMinY = b.minY;
            if (b.maxY > nMaxY) nMaxY = b.maxY;
            if (b.minZ < nMinZ) nMinZ = b.minZ;
            if (b.maxZ > nMaxZ) nMaxZ = b.maxZ;
            if (!b.fullHeight) {
                allFullHeight = false;
            }
        }
        this.minX = nMinX;
        this.maxX = nMaxX;
        this.minY = nMinY;
        this.maxY = nMaxY;
        this.minZ = nMinZ;
        this.maxZ = nMaxZ;
        this.fullHeight = allFullHeight;
    }

    public List<PlotBox> getBoxes() {
        if (boxes == null || boxes.isEmpty()) {
            return Collections.singletonList(new PlotBox(minX, maxX, minY, maxY, minZ, maxZ, fullHeight));
        }
        return Collections.unmodifiableList(boxes);
    }

    public boolean isMergedMultiBox() {
        return boxes != null && boxes.size() > 1;
    }

    /**
     * Merges another Plot (and its boxes & trusted friends) into this Plot.
     */
    public void mergeWith(Plot other) {
        if (other == null || other == this) {
            return;
        }
        normalizeAfterLoad();
        other.normalizeAfterLoad();

        for (PlotBox box : other.getBoxes()) {
            addBoxInternal(box);
        }
        simplifyBoxes();
        recalculateOuterBounds();

        if (other.trustedUuidToName != null) {
            for (Map.Entry<String, String> entry : other.trustedUuidToName.entrySet()) {
                this.trustedUuidToName.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        if (other.trustedNamesLower != null) {
            this.trustedNamesLower.addAll(other.trustedNamesLower);
        }
        this.lastActiveTimestampMs = Math.max(this.lastActiveTimestampMs, other.lastActiveTimestampMs);
    }

    private void addBoxInternal(PlotBox candidate) {
        if (candidate == null) {
            return;
        }
        // Check if an existing box already completely contains candidate
        for (PlotBox existing : boxes) {
            if (existing.containsBox(candidate)) {
                return;
            }
        }
        // Remove any existing boxes that are completely contained inside candidate
        boxes.removeIf(candidate::containsBox);
        boxes.add(new PlotBox(
                candidate.minX, candidate.maxX,
                candidate.minY, candidate.maxY,
                candidate.minZ, candidate.maxZ,
                candidate.fullHeight
        ));
    }

    /**
     * Coalesces boxes that share identical bounds on 2 axes and overlap/touch on the 3rd axis.
     */
    private void simplifyBoxes() {
        if (boxes == null || boxes.size() <= 1) {
            return;
        }
        boolean mergedAny = true;
        while (mergedAny && boxes.size() > 1) {
            mergedAny = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    PlotBox a = boxes.get(i);
                    PlotBox b = boxes.get(j);
                    PlotBox combined = tryCombineExactCuboids(a, b);
                    if (combined != null) {
                        boxes.set(i, combined);
                        boxes.remove(j);
                        mergedAny = true;
                        break outer;
                    }
                }
            }
        }
    }

    private static PlotBox tryCombineExactCuboids(PlotBox a, PlotBox b) {
        if (a.containsBox(b)) return a;
        if (b.containsBox(a)) return b;
        if (a.fullHeight != b.fullHeight) {
            return null;
        }
        boolean sameY = a.fullHeight || (a.minY == b.minY && a.maxY == b.maxY);
        boolean sameX = (a.minX == b.minX && a.maxX == b.maxX);
        boolean sameZ = (a.minZ == b.minZ && a.maxZ == b.maxZ);

        // Merge along X if Y and Z match and X ranges touch or overlap
        if (sameY && sameZ && a.minX <= b.maxX + 1 && a.maxX >= b.minX - 1) {
            return new PlotBox(
                    Math.min(a.minX, b.minX), Math.max(a.maxX, b.maxX),
                    Math.min(a.minY, b.minY), Math.max(a.maxY, b.maxY),
                    a.minZ, a.maxZ, a.fullHeight
            );
        }
        // Merge along Z if Y and X match and Z ranges touch or overlap
        if (sameY && sameX && a.minZ <= b.maxZ + 1 && a.maxZ >= b.minZ - 1) {
            return new PlotBox(
                    a.minX, a.maxX,
                    Math.min(a.minY, b.minY), Math.max(a.maxY, b.maxY),
                    Math.min(a.minZ, b.minZ), Math.max(a.maxZ, b.maxZ),
                    a.fullHeight
            );
        }
        // Merge along Y if X and Z match and Y ranges touch or overlap
        if (!a.fullHeight && sameX && sameZ && a.minY <= b.maxY + 1 && a.maxY >= b.minY - 1) {
            return new PlotBox(
                    a.minX, a.maxX,
                    Math.min(a.minY, b.minY), Math.max(a.maxY, b.maxY),
                    a.minZ, a.maxZ, false
            );
        }
        return null;
    }

    /**
     * Computes the exact 2D horizontal area (union of X/Z columns) covered by all boxes in this plot.
     */
    public int calculateArea2D() {
        List<PlotBox> list = getBoxes();
        if (list.size() <= 1) {
            return getWidthX() * getLengthZ();
        }
        // Coordinate-compressed 2D rectangle union area
        List<Integer> xs = new ArrayList<>(list.size() * 2);
        List<Integer> zs = new ArrayList<>(list.size() * 2);
        for (PlotBox b : list) {
            xs.add(b.minX);
            xs.add(b.maxX + 1);
            zs.add(b.minZ);
            zs.add(b.maxZ + 1);
        }
        Collections.sort(xs);
        Collections.sort(zs);

        long totalArea = 0L;
        for (int xi = 0; xi < xs.size() - 1; xi++) {
            int x0 = xs.get(xi);
            int x1 = xs.get(xi + 1);
            if (x1 <= x0) continue;
            for (int zi = 0; zi < zs.size() - 1; zi++) {
                int z0 = zs.get(zi);
                int z1 = zs.get(zi + 1);
                if (z1 <= z0) continue;
                boolean covered = false;
                for (PlotBox b : list) {
                    if (x0 >= b.minX && x1 <= b.maxX + 1 && z0 >= b.minZ && z1 <= b.maxZ + 1) {
                        covered = true;
                        break;
                    }
                }
                if (covered) {
                    totalArea += (long) (x1 - x0) * (long) (z1 - z0);
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, totalArea);
    }

    public UUID getId() {
        return id;
    }

    public String getWorldName() {
        return worldName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public int getMinX() {
        return minX;
    }

    public int getMaxX() {
        return maxX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getMaxZ() {
        return maxZ;
    }

    public boolean isFullHeight() {
        return fullHeight;
    }

    public long getLastActiveTimestampMs() {
        return lastActiveTimestampMs <= 0L ? System.currentTimeMillis() : lastActiveTimestampMs;
    }

    public void touchActivity() {
        this.lastActiveTimestampMs = System.currentTimeMillis();
    }

    public boolean isExpired(long nowMs, long decayDurationMs) {
        if (decayDurationMs <= 0L) {
            return false;
        }
        long last = getLastActiveTimestampMs();
        return (nowMs - last) >= decayDurationMs;
    }

    public int getWidthX() {
        return (maxX - minX) + 1;
    }

    public int getHeightY() {
        return (maxY - minY) + 1;
    }

    public int getLengthZ() {
        return (maxZ - minZ) + 1;
    }

    public double getCenterX() {
        return (minX + maxX) * 0.5;
    }

    public double getCenterY() {
        return (minY + maxY) * 0.5;
    }

    public double getCenterZ() {
        return (minZ + maxZ) * 0.5;
    }

    public boolean contains(String world, int x, int y, int z) {
        if (world == null || !world.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        if (x < minX || x > maxX || z < minZ || z > maxZ || y < minY || y > maxY) {
            return false;
        }
        if (boxes == null || boxes.size() <= 1) {
            return true;
        }
        for (PlotBox box : boxes) {
            if (box.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean contains2D(String world, int x, int z) {
        if (world == null || !world.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return false;
        }
        if (boxes == null || boxes.size() <= 1) {
            return true;
        }
        for (PlotBox box : boxes) {
            if (box.contains2D(x, z)) {
                return true;
            }
        }
        return false;
    }

    public boolean isWithinBuffer(String world, int x, int y, int z, int buffer) {
        if (world == null || !world.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        if (x < (minX - buffer) || x > (maxX + buffer)
                || z < (minZ - buffer) || z > (maxZ + buffer)
                || y < (minY - buffer) || y > (maxY + buffer)) {
            return false;
        }
        if (boxes == null || boxes.size() <= 1) {
            return true;
        }
        for (PlotBox box : boxes) {
            if (box.isWithinBuffer(x, y, z, buffer)) {
                return true;
            }
        }
        return false;
    }

    public boolean intersects2D(String world, int oMinX, int oMaxX, int oMinZ, int oMaxZ) {
        if (world == null || !world.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        if (this.minX > oMaxX || this.maxX < oMinX || this.minZ > oMaxZ || this.maxZ < oMinZ) {
            return false;
        }
        if (boxes == null || boxes.size() <= 1) {
            return true;
        }
        for (PlotBox box : boxes) {
            if (box.intersects2D(oMinX, oMaxX, oMinZ, oMaxZ)) {
                return true;
            }
        }
        return false;
    }

    public boolean intersects3D(String world, int oMinX, int oMaxX, int oMinY, int oMaxY, int oMinZ, int oMaxZ) {
        if (world == null || !world.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        if (this.minX > oMaxX || this.maxX < oMinX
                || this.minY > oMaxY || this.maxY < oMinY
                || this.minZ > oMaxZ || this.maxZ < oMinZ) {
            return false;
        }
        if (boxes == null || boxes.size() <= 1) {
            return true;
        }
        for (PlotBox box : boxes) {
            if (box.intersects3D(oMinX, oMaxX, oMinY, oMaxY, oMinZ, oMaxZ)) {
                return true;
            }
        }
        return false;
    }

    public boolean intersectsPlot3D(Plot other) {
        if (other == null || other.worldName == null || !other.worldName.equalsIgnoreCase(this.worldName)) {
            return false;
        }
        for (PlotBox b : other.getBoxes()) {
            if (this.intersects3D(this.worldName, b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ)) {
                return true;
            }
        }
        return false;
    }

    public boolean isOwner(UUID playerUuid) {
        return playerUuid != null && playerUuid.equals(this.ownerUuid);
    }

    public boolean isAuthorized(UUID playerUuid, String playerName) {
        if (isOwner(playerUuid)) {
            return true;
        }
        if (playerUuid != null && trustedUuidToName != null && trustedUuidToName.containsKey(playerUuid.toString())) {
            return true;
        }
        if (playerName != null && trustedNamesLower != null && trustedNamesLower.contains(playerName.toLowerCase())) {
            if (playerUuid != null && trustedUuidToName != null) {
                trustedUuidToName.put(playerUuid.toString(), playerName);
            }
            return true;
        }
        return false;
    }

    public boolean trustFriend(UUID friendUuid, String friendName) {
        if (trustedUuidToName == null) {
            trustedUuidToName = new LinkedHashMap<>();
        }
        if (trustedNamesLower == null) {
            trustedNamesLower = new LinkedHashSet<>();
        }
        boolean added = false;
        if (friendUuid != null) {
            String prev = trustedUuidToName.put(friendUuid.toString(), friendName != null ? friendName : friendUuid.toString());
            if (prev == null) {
                added = true;
            }
        }
        if (friendName != null && !friendName.isBlank()) {
            if (trustedNamesLower.add(friendName.toLowerCase())) {
                added = true;
            }
        }
        return added;
    }

    public boolean untrustFriend(UUID friendUuid, String friendName) {
        boolean removed = false;
        if (trustedUuidToName != null && friendUuid != null) {
            String oldName = trustedUuidToName.remove(friendUuid.toString());
            if (oldName != null) {
                removed = true;
                if (trustedNamesLower != null) {
                    trustedNamesLower.remove(oldName.toLowerCase());
                }
            }
        }
        if (friendName != null && !friendName.isBlank()) {
            String lower = friendName.toLowerCase();
            if (trustedNamesLower != null && trustedNamesLower.remove(lower)) {
                removed = true;
            }
            if (trustedUuidToName != null) {
                var it = trustedUuidToName.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (entry.getValue() != null && entry.getValue().equalsIgnoreCase(friendName)) {
                        it.remove();
                        removed = true;
                    }
                }
            }
        }
        return removed;
    }

    public Collection<String> getTrustedDisplayNames() {
        Set<String> names = new LinkedHashSet<>();
        if (trustedUuidToName != null) {
            names.addAll(trustedUuidToName.values());
        }
        if (trustedNamesLower != null) {
            for (String lower : trustedNamesLower) {
                boolean alreadyPresent = names.stream().anyMatch(n -> n.equalsIgnoreCase(lower));
                if (!alreadyPresent) {
                    names.add(lower);
                }
            }
        }
        return Collections.unmodifiableCollection(names);
    }

    public String formatBounds() {
        String mergedTag = isMergedMultiBox() ? (" [Merged " + boxes.size() + " sections, Area: " + calculateArea2D() + "]") : "";
        if (fullHeight) {
            return String.format("(%d, %d) to (%d, %d) [%dx%d, Bedrock-to-Ceiling]%s",
                    minX, minZ, maxX, maxZ, getWidthX(), getLengthZ(), mergedTag);
        } else {
            return String.format("(%d, %d, %d) to (%d, %d, %d) [%dx%dx%d, Specific-Height]%s",
                    minX, minY, minZ, maxX, maxY, maxZ, getWidthX(), getHeightY(), getLengthZ(), mergedTag);
        }
    }
}

