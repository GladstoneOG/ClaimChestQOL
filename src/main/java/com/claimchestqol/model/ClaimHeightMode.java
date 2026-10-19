package com.claimchestqol.model;

/**
 * Defines how vertical height (Y-axis) is handled when claiming a plot.
 */
public enum ClaimHeightMode {
    BEDROCK_TO_CEILING(
            "Bedrock-to-Ceiling",
            "Full vertical column from bedrock to sky ceiling"
    ),
    SPECIFIC_HEIGHT(
            "Specific-Height (3D /fill)",
            "Exact 3D cuboid between Corner 1 (x1, y1, z1) and Corner 2 (x2, y2, z2)"
    );

    private final String displayName;
    private final String description;

    ClaimHeightMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public ClaimHeightMode toggle() {
        return this == BEDROCK_TO_CEILING ? SPECIFIC_HEIGHT : BEDROCK_TO_CEILING;
    }
}

