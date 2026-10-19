package com.claimchestqol.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandRegistry;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.claimchestqol.config.PlotConfig;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.map.PlotWorldMapService;
import com.claimchestqol.model.ClaimHeightMode;
import com.claimchestqol.model.Plot;
import com.claimchestqol.protection.PlotProtectionSystems;
import com.claimchestqol.qol.PlotPlayerHookManager;
import com.claimchestqol.util.ClaimToolUtil;
import com.claimchestqol.util.SelectionVisualizer;
import org.joml.Vector3i;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Registers all player and admin commands for ClaimChestQOL:
 * - /claim (0 args -> gives Claim Tool)
 * - /claim <action> (1 positional arg: tool, bedrock, height, fill, switch, mode, pos1, pos2, show, undo, info, list)
 * - /claimbedrock (directly sets Bedrock-to-Ceiling mode)
 * - /claimfill / /claimheight (directly sets Specific-Height 3D cuboid /fill mode)
 * - /unclaim
 * - /trust <friend>
 * - /untrust <friend>
 * - /trustlist
 * - /plotconfig (aliases: /claimconfig, /plotadmin) for server admin settings & config.json management
 */
public final class PlotCommands {
    private PlotCommands() {
    }

    public static void registerAll(CommandRegistry registry, PlotManager plotManager) {
        registry.registerCommand(new ClaimCommand(plotManager));
        registry.registerCommand(new ClaimBedrockCommand(plotManager));
        registry.registerCommand(new ClaimFillHeightCommand(plotManager));
        registry.registerCommand(new UnclaimCommand(plotManager));
        registry.registerCommand(new TrustCommand(plotManager));
        registry.registerCommand(new UntrustCommand(plotManager));
        registry.registerCommand(new TrustListCommand(plotManager));
        registry.registerCommand(new PlotConfigCommand(plotManager));
    }

    private static Vector3i getPlayerBlockPos(PlayerRef playerRef) {
        Transform transform = playerRef.getTransform();
        if (transform == null || transform.getPosition() != null) {
            return SelectionVisualizer.getLivePlayerBlockPos(playerRef, 64);
        }
        return new Vector3i(0, 64, 0);
    }

    private static Plot findTargetOwnedPlot(PlotManager plotManager, PlayerRef playerRef, World world) {
        Vector3i pos = getPlayerBlockPos(playerRef);
        Plot standing = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
        if (standing != null && standing.isOwner(playerRef.getUuid())) {
            return standing;
        }
        List<Plot> owned = plotManager.getPlotsByOwner(playerRef.getUuid());
        if (!owned.isEmpty()) {
            return owned.get(owned.size() - 1);
        }
        return null;
    }

    /**
     * Base /claim command (0 arguments): gives the player the Plot Claim Tool.
     * Also registers a 1-argument positional usage variant so "/claim bedrock", "/claim fill",
     * "/claim height", "/claim info", "/claim undo", etc. work directly!
     */
    public static class ClaimCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public ClaimCommand(PlotManager plotManager) {
            super("claim", "Get the Plot Claim Tool or run /claim <bedrock|height|fill|pos1|pos2|show|undo|info|list>");
            this.plotManager = plotManager;
            this.addAliases("plot", "claimtool");
            this.requireNoPermission();
            this.addUsageVariant(new ClaimActionVariantCommand(plotManager));
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            executeClaimAction("tool", store, ref, playerRef, world, plotManager);
        }
    }

    /**
     * 1-argument positional usage variant for /claim <action>.
     */
    public static class ClaimActionVariantCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;
        private final RequiredArg<String> actionArg;

        public ClaimActionVariantCommand(PlotManager plotManager) {
            super("Run a /claim action: bedrock, height, fill, switch, tool, mode, pos1, pos2, show, undo, info, list");
            this.plotManager = plotManager;
            this.requireNoPermission();
            this.actionArg = this.withRequiredArg(
                    "action",
                    "Action: bedrock, height, fill, switch, tool, mode, pos1, pos2, show, undo, info, list",
                    ArgTypes.STRING
            );
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            String action = context.get(actionArg);
            executeClaimAction(action, store, ref, playerRef, world, plotManager);
        }
    }

    /**
     * Direct shortcut command: /claimbedrock
     */
    public static class ClaimBedrockCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public ClaimBedrockCommand(PlotManager plotManager) {
            super("claimbedrock", "Set plot claiming mode to Bedrock-to-Ceiling (full vertical column)");
            this.plotManager = plotManager;
            this.requireNoPermission();
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            executeClaimAction("bedrock", store, ref, playerRef, world, plotManager);
        }
    }

    /**
     * Direct shortcut command: /claimfill (aliases: /claimheight, /claim3d)
     */
    public static class ClaimFillHeightCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public ClaimFillHeightCommand(PlotManager plotManager) {
            super("claimfill", "Set plot claiming mode to Specific-Height (3D cuboid like Minecraft /fill)");
            this.plotManager = plotManager;
            this.addAliases("claimheight", "claim3d");
            this.requireNoPermission();
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            executeClaimAction("fill", store, ref, playerRef, world, plotManager);
        }
    }

    private static void executeClaimAction(String action,
                                           Store<EntityStore> store,
                                           Ref<EntityStore> ref,
                                           PlayerRef playerRef,
                                           World world,
                                           PlotManager plotManager) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PlotPlayerHookManager.ensureHooks(player, playerRef, plotManager);
        PlotWorldMapService.installOnWorld(world, plotManager);

        if (action == null || action.isBlank()) {
            action = "tool";
        }
        action = action.trim().toLowerCase();

        switch (action) {
            case "tool", "wand", "get", "stick" -> ClaimToolUtil.giveClaimTool(ref, store, playerRef, plotManager);
            case "bedrock", "full", "column", "vertical", "sky" -> {
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.heightMode = ClaimHeightMode.BEDROCK_TO_CEILING;
                ClaimToolUtil.sendMsg(playerRef,
                        "Claim Mode set to: " + sel.heightMode.getDisplayName() + " (" + sel.heightMode.getDescription() + ")",
                        "#55FF55");
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
            }
            case "height", "specific", "3d", "cuboid", "fill", "box" -> {
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.heightMode = ClaimHeightMode.SPECIFIC_HEIGHT;
                ClaimToolUtil.sendMsg(playerRef,
                        "Claim Mode set to: " + sel.heightMode.getDisplayName() + " (" + sel.heightMode.getDescription() + ")",
                        "#55FF55");
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
            }
            case "switch", "heightmode", "switchmode" -> {
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.heightMode = sel.heightMode.toggle();
                ClaimToolUtil.sendMsg(playerRef,
                        "Claim Mode switched to: " + sel.heightMode.getDisplayName() + " (" + sel.heightMode.getDescription() + ")",
                        "#55FF55");
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
            }
            case "mode", "hand", "toggle" -> {
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.toolModeEnabled = !sel.toolModeEnabled;
                if (sel.toolModeEnabled) {
                    ClaimToolUtil.sendMsg(playerRef,
                            "Claim Tool Mode ENABLED! Left-Click = Corner 1, Right-Click/Use = Corner 2 (or Undo). Current Height Mode: "
                                    + sel.heightMode.getDisplayName(),
                            "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef, "Claim Tool Mode DISABLED.", "#FFAA00");
                }
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
            }
            case "pos1", "1", "corner1" -> {
                Vector3i pos = getPlayerBlockPos(playerRef);
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                sel.worldName = world.getName();
                sel.pos1 = pos;
                sel.pos2 = null;
                sel.pos1TimestampMs = System.currentTimeMillis();
                SelectionVisualizer.forceRefresh(playerRef, plotManager);
                ClaimToolUtil.sendMsg(playerRef,
                        String.format("Corner 1 set at (%d, %d, %d) [Mode: %s]. Walk to Corner 2 (green outline follows you) and run /claim pos2 or Right-Click!",
                                pos.x, pos.y, pos.z, sel.heightMode.getDisplayName()),
                        "#55FFFF");
            }
            case "pos2", "2", "corner2" -> {
                Vector3i pos = getPlayerBlockPos(playerRef);
                PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
                if (sel.pos1 == null || !world.getName().equalsIgnoreCase(sel.worldName)) {
                    ClaimToolUtil.sendMsg(playerRef, "Please set Corner 1 first (using the Claim Tool or /claim pos1)!", "#FF5555");
                    return;
                }
                sel.pos2 = pos;
                PlotManager.ClaimOutcome outcome = plotManager.claimPlot(
                        playerRef.getUuid(), playerRef.getUsername(), world.getName(), sel.pos1, sel.pos2, sel.heightMode);
                if (outcome.success()) {
                    sel.clearCorners();
                    SelectionVisualizer.showClaimedPlot(playerRef, outcome.plot(), 5.0f);
                    int chests = plotManager.getContainersInPlot(world, outcome.plot(), null).size();
                    ClaimToolUtil.sendMsg(playerRef, outcome.message() + " | Connected Chests: " + chests, "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef, outcome.message(), "#FF5555");
                }
            }
            case "show", "view", "border", "outline", "highlight" -> {
                Vector3i pos = getPlayerBlockPos(playerRef);
                Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
                if (plot == null) {
                    plot = findTargetOwnedPlot(plotManager, playerRef, world);
                }
                if (plot == null) {
                    ClaimToolUtil.sendMsg(playerRef, "No plot found to highlight. Claim a plot first with /claim!", "#FF5555");
                    return;
                }
                SelectionVisualizer.showClaimedPlot(playerRef, plot, 10.0f);
                if (plot.isOwner(playerRef.getUuid())) {
                    ClaimToolUtil.sendMsg(playerRef,
                            "Highlighting your plot " + plot.formatBounds() + " in glowing green for 10 seconds!",
                            "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef,
                            "Highlighting " + plot.getOwnerName() + "'s plot " + plot.formatBounds() + " in glowing red/yellow for 10 seconds!",
                            "#FFAA00");
                }
            }
            case "undo", "unclaim", "remove", "delete", "clear" -> handleUndoClaim(plotManager, playerRef, world);
            case "info", "status", "here" -> handlePlotInfo(plotManager, playerRef, world);
            case "list", "all" -> {
                List<Plot> owned = plotManager.getPlotsByOwner(playerRef.getUuid());
                if (owned.isEmpty()) {
                    ClaimToolUtil.sendMsg(playerRef, "You do not own any plots yet. Run /claim to get the Claim Tool!", "#FFAA00");
                    return;
                }
                int maxPlots = plotManager.getConfig().getMaxPlotsPerPlayer();
                String limitStr = maxPlots > 0 ? (owned.size() + "/" + maxPlots) : String.valueOf(owned.size());
                ClaimToolUtil.sendMsg(playerRef, "Your claimed plots (" + limitStr + "):", "#55FFFF");
                for (int i = 0; i < owned.size(); i++) {
                    Plot p = owned.get(i);
                    ClaimToolUtil.sendMsg(playerRef,
                            String.format(" #%d [%s] %s | Trusted: %s",
                                    i + 1, p.getWorldName(), p.formatBounds(),
                                    p.getTrustedDisplayNames().isEmpty() ? "None" : String.join(", ", p.getTrustedDisplayNames())),
                            "#FFFFFF");
                }
            }
            case "config", "settings", "admin" -> printConfigSummary(playerRef, plotManager);
            default -> {
                ClaimToolUtil.sendMsg(playerRef, "Usage: /claim [tool | bedrock | height | fill | switch | pos1 | pos2 | show | undo | info | list]", "#FFFF55");
                ClaimToolUtil.sendMsg(playerRef, "Modes: /claim bedrock (Full Height) | /claim fill or /claim height (3D Cuboid /fill)", "#55FF55");
                ClaimToolUtil.sendMsg(playerRef, "Admin Config: /plotconfig [info | decay | maxplots | maxsize | maxarea | maxheight | exclude | include | map | reload]", "#AAAAAA");
            }
        }
    }

    public static class UnclaimCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public UnclaimCommand(PlotManager plotManager) {
            super("unclaim", "Undo / unclaim the plot you are standing in (or your most recent plot)");
            this.plotManager = plotManager;
            this.requireNoPermission();
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            handleUndoClaim(plotManager, playerRef, world);
        }
    }

    public static class TrustCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;
        private final RequiredArg<String> friendArg;

        public TrustCommand(PlotManager plotManager) {
            super("trust", "Trust a friend on your plot so they can build, open chests, craft, and quick-stack");
            this.plotManager = plotManager;
            this.requireNoPermission();
            this.friendArg = this.withRequiredArg("friend", "Friend's username to trust", ArgTypes.STRING);
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            String targetName = context.get(friendArg);
            if (targetName == null || targetName.isBlank()) {
                ClaimToolUtil.sendMsg(playerRef, "Usage: /trust <friendUsername>", "#FF5555");
                return;
            }
            targetName = targetName.trim();

            List<Plot> ownedPlots = plotManager.getPlotsByOwner(playerRef.getUuid());
            if (ownedPlots.isEmpty()) {
                ClaimToolUtil.sendMsg(playerRef, "You don't have a claimed plot yet! Use /claim to claim one first.", "#FF5555");
                return;
            }

            PlayerRef onlineFriend = Universe.get().getPlayerByUsername(targetName, NameMatching.EXACT_IGNORE_CASE);
            UUID friendUuid = onlineFriend != null ? onlineFriend.getUuid() : null;
            String resolvedName = onlineFriend != null ? onlineFriend.getUsername() : targetName;

            if (friendUuid != null && friendUuid.equals(playerRef.getUuid())) {
                ClaimToolUtil.sendMsg(playerRef, "You are already the owner of your plot!", "#FFAA00");
                return;
            }

            Vector3i pos = getPlayerBlockPos(playerRef);
            Plot standingPlot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
            if (standingPlot != null && standingPlot.isOwner(playerRef.getUuid())) {
                standingPlot.trustFriend(friendUuid, resolvedName);
            } else {
                for (Plot p : ownedPlots) {
                    p.trustFriend(friendUuid, resolvedName);
                }
            }
            plotManager.save();

            ClaimToolUtil.sendMsg(playerRef,
                    "Trusted " + resolvedName + " on your plot! They now have full build, crafting, and quick-stack access.",
                    "#55FF55");
            if (onlineFriend != null) {
                ClaimToolUtil.sendMsg(onlineFriend,
                        playerRef.getUsername() + " trusted you on their plot! You can now build, craft from all plot chests, and press R in any chest to quick-stack across the plot.",
                        "#55FF55");
            }
        }
    }

    public static class UntrustCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;
        private final RequiredArg<String> friendArg;

        public UntrustCommand(PlotManager plotManager) {
            super("untrust", "Remove a friend from your plot's trusted players list");
            this.plotManager = plotManager;
            this.requireNoPermission();
            this.friendArg = this.withRequiredArg("friend", "Friend's username to untrust", ArgTypes.STRING);
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            String targetName = context.get(friendArg);
            if (targetName == null || targetName.isBlank()) {
                ClaimToolUtil.sendMsg(playerRef, "Usage: /untrust <friendUsername>", "#FF5555");
                return;
            }
            targetName = targetName.trim();

            List<Plot> ownedPlots = plotManager.getPlotsByOwner(playerRef.getUuid());
            if (ownedPlots.isEmpty()) {
                ClaimToolUtil.sendMsg(playerRef, "You don't own any plots.", "#FF5555");
                return;
            }

            PlayerRef onlineFriend = Universe.get().getPlayerByUsername(targetName, NameMatching.EXACT_IGNORE_CASE);
            UUID friendUuid = onlineFriend != null ? onlineFriend.getUuid() : null;

            boolean anyRemoved = false;
            for (Plot p : ownedPlots) {
                if (p.untrustFriend(friendUuid, targetName)) {
                    anyRemoved = true;
                }
            }
            if (anyRemoved) {
                plotManager.save();
                ClaimToolUtil.sendMsg(playerRef, "Removed " + targetName + " from your plot's trusted list.", "#FFAA00");
            } else {
                ClaimToolUtil.sendMsg(playerRef, targetName + " was not on your plot's trusted list.", "#FF5555");
            }
        }
    }

    public static class TrustListCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public TrustListCommand(PlotManager plotManager) {
            super("trustlist", "View trusted friends and info for your plot");
            this.plotManager = plotManager;
            this.requireNoPermission();
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            handlePlotInfo(plotManager, playerRef, world);
        }
    }

    /**
     * Server Admin configuration command: /plotconfig (aliases: /claimconfig, /plotadmin)
     * Supports 0 args (show config), 1 arg (reload / excludelist / info), and 2 args (setting + value).
     */
    public static class PlotConfigCommand extends AbstractPlayerCommand {
        private final PlotManager plotManager;

        public PlotConfigCommand(PlotManager plotManager) {
            super("plotconfig", "View or modify ClaimChestQOL server settings (decay, size/count limits, excluded blocks, world map)");
            this.plotManager = plotManager;
            this.addAliases("claimconfig", "plotadmin");
            this.requireNoPermission();
            this.addUsageVariant(new PlotConfigOneArgVariant(plotManager));
            this.addUsageVariant(new PlotConfigTwoArgVariant(plotManager));
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            printConfigSummary(playerRef, plotManager);
        }
    }

    public static class PlotConfigOneArgVariant extends AbstractPlayerCommand {
        private final PlotManager plotManager;
        private final RequiredArg<String> subCommandArg;

        public PlotConfigOneArgVariant(PlotManager plotManager) {
            super("Run /plotconfig <info|reload|excludelist>");
            this.plotManager = plotManager;
            this.requireNoPermission();
            this.subCommandArg = this.withRequiredArg("subcommand", "info, reload, or excludelist", ArgTypes.STRING);
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            String sub = context.get(subCommandArg);
            executeConfigCommand(sub, null, store, ref, playerRef, world, plotManager);
        }
    }

    public static class PlotConfigTwoArgVariant extends AbstractPlayerCommand {
        private final PlotManager plotManager;
        private final RequiredArg<String> settingArg;
        private final RequiredArg<String> valueArg;

        public PlotConfigTwoArgVariant(PlotManager plotManager) {
            super("Run /plotconfig <decay|maxplots|maxsize|maxarea|maxheight|exclude|include|map> <value>");
            this.plotManager = plotManager;
            this.requireNoPermission();
            this.settingArg = this.withRequiredArg(
                    "setting",
                    "Setting: decay, maxplots, maxsize, maxarea, maxheight, exclude, include, map",
                    ArgTypes.STRING
            );
            this.valueArg = this.withRequiredArg(
                    "value",
                    "New value (e.g. on/off, number, block ID/pattern, hand, or look)",
                    ArgTypes.STRING
            );
        }

        @Override
        public boolean hasPermission(CommandSender sender) {
            return true;
        }

        @Override
        protected void execute(CommandContext context,
                               Store<EntityStore> store,
                               Ref<EntityStore> ref,
                               PlayerRef playerRef,
                               World world) {
            String setting = context.get(settingArg);
            String value = context.get(valueArg);
            executeConfigCommand(setting, value, store, ref, playerRef, world, plotManager);
        }
    }

    private static void executeConfigCommand(String settingRaw,
                                             String valueRaw,
                                             Store<EntityStore> store,
                                             Ref<EntityStore> ref,
                                             PlayerRef playerRef,
                                             World world,
                                             PlotManager plotManager) {
        PlotConfig cfg = plotManager.getConfig();
        if (settingRaw == null || settingRaw.isBlank()) {
            printConfigSummary(playerRef, plotManager);
            return;
        }
        String setting = settingRaw.trim().toLowerCase(Locale.ROOT);
        String val = valueRaw != null ? valueRaw.trim() : null;

        switch (setting) {
            case "info", "status", "list", "show" -> printConfigSummary(playerRef, plotManager);
            case "reload" -> {
                cfg.reload();
                PlotWorldMapService.refreshAllWorldMaps(plotManager);
                ClaimToolUtil.sendMsg(playerRef, "Reloaded config.json and refreshed World Map territories!", "#55FF55");
                printConfigSummary(playerRef, plotManager);
            }
            case "excludelist", "excluded", "blocks" -> {
                List<String> excluded = cfg.getExcludedBlockTypes();
                if (excluded.isEmpty()) {
                    ClaimToolUtil.sendMsg(playerRef, "No block types are currently excluded from plot protection.", "#FFAA00");
                } else {
                    ClaimToolUtil.sendMsg(playerRef, "Excluded Block Types (" + excluded.size() + "): " + String.join(", ", excluded), "#55FFFF");
                }
            }
            case "decay", "plotdecay" -> {
                if (val == null || val.isBlank()) {
                    ClaimToolUtil.sendMsg(playerRef,
                            "Usage: /plotconfig decay <on | off | hours> (e.g. /plotconfig decay on, /plotconfig decay 168)",
                            "#FFFF55");
                    return;
                }
                String vLower = val.toLowerCase(Locale.ROOT);
                if (vLower.equals("on") || vLower.equals("true") || vLower.equals("enable") || vLower.equals("enabled")) {
                    cfg.setPlotDecayEnabled(true);
                    cfg.save();
                    ClaimToolUtil.sendMsg(playerRef,
                            "Plot Decay ENABLED! Inactive plots will expire after " + cfg.getPlotDecayHours() + " hours.",
                            "#55FF55");
                } else if (vLower.equals("off") || vLower.equals("false") || vLower.equals("disable") || vLower.equals("disabled")) {
                    cfg.setPlotDecayEnabled(false);
                    cfg.save();
                    ClaimToolUtil.sendMsg(playerRef, "Plot Decay DISABLED.", "#FFAA00");
                } else {
                    try {
                        double hours;
                        if (vLower.endsWith("d")) {
                            hours = Double.parseDouble(vLower.substring(0, vLower.length() - 1)) * 24.0;
                        } else if (vLower.endsWith("h")) {
                            hours = Double.parseDouble(vLower.substring(0, vLower.length() - 1));
                        } else {
                            hours = Double.parseDouble(vLower);
                        }
                        cfg.setPlotDecayHours(hours);
                        cfg.setPlotDecayEnabled(true);
                        cfg.save();
                        ClaimToolUtil.sendMsg(playerRef,
                                String.format("Plot Decay set to %.2f hours (%.1f days) and ENABLED!", hours, hours / 24.0),
                                "#55FF55");
                    } catch (NumberFormatException e) {
                        ClaimToolUtil.sendMsg(playerRef, "Invalid decay value. Use 'on', 'off', or hours (e.g. 72 or 7d).", "#FF5555");
                    }
                }
            }
            case "maxplots", "maxplot", "plotlimit" -> {
                Integer num = parseIntArg(playerRef, val, "/plotconfig maxplots <count> (0 for unlimited)");
                if (num == null) return;
                cfg.setMaxPlotsPerPlayer(num);
                cfg.save();
                ClaimToolUtil.sendMsg(playerRef,
                        "Max Plots Per Player set to: " + (num <= 0 ? "Unlimited" : num),
                        "#55FF55");
            }
            case "maxsize", "maxside", "maxwidth", "maxlength" -> {
                Integer num = parseIntArg(playerRef, val, "/plotconfig maxsize <maxSideBlocks> (0 for unlimited)");
                if (num == null) return;
                cfg.setMaxPlotSideLength(num);
                cfg.save();
                ClaimToolUtil.sendMsg(playerRef,
                        "Max Plot Side Length (X/Z) set to: " + (num <= 0 ? "Unlimited" : (num + " blocks")),
                        "#55FF55");
            }
            case "maxarea", "area" -> {
                Integer num = parseIntArg(playerRef, val, "/plotconfig maxarea <maxArea2D> (0 for unlimited)");
                if (num == null) return;
                cfg.setMaxPlotArea2D(num);
                cfg.save();
                ClaimToolUtil.sendMsg(playerRef,
                        "Max Plot 2D Area set to: " + (num <= 0 ? "Unlimited" : (num + " blocks")),
                        "#55FF55");
            }
            case "maxheight", "height" -> {
                Integer num = parseIntArg(playerRef, val, "/plotconfig maxheight <maxHeightY> (0 for unlimited)");
                if (num == null) return;
                cfg.setMaxPlotHeightY(num);
                cfg.save();
                ClaimToolUtil.sendMsg(playerRef,
                        "Max Specific-Height (3D /fill) Y Height set to: " + (num <= 0 ? "Unlimited" : (num + " blocks")),
                        "#55FF55");
            }
            case "exclude", "excludeblock", "unprotect" -> {
                String pattern = resolveBlockTargetOrPattern(val, store, ref, world, playerRef);
                if (pattern == null) return;
                if (cfg.addExcludedBlockType(pattern)) {
                    cfg.save();
                    ClaimToolUtil.sendMsg(playerRef,
                            "Excluded block type/pattern '" + pattern + "' from plot protection! Anyone can now interact with or break matching blocks.",
                            "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef,
                            "Block type/pattern '" + pattern + "' is already in the exclusion list.",
                            "#FFAA00");
                }
            }
            case "include", "includeblock", "protect" -> {
                String pattern = resolveBlockTargetOrPattern(val, store, ref, world, playerRef);
                if (pattern == null) return;
                if (cfg.removeExcludedBlockType(pattern)) {
                    cfg.save();
                    ClaimToolUtil.sendMsg(playerRef,
                            "Removed '" + pattern + "' from the exclusion list (now protected inside plots again).",
                            "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef,
                            "'" + pattern + "' was not found in the excluded block list.",
                            "#FF5555");
                }
            }
            case "map", "worldmap" -> {
                if (val == null || val.isBlank()) {
                    ClaimToolUtil.sendMsg(playerRef, "Usage: /plotconfig map <on | off | refresh>", "#FFFF55");
                    return;
                }
                String vLower = val.toLowerCase(Locale.ROOT);
                if (vLower.equals("on") || vLower.equals("true") || vLower.equals("enable")) {
                    cfg.setShowPlotsOnWorldMap(true);
                    cfg.save();
                    PlotWorldMapService.refreshAllWorldMaps(plotManager);
                    ClaimToolUtil.sendMsg(playerRef, "World Map Plot Territory display ENABLED and map refreshed!", "#55FF55");
                } else if (vLower.equals("off") || vLower.equals("false") || vLower.equals("disable")) {
                    cfg.setShowPlotsOnWorldMap(false);
                    cfg.save();
                    PlotWorldMapService.refreshAllWorldMaps(plotManager);
                    ClaimToolUtil.sendMsg(playerRef, "World Map Plot Territory display DISABLED and map refreshed.", "#FFAA00");
                } else if (vLower.equals("refresh") || vLower.equals("reload")) {
                    PlotWorldMapService.refreshAllWorldMaps(plotManager);
                    ClaimToolUtil.sendMsg(playerRef, "Refreshed all World Map plot territory chunks!", "#55FF55");
                } else {
                    ClaimToolUtil.sendMsg(playerRef, "Usage: /plotconfig map <on | off | refresh>", "#FF5555");
                }
            }
            default -> {
                ClaimToolUtil.sendMsg(playerRef, "=== ClaimChest Admin Config Commands ===", "#55FFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig info                     - View all current settings", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig decay <on|off|hours>     - Toggle plot decay or set hours (e.g. 168 or 7d)", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig maxplots <count>         - Max plots per player (0 = unlimited)", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig maxsize <blocks>         - Max X/Z side length per plot (0 = unlimited)", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig maxarea <blocks2D>       - Max 2D area per plot (0 = unlimited)", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig maxheight <blocksY>      - Max height in /claimfill mode (0 = unlimited)", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig exclude <id|hand|look>   - Exclude block type/wildcard from protection", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig include <id|hand|look>   - Re-enable protection for block type", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig map <on|off|refresh>     - Toggle or refresh World Map territories", "#FFFFFF");
                ClaimToolUtil.sendMsg(playerRef, " /plotconfig reload                   - Reload config.json from disk", "#FFFFFF");
            }
        }
    }

    private static Integer parseIntArg(PlayerRef playerRef, String val, String usage) {
        if (val == null || val.isBlank()) {
            ClaimToolUtil.sendMsg(playerRef, "Usage: " + usage, "#FFFF55");
            return null;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            ClaimToolUtil.sendMsg(playerRef, "Invalid integer '" + val + "'. Usage: " + usage, "#FF5555");
            return null;
        }
    }

    private static String resolveBlockTargetOrPattern(String val,
                                                      Store<EntityStore> store,
                                                      Ref<EntityStore> ref,
                                                      World world,
                                                      PlayerRef playerRef) {
        if (val == null || val.isBlank()) {
            ClaimToolUtil.sendMsg(playerRef,
                    "Specify a block ID/pattern (e.g. *Door*, *Crop*), 'hand' (held item), or 'look' (block you're looking at).",
                    "#FFFF55");
            return null;
        }
        String trimmed = val.trim();
        if (trimmed.equalsIgnoreCase("hand") || trimmed.equalsIgnoreCase("held")) {
            ItemStack held = InventoryComponent.getItemInHand(store, ref);
            if (held == null || held.isEmpty()) {
                ClaimToolUtil.sendMsg(playerRef, "You are not holding any block/item in your hand!", "#FF5555");
                return null;
            }
            return held.getItemId();
        }
        if (trimmed.equalsIgnoreCase("look") || trimmed.equalsIgnoreCase("target")) {
            try {
                Vector3i targetPos = TargetUtil.getTargetBlock(ref, 6.0, store);
                if (targetPos != null) {
                    String blockId = PlotProtectionSystems.getBlockTypeIdAt(world, targetPos.x, targetPos.y, targetPos.z);
                    if (blockId != null && !blockId.equalsIgnoreCase("Empty")) {
                        return blockId;
                    }
                }
            } catch (Exception ignored) {
            }
            ClaimToolUtil.sendMsg(playerRef, "Could not find a valid block where you are looking!", "#FF5555");
            return null;
        }
        return trimmed;
    }

    private static void printConfigSummary(PlayerRef playerRef, PlotManager plotManager) {
        PlotConfig cfg = plotManager.getConfig();
        List<String> excluded = cfg.getExcludedBlockTypes();
        ClaimToolUtil.sendMsg(playerRef, "=== ClaimChestQOL Server Configuration (config.json) ===", "#55FFFF");
        ClaimToolUtil.sendMsg(playerRef,
                String.format(" Plot Decay: %s (Duration: %.1f hours / %.1f days)",
                        cfg.isPlotDecayEnabled() ? "ACTIVE" : "INACTIVE",
                        cfg.getPlotDecayHours(),
                        cfg.getPlotDecayHours() / 24.0),
                cfg.isPlotDecayEnabled() ? "#55FF55" : "#FFAA00");
        ClaimToolUtil.sendMsg(playerRef,
                " Max Plots Per Player: " + (cfg.getMaxPlotsPerPlayer() <= 0 ? "Unlimited" : cfg.getMaxPlotsPerPlayer()),
                "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef,
                " Max Plot Side Length (X/Z): " + (cfg.getMaxPlotSideLength() <= 0 ? "Unlimited" : (cfg.getMaxPlotSideLength() + " blocks")),
                "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef,
                " Max Plot 2D Area: " + (cfg.getMaxPlotArea2D() <= 0 ? "Unlimited" : (cfg.getMaxPlotArea2D() + " blocks")),
                "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef,
                " Max Specific-Height (Y): " + (cfg.getMaxPlotHeightY() <= 0 ? "Unlimited" : (cfg.getMaxPlotHeightY() + " blocks")),
                "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef,
                " Show Plots on World Map: " + (cfg.isShowPlotsOnWorldMap() ? "ENABLED" : "DISABLED"),
                cfg.isShowPlotsOnWorldMap() ? "#55FF55" : "#FFAA00");
        ClaimToolUtil.sendMsg(playerRef,
                " Excluded Block Types (" + excluded.size() + "): " + (excluded.isEmpty() ? "None" : String.join(", ", excluded)),
                "#FFFF55");
    }

    private static void handleUndoClaim(PlotManager plotManager, PlayerRef playerRef, World world) {
        PlotManager.CornerSelection sel = plotManager.getSelection(playerRef.getUuid());
        boolean hadPendingSelection = (sel.pos1 != null || sel.pos2 != null);
        sel.clearCorners();

        Plot target = findTargetOwnedPlot(plotManager, playerRef, world);
        if (target != null) {
            plotManager.removePlot(target);
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            ClaimToolUtil.sendMsg(playerRef,
                    "Plot claim undone! Removed your plot at " + target.formatBounds() + ".",
                    "#FFAA00");
        } else if (hadPendingSelection) {
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            ClaimToolUtil.sendMsg(playerRef,
                    "Cleared pending Corner 1 selection and glowing outline.",
                    "#FFAA00");
        } else {
            SelectionVisualizer.forceRefresh(playerRef, plotManager);
            ClaimToolUtil.sendMsg(playerRef,
                    "You do not have an active plot claim to undo.",
                    "#FF5555");
        }
    }

    private static void handlePlotInfo(PlotManager plotManager, PlayerRef playerRef, World world) {
        Vector3i pos = getPlayerBlockPos(playerRef);
        Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
        List<Plot> columnPlots = plotManager.getPlotsAt2D(world.getName(), pos.x, pos.z);
        if (plot == null) {
            if (!columnPlots.isEmpty()) {
                plot = columnPlots.get(0);
            } else {
                plot = findTargetOwnedPlot(plotManager, playerRef, world);
            }
        }
        if (plot == null) {
            ClaimToolUtil.sendMsg(playerRef, "No plot found at your location (and you don't own any plots yet). Use /claim to get the Claim Tool!", "#FFAA00");
            return;
        }
        SelectionVisualizer.showClaimedPlot(playerRef, plot, 8.0f);
        int chests = plotManager.getContainersInPlot(world, plot, null).size();
        Collection<String> trusted = plot.getTrustedDisplayNames();
        ClaimToolUtil.sendMsg(playerRef, "=== Plot Info ===", "#55FFFF");
        ClaimToolUtil.sendMsg(playerRef, " Owner: " + plot.getOwnerName(), "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef, " Bounds: " + plot.formatBounds() + " in world '" + plot.getWorldName() + "'", "#FFFFFF");
        ClaimToolUtil.sendMsg(playerRef, " Height Mode: " + (plot.isFullHeight() ? "Bedrock-to-Ceiling (Full Vertical)" : "Specific-Height (3D Cuboid)")
                + (plot.isMergedMultiBox() ? (" | Merged Sections: " + plot.getBoxes().size()) : ""), "#55FFFF");
        ClaimToolUtil.sendMsg(playerRef, " Trusted Friends: " + (trusted.isEmpty() ? "None" : String.join(", ", trusted)), "#55FF55");
        ClaimToolUtil.sendMsg(playerRef, " Connected Chests in Plot: " + chests, "#FFFF55");

        if (plotManager.getConfig().isPlotDecayEnabled()) {
            long elapsedMs = Math.max(0L, System.currentTimeMillis() - plot.getLastActiveTimestampMs());
            double remainingHours = Math.max(0.0, plotManager.getConfig().getPlotDecayHours() - (elapsedMs / 3600_000.0));
            ClaimToolUtil.sendMsg(playerRef,
                    String.format(" Plot Decay Timer: %.1f hours remaining before expiry", remainingHours),
                    "#FFAA00");
        }

        if (columnPlots.size() > 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < columnPlots.size(); i++) {
                Plot cp = columnPlots.get(i);
                if (i > 0) sb.append(" | ");
                sb.append(cp.getOwnerName()).append(" (Y:").append(cp.getMinY()).append("..").append(cp.getMaxY()).append(")");
            }
            ClaimToolUtil.sendMsg(playerRef, " Stacked Plots at this X/Z: " + sb, "#FF55FF");
        }
    }
}

