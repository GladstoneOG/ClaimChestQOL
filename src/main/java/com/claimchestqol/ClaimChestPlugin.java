package com.claimchestqol;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.protocol.packets.window.WindowType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.claimchestqol.command.PlotCommands;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.map.PlotWorldMapService;
import com.claimchestqol.protection.PlotProtectedFireFluidTicker;
import com.claimchestqol.protection.PlotProtectionSystems;
import com.claimchestqol.qol.PlotFieldCraftingWindow;
import com.claimchestqol.qol.PlotPlayerHookManager;
import com.claimchestqol.util.SelectionVisualizer;

import java.util.logging.Level;

/**
 * Main entrypoint for ClaimChestQOL:
 * - Two-corner plot claiming & undo via Claim Tool (/claim)
 * - Two claiming modes: Bedrock-to-Ceiling & Specific-Height (3D cuboid like /fill)
 * - Automatic plot merging when overlapping claims are made by the same player
 * - World Map claimed plot territory shading, borders, markers, and multi-height overlap stripes
 * - Server Admin Config (config.json & /plotconfig): plot decay, max plot size/count, excluded block types
 * - Live glowing green 3D selection outline that follows the player after placing Corner 1
 * - Plot protection from non-trusted players, mobs, explosions, and fire
 * - Friend trust system (/trust, /untrust, /trustlist)
 * - Plot-wide crafting from any chest in the plot (Crafting Benches & Pocket Crafting)
 * - Plot-wide Quick Stack by hijacking single-chest 'R' button when inside your plot
 */
public class ClaimChestPlugin extends JavaPlugin {
    private static ClaimChestPlugin instance;
    private PlotManager plotManager;

    public ClaimChestPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static ClaimChestPlugin get() {
        return instance;
    }

    public PlotManager getPlotManager() {
        return plotManager;
    }

    @Override
    protected void setup() {
        this.plotManager = new PlotManager(getDataDirectory());

        // 1. Register ECS Protection & Claim Tool Systems
        PlotProtectionSystems.registerAll(getEntityStoreRegistry(), plotManager);

        // 2. Register Player & Admin Commands (/claim, /unclaim, /trust, /untrust, /trustlist, /plotconfig)
        PlotCommands.registerAll(getCommandRegistry(), plotManager);

        // 3. Register PlayerReadyEvent to install PlotWindowManager, QuickStack interceptor, WorldMap hooks & refresh decay timer
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            Player player = event.getPlayer();
            var ref = event.getPlayerRef();
            if (player != null && ref != null && ref.isValid()) {
                PlayerRef playerRef = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
                PlotPlayerHookManager.ensureHooks(player, playerRef, plotManager);
                if (playerRef != null) {
                    plotManager.touchOwnerActivity(playerRef.getUuid());
                }
                PlotWorldMapService.installOnAllWorlds(plotManager);
            }
        });

        // 4. Re-install fire protection ticker whenever Fluid assets load or reload
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<LoadedAssetsEvent<String, Fluid, ?>> fluidLoadedClass = (Class) LoadedAssetsEvent.class;
        getEventRegistry().register(
                fluidLoadedClass,
                Fluid.class,
                event -> PlotProtectedFireFluidTicker.installOnAllFluids(plotManager)
        );

        getLogger().at(Level.INFO).log("[ClaimChestQOL] Setup complete! Loaded %d plot(s).", plotManager.getAllPlots().size());
    }

    @Override
    protected void start() {
        // Start live glowing green 3D selection visualizer
        SelectionVisualizer.start(plotManager);

        // Start periodic plot decay & world map hook maintenance task
        plotManager.startMaintenanceTask();

        // Install World Map plot territory & multi-height overlap renderer on all loaded worlds
        PlotWorldMapService.installOnAllWorlds(plotManager);

        // Install fire protection ticker on all loaded fire fluids
        PlotProtectedFireFluidTicker.installOnAllFluids(plotManager);

        // Enable plot-wide chest ingredients for Pocket Crafting (FieldCraftingWindow)
        Window.CLIENT_REQUESTABLE_WINDOW_TYPES.put(
                WindowType.PocketCrafting,
                () -> new PlotFieldCraftingWindow(plotManager)
        );

        // Hook any already-connected players
        try {
            if (Universe.get() != null && Universe.get().getPlayers() != null) {
                for (PlayerRef playerRef : Universe.get().getPlayers()) {
                    if (playerRef != null) {
                        Player player = playerRef.getComponent(Player.getComponentType());
                        PlotPlayerHookManager.ensureHooks(player, playerRef, plotManager);
                        plotManager.touchOwnerActivity(playerRef.getUuid());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        getLogger().at(Level.INFO).log("[ClaimChestQOL] Started! Plot claiming, plot merging, World Map territories, admin config, protection, plot-wide crafting, and Quick Stack (R) active.");
    }

    @Override
    protected void shutdown() {
        SelectionVisualizer.shutdown();
        if (plotManager != null) {
            plotManager.shutdownMaintenanceTask();
            plotManager.save();
            if (plotManager.getConfig() != null) {
                plotManager.getConfig().save();
            }
        }
        getLogger().at(Level.INFO).log("[ClaimChestQOL] Saved plots/config and shut down.");
    }
}

