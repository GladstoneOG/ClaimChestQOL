package com.claimchestqol.protection;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionChainData;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionEntry;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.event.InteractionChainStartEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.claimchestqol.config.PlotConfig;
import com.claimchestqol.manager.PlotManager;
import com.claimchestqol.model.Plot;
import com.claimchestqol.qol.PlotPlayerHookManager;
import com.claimchestqol.util.ClaimToolUtil;
import com.claimchestqol.util.SelectionVisualizer;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECS Event Systems enforcing plot protection (blocks, chests, benches, doors, entities, explosions, mobs),
 * respecting excluded block types in PlotConfig, and handling Claim Tool corner selection & undo interactions.
 */
public final class PlotProtectionSystems {
    private static final Map<UUID, Long> LAST_DENIED_MSG_MS = new ConcurrentHashMap<>();

    private PlotProtectionSystems() {
    }

    public static void registerAll(ComponentRegistryProxy<EntityStore> registry, PlotManager plotManager) {
        registry.registerSystem(new InteractionChainStartSystem(plotManager));
        registry.registerSystem(new DamageBlockSystem(plotManager));
        registry.registerSystem(new WorldDamageBlockSystem(plotManager));
        registry.registerSystem(new BreakBlockSystem(plotManager));
        registry.registerSystem(new WorldBreakBlockSystem(plotManager));
        registry.registerSystem(new PlaceBlockSystem(plotManager));
        registry.registerSystem(new UseBlockPreSystem(plotManager));
        registry.registerSystem(new UseEntityPreSystem(plotManager));
    }

    /**
     * Looks up the BlockType ID at (x, y, z) in the given World, or null if unavailable.
     */
    public static String getBlockTypeIdAt(World world, int x, int y, int z) {
        if (world == null) {
            return null;
        }
        try {
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return null;
            }
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return null;
            }
            BlockSection blockSection = chunkStore.getStore().getComponent(sectionRef, BlockSection.getComponentType());
            if (blockSection == null) {
                return null;
            }
            int blockId = blockSection.get(ChunkUtil.indexBlock(x, y, z));
            BlockType bt = BlockType.getAssetMap().getAsset(blockId);
            return bt != null ? bt.getId() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isExcludedBlock(PlotManager plotManager, BlockType blockType, World world, int x, int y, int z) {
        if (plotManager == null) {
            return false;
        }
        PlotConfig cfg = plotManager.getConfig();
        if (cfg == null || cfg.getExcludedBlockTypes().isEmpty()) {
            return false;
        }
        if (blockType != null && cfg.isBlockExcluded(blockType.getId())) {
            return true;
        }
        String worldBlockId = getBlockTypeIdAt(world, x, y, z);
        return worldBlockId != null && cfg.isBlockExcluded(worldBlockId);
    }

    /**
     * Notifies the player in chat who owns the plot they tried to break/interact with,
     * and briefly highlights the plot's boundary in Red/Yellow so they can see its edges.
     */
    private static void sendDeniedMessageRateLimited(PlayerRef playerRef, Plot plot, String actionDescription) {
        if (playerRef == null || plot == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_DENIED_MSG_MS.get(playerRef.getUuid());
        if (last != null && (now - last) < 900L) {
            return;
        }
        LAST_DENIED_MSG_MS.put(playerRef.getUuid(), now);
        String action = (actionDescription != null && !actionDescription.isEmpty()) ? actionDescription : "interact";
        ClaimToolUtil.sendMsg(
                playerRef,
                "You cannot " + action + " here! This plot is owned by " + plot.getOwnerName()
                        + " (ask them to run /trust " + playerRef.getUsername() + ").",
                "#FF5555"
        );
        SelectionVisualizer.showClaimedPlot(playerRef, plot, 3.5f);
    }

    /**
     * Intercepts the start of interaction chains for:
     * 1. Claim Tool Left-Click (Corner 1 / Mode Toggle) and Right-Click / Use (Corner 2 / Undo).
     * 2. Preventing unauthorized block/fluid breaking or interactions inside another player's plot
     *    and notifying the player in chat who owns the plot.
     */
    public static class InteractionChainStartSystem extends EntityEventSystem<EntityStore, InteractionChainStartEvent> {
        private final PlotManager plotManager;

        public InteractionChainStartSystem(PlotManager plotManager) {
            super(InteractionChainStartEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           InteractionChainStartEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                Player player = chunk.getComponent(index, Player.getComponentType());
                if (player != null && playerRef != null) {
                    PlotPlayerHookManager.ensureHooks(player, playerRef, plotManager);
                }

                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }

                InteractionContext ctx = event.getContext();
                InteractionChainData chainData = (event.getChain() != null) ? event.getChain().getChainData() : null;
                BlockPosition targetPos = null;
                if (ctx != null) {
                    targetPos = ctx.getTargetBlock();
                    if (targetPos == null) {
                        targetPos = ctx.getTargetBlockRaw();
                    }
                    // Guard ctx.getEntry() != null because entry is null before the first interaction ticks!
                    if (targetPos == null && ctx.getEntry() != null) {
                        InteractionSyncData clientState = ctx.getClientState();
                        if (clientState != null) {
                            targetPos = clientState.blockPosition;
                        }
                    }
                }
                if (targetPos == null && chainData != null) {
                    targetPos = chainData.blockPosition;
                }
                if (targetPos == null && event.getChain() != null) {
                    InteractionEntry entry0 = event.getChain().getInteraction(0);
                    if (entry0 != null && entry0.getClientState() != null) {
                        targetPos = entry0.getClientState().blockPosition;
                    }
                }

                InteractionType type = event.getType();
                if (playerRef != null) {
                    ItemStack held = ctx != null ? ctx.getHeldItem() : null;
                    if (held == null || held.isEmpty()) {
                        held = InventoryComponent.getItemInHand(store, ref);
                    }

                    // Check Claim Tool usage (supports clicking blocks OR clicking in the air)
                    if (ClaimToolUtil.isUsingClaimTool(playerRef.getUuid(), held, plotManager)) {
                        if (type == InteractionType.Primary || type == InteractionType.Secondary || type == InteractionType.Use) {
                            int clickX;
                            int clickY;
                            int clickZ;
                            if (targetPos != null) {
                                clickX = targetPos.x;
                                clickY = targetPos.y;
                                clickZ = targetPos.z;
                            } else {
                                Vector3i rayBlock = null;
                                try {
                                    rayBlock = TargetUtil.getTargetBlock(ref, 5.5, store);
                                } catch (Exception ignored) {
                                }
                                if (rayBlock != null) {
                                    clickX = rayBlock.x;
                                    clickY = rayBlock.y;
                                    clickZ = rayBlock.z;
                                } else {
                                    Vector3i playerPos = SelectionVisualizer.getLivePlayerBlockPos(playerRef, 64);
                                    clickX = playerPos.x;
                                    clickY = playerPos.y;
                                    clickZ = playerPos.z;
                                }
                            }

                            boolean isLeftClick = (type == InteractionType.Primary);
                            if (ClaimToolUtil.handleToolClick(
                                    ref, store, playerRef, world,
                                    clickX, clickY, clickZ,
                                    isLeftClick, plotManager)) {
                                event.setCancelled(true);
                                return;
                            }
                        }
                    }
                }

                // If targetPos was not populated yet in the root interaction packet (e.g. bare-hand/weapon punch or F-key use),
                // raycast the block in front of the player when not targeting an entity.
                boolean targetingEntity = (ctx != null && ctx.getTargetEntity() != null)
                        || (chainData != null && chainData.entityId > 0);
                Vector3i checkBlock = null;
                if (targetPos != null) {
                    checkBlock = new Vector3i(targetPos.x, targetPos.y, targetPos.z);
                } else if (playerRef != null && !targetingEntity) {
                    if (type == InteractionType.Primary
                            || type == InteractionType.Use
                            || type == InteractionType.Pick
                            || type == InteractionType.Pickup) {
                        try {
                            checkBlock = TargetUtil.getTargetBlock(ref, 4.8, store);
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (checkBlock == null) {
                    return;
                }

                // Check plot protection on target block
                Plot plot = plotManager.getPlotAt(world.getName(), checkBlock.x, checkBlock.y, checkBlock.z);
                if (plot != null) {
                    // Check if block type is excluded from plot protection in config.json
                    if (isExcludedBlock(plotManager, null, world, checkBlock.x, checkBlock.y, checkBlock.z)) {
                        return;
                    }
                    if (playerRef == null) {
                        if (type == InteractionType.Primary || type == InteractionType.Secondary || type == InteractionType.Use) {
                            event.setCancelled(true);
                        }
                        return;
                    }
                    if (plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (!plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        if (type == InteractionType.Primary
                                || type == InteractionType.Secondary
                                || type == InteractionType.Use
                                || type == InteractionType.Pick
                                || type == InteractionType.Pickup) {
                            event.setCancelled(true);
                            String action = (type == InteractionType.Primary) ? "break blocks" : "interact";
                            sendDeniedMessageRateLimited(playerRef, plot, action);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects blocks inside plots from being damaged by unauthorized players, mobs, or entity explosions,
     * and handles Claim Tool left-click selection.
     */
    public static class DamageBlockSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {
        private final PlotManager plotManager;

        public DamageBlockSystem(PlotManager plotManager) {
            super(DamageBlockEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           DamageBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());

                if (playerRef != null) {
                    ItemStack held = event.getItemInHand();
                    if (held == null || held.isEmpty()) {
                        held = InventoryComponent.getItemInHand(store, ref);
                    }
                    if (ClaimToolUtil.isUsingClaimTool(playerRef.getUuid(), held, plotManager)) {
                        ClaimToolUtil.handleToolClick(ref, store, playerRef, world, pos.x, pos.y, pos.z, true, plotManager);
                        event.setCancelled(true);
                        return;
                    }
                }

                Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
                if (plot != null) {
                    if (isExcludedBlock(plotManager, event.getBlockType(), world, pos.x, pos.y, pos.z)) {
                        return;
                    }
                    if (playerRef == null) {
                        // Block damage from non-player entity (e.g. mob or explosion)
                        event.setCancelled(true);
                        return;
                    }
                    if (plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (!plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        event.setCancelled(true);
                        sendDeniedMessageRateLimited(playerRef, plot, "break blocks");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects blocks inside plots from entity-less DamageBlockEvents (e.g. world/block explosions).
     */
    public static class WorldDamageBlockSystem extends WorldEventSystem<EntityStore, DamageBlockEvent> {
        private final PlotManager plotManager;

        public WorldDamageBlockSystem(PlotManager plotManager) {
            super(DamageBlockEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, DamageBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world != null && plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z) != null) {
                    if (!isExcludedBlock(plotManager, event.getBlockType(), world, pos.x, pos.y, pos.z)) {
                        event.setCancelled(true);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects blocks inside plots from being broken by unauthorized players, mobs, or entity explosions.
     */
    public static class BreakBlockSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {
        private final PlotManager plotManager;

        public BreakBlockSystem(PlotManager plotManager) {
            super(BreakBlockEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           BreakBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());

                if (playerRef != null) {
                    ItemStack held = event.getItemInHand();
                    if (held == null || held.isEmpty()) {
                        held = InventoryComponent.getItemInHand(store, ref);
                    }
                    if (ClaimToolUtil.isUsingClaimTool(playerRef.getUuid(), held, plotManager)) {
                        ClaimToolUtil.handleToolClick(ref, store, playerRef, world, pos.x, pos.y, pos.z, true, plotManager);
                        event.setCancelled(true);
                        return;
                    }
                }

                Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
                if (plot != null) {
                    if (isExcludedBlock(plotManager, event.getBlockType(), world, pos.x, pos.y, pos.z)) {
                        return;
                    }
                    if (playerRef == null) {
                        event.setCancelled(true);
                        return;
                    }
                    if (plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (!plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        event.setCancelled(true);
                        sendDeniedMessageRateLimited(playerRef, plot, "break blocks");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects blocks inside plots from entity-less BreakBlockEvents (e.g. world/block explosions).
     */
    public static class WorldBreakBlockSystem extends WorldEventSystem<EntityStore, BreakBlockEvent> {
        private final PlotManager plotManager;

        public WorldBreakBlockSystem(PlotManager plotManager) {
            super(BreakBlockEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, BreakBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world != null && plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z) != null) {
                    if (!isExcludedBlock(plotManager, event.getBlockType(), world, pos.x, pos.y, pos.z)) {
                        event.setCancelled(true);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects plots from unauthorized block placement and handles Claim Tool right-click when holding a block.
     */
    public static class PlaceBlockSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {
        private final PlotManager plotManager;

        public PlaceBlockSystem(PlotManager plotManager) {
            super(PlaceBlockEvent.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           PlaceBlockEvent event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());

                ItemStack held = event.getItemInHand();
                if (playerRef != null) {
                    if (held == null || held.isEmpty()) {
                        held = InventoryComponent.getItemInHand(store, ref);
                    }
                    if (ClaimToolUtil.isUsingClaimTool(playerRef.getUuid(), held, plotManager)) {
                        ClaimToolUtil.handleToolClick(ref, store, playerRef, world, pos.x, pos.y, pos.z, false, plotManager);
                        event.setCancelled(true);
                        return;
                    }
                }

                Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
                if (plot != null) {
                    // Check if the block being placed is in excludedBlockTypes
                    if (held != null && !held.isEmpty() && plotManager.getConfig() != null
                            && plotManager.getConfig().isBlockExcluded(held.getItemId())) {
                        return;
                    }
                    if (playerRef != null && plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (playerRef == null || !plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        event.setCancelled(true);
                        sendDeniedMessageRateLimited(playerRef, plot, "place blocks");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects chests, crafting benches, doors, and interactive blocks inside plots from unauthorized players,
     * and ensures QoL hooks (PlotWindowManager & QuickStack packet interceptor) are active before any window opens.
     */
    public static class UseBlockPreSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
        private final PlotManager plotManager;

        public UseBlockPreSystem(PlotManager plotManager) {
            super(UseBlockEvent.Pre.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           UseBlockEvent.Pre event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Player player = chunk.getComponent(index, Player.getComponentType());
                PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                if (player != null && playerRef != null) {
                    PlotPlayerHookManager.ensureHooks(player, playerRef, plotManager);
                }

                Vector3i pos = event.getTargetBlock();
                if (pos == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }
                Ref<EntityStore> ref = chunk.getReferenceTo(index);

                if (playerRef != null) {
                    ItemStack held = event.getContext() != null ? event.getContext().getHeldItem() : null;
                    if (held == null || held.isEmpty()) {
                        held = InventoryComponent.getItemInHand(store, ref);
                    }
                    if (ClaimToolUtil.isUsingClaimTool(playerRef.getUuid(), held, plotManager)) {
                        ClaimToolUtil.handleToolClick(ref, store, playerRef, world, pos.x, pos.y, pos.z, false, plotManager);
                        event.setCancelled(true);
                        return;
                    }
                }

                Plot plot = plotManager.getPlotAt(world.getName(), pos.x, pos.y, pos.z);
                if (plot != null) {
                    if (isExcludedBlock(plotManager, event.getBlockType(), world, pos.x, pos.y, pos.z)) {
                        return;
                    }
                    if (playerRef != null && plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (playerRef == null || !plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        event.setCancelled(true);
                        sendDeniedMessageRateLimited(playerRef, plot, "interact");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Protects interactive entities (mounts, minecarts, capture crates, stands, etc.) located inside plots
     * from unauthorized players and notifies them who owns the plot.
     */
    public static class UseEntityPreSystem extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {
        private final PlotManager plotManager;

        public UseEntityPreSystem(PlotManager plotManager) {
            super(UseEntityEvent.Pre.class);
            this.plotManager = plotManager;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index,
                           ArchetypeChunk<EntityStore> chunk,
                           Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer,
                           UseEntityEvent.Pre event) {
            try {
                if (event.isCancelled()) {
                    return;
                }
                Ref<EntityStore> targetEntity = event.getTargetEntity();
                if (targetEntity == null || !targetEntity.isValid()) {
                    return;
                }
                TransformComponent tc = store.getComponent(targetEntity, TransformComponent.getComponentType());
                if (tc == null || tc.getPosition() == null) {
                    return;
                }
                World world = ((EntityStore) store.getExternalData()).getWorld();
                if (world == null) {
                    return;
                }
                Vector3d entityPos = tc.getPosition();
                int bx = (int) Math.floor(entityPos.x);
                int by = (int) Math.floor(entityPos.y);
                int bz = (int) Math.floor(entityPos.z);

                Plot plot = plotManager.getPlotAt(world.getName(), bx, by, bz);
                if (plot != null) {
                    PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
                    if (playerRef != null && plot.isOwner(playerRef.getUuid())) {
                        plot.touchActivity();
                        return;
                    }
                    if (playerRef == null || !plot.isAuthorized(playerRef.getUuid(), playerRef.getUsername())) {
                        event.setCancelled(true);
                        sendDeniedMessageRateLimited(playerRef, plot, "interact");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}

