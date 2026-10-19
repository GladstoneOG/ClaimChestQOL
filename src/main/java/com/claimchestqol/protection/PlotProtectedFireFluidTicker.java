package com.claimchestqol.protection;

import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy;
import com.hypixel.hytale.server.core.asset.type.fluid.FireFluidTicker;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Custom FireFluidTicker that immediately extinguishes fire inside or within 2 blocks
 * of any claimed plot, preventing fire spread and block burning in claimed plots.
 */
public class PlotProtectedFireFluidTicker extends FireFluidTicker {
    private final PlotManager plotManager;

    public PlotProtectedFireFluidTicker(FireFluidTicker original, PlotManager plotManager) {
        super();
        this.plotManager = plotManager;
        copyFields(original, this);
    }

    @Override
    public BlockTickStrategy process(World world,
                                     long tick,
                                     FluidTicker.Accessor accessor,
                                     FluidSection fluidSection,
                                     BlockSection blockSection,
                                     Fluid fluid,
                                     int fluidId,
                                     int worldX,
                                     int worldY,
                                     int worldZ) {
        if (world != null && plotManager != null) {
            // Check if the fire is inside or within 2 blocks of any claimed plot
            Plot nearPlot = plotManager.getPlotWithinBuffer(world.getName(), worldX, worldY, worldZ, 2);
            if (nearPlot != null) {
                fluidSection.setFluid(worldX, worldY, worldZ, 0, (byte) 0);
                setTickingSurrounding(accessor, blockSection, worldX, worldY, worldZ);
                return BlockTickStrategy.SLEEP;
            }
        }
        return super.process(world, tick, accessor, fluidSection, blockSection, fluid, fluidId, worldX, worldY, worldZ);
    }

    private static void copyFields(FireFluidTicker src, FireFluidTicker dst) {
        if (src == null || dst == null) {
            return;
        }
        Class<?> clazz = FireFluidTicker.class;
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(dst, field.get(src));
                } catch (Exception ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    public static void installOnAllFluids(PlotManager plotManager) {
        try {
            Field tickerField = Fluid.class.getDeclaredField("ticker");
            tickerField.setAccessible(true);

            var assetMap = Fluid.getAssetMap();
            if (assetMap == null) {
                return;
            }
            Map<String, Fluid> map = assetMap.getAssetMap();
            if (map != null) {
                for (Fluid fluid : map.values()) {
                    if (fluid == null) {
                        continue;
                    }
                    FluidTicker ticker = fluid.getTicker();
                    if (ticker instanceof FireFluidTicker fireTicker && !(ticker instanceof PlotProtectedFireFluidTicker)) {
                        tickerField.set(fluid, new PlotProtectedFireFluidTicker(fireTicker, plotManager));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ClaimChestQOL] Failed to install PlotProtectedFireFluidTicker: " + e.getMessage());
        }
    }
}

