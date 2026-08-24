package com.wakeup.wakeup;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Core dream logic. The dream is server-wide: one stack of whole-server snapshots.
 * Only the top (deepest) layer counts down; deeper layers wait until the layer above
 * them wakes up, which gives the "dream within a dream" behaviour.
 */
public final class DreamManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Players whose death was tentatively cancelled this tick (to detect a full party wipe). */
    private static final Set<UUID> deathsThisTick = new HashSet<>();

    /** Players being force-killed by us, so the death handler lets the death through. */
    private static final Set<UUID> forceKilling = new HashSet<>();

    /** Players who will always trigger a dream on their next night-skip (test command). */
    private static final Set<UUID> forceDream = new HashSet<>();

    /** Players queued to enter a dream at the end of the tick (after wake-up completes). */
    private static final Set<UUID> pendingDream = new HashSet<>();

    /** True while rolling back blocks, to avoid re-recording those changes. */
    private static boolean rollingBack = false;

    private DreamManager() {
    }

    public static boolean isDreaming(MinecraftServer server) {
        return !getData(server).getLayers().isEmpty();
    }

    public static int getLayerCount(MinecraftServer server) {
        return getData(server).getLayers().size();
    }

    public static long getTopRemaining(MinecraftServer server) {
        ListTag layers = getData(server).getLayers();
        if (layers.isEmpty()) {
            return 0L;
        }
        return ((CompoundTag) layers.get(layers.size() - 1)).getLongOr("remainingTicks", 0L);
    }

    /** Queues a dream entry; the snapshot is actually taken at the end of this tick. */
    public static void queueDream(ServerPlayer player) {
        pendingDream.add(player.getUUID());
    }

    /** Toggles "force dream on next night-skip" for the player. Returns the new state. */
    public static boolean toggleForceDream(ServerPlayer player) {
        UUID id = player.getUUID();
        if (forceDream.contains(id)) {
            forceDream.remove(id);
            return false;
        }
        forceDream.add(id);
        return true;
    }

    public static boolean isForceDream(ServerPlayer player) {
        return forceDream.contains(player.getUUID());
    }

    /** Pushes a new dream layer: snapshots the whole server and starts the countdown. */
    public static void enterDream(MinecraftServer server, ServerPlayer trigger) {
        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();

        long duration = randomDuration(trigger);
        CompoundTag layer = new CompoundTag();
        layer.putLong("remainingTicks", duration);
        layer.put("snapshot", ServerSnapshot.capture(server));
        layers.add(layer);
        data.resetBlockKeys();
        data.markDirty();

        LOGGER.info("[wakeup] enter dream: layers={}, dayTime={}, remainingTicks={}",
                layers.size(), server.overworld().getDayTime(), duration);
    }

    /** Records a block change during the dream (its original state), so it can be reverted on wake. */
    public static void recordBlockChange(ServerLevel level, BlockPos pos, BlockState state, CompoundTag beTag) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();
        if (layers.isEmpty()) {
            return;
        }

        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        ListTag changes = top.getListOrEmpty("blockChanges");
        String dim = level.dimension().identifier().toString();

        // O(1) dedup: keep only the ORIGINAL state per position (first change wins).
        String key = dim + ':' + pos.getX() + ':' + pos.getY() + ':' + pos.getZ();
        if (!data.tryRecordBlock(key)) {
            return;
        }

        CompoundTag change = new CompoundTag();
        change.putString("dim", dim);
        change.putInt("x", pos.getX());
        change.putInt("y", pos.getY());
        change.putInt("z", pos.getZ());
        change.put("state", NbtUtils.writeBlockState(state));
        if (beTag != null && !beTag.isEmpty()) {
            change.put("be", beTag);
        }
        changes.add(change);
        top.put("blockChanges", changes);
        data.markDirty();
    }

    /** Called from the Level#setBlock mixin on every block change while dreaming. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState newState) {
        if (rollingBack) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !isDreaming(server)) {
            return;
        }
        BlockState oldState = level.getBlockState(pos);
        if (oldState == newState) {
            return; // no actual change
        }
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag beTag = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
        recordBlockChange(level, pos, oldState, beTag);
    }

    /** Called once per server tick (end of tick): resolves pending dreams and deaths, then counts down. */
    public static void tick(MinecraftServer server) {
        processPendingDreams(server);
        resolveDeaths(server);

        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();
        if (layers.isEmpty()) {
            return;
        }

        // Pause the countdown while nobody is online.
        if (server.getPlayerList().getPlayerCount() <= 0) {
            return;
        }

        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        long remaining = top.getLongOr("remainingTicks", 0L) - 1;
        if (remaining <= 0) {
            wake(server);
        } else {
            top.putLong("remainingTicks", remaining);
            data.markDirty();
        }
    }

    private static void processPendingDreams(MinecraftServer server) {
        if (pendingDream.isEmpty()) {
            return;
        }
        for (UUID uuid : pendingDream) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                enterDream(server, player);
            }
        }
        pendingDream.clear();
    }

    /** Called when a dreaming player's death is tentatively cancelled by the death handler. */
    public static void recordDeath(ServerPlayer player) {
        deathsThisTick.add(player.getUUID());
    }

    /** True while a player is being force-killed, so the death handler lets the death happen. */
    public static boolean shouldIgnoreDeath(ServerPlayer player) {
        return forceKilling.contains(player.getUUID());
    }

    /**
     * Decides, at the end of the tick, whether the recorded deaths were a full party wipe.
     * If every online player died this tick, the dream wakes (rollback). Otherwise the
     * cancelled deaths are re-applied as normal deaths and the dream continues.
     */
    private static void resolveDeaths(MinecraftServer server) {
        if (deathsThisTick.isEmpty()) {
            return;
        }

        boolean allDied = true;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!deathsThisTick.contains(p.getUUID())) {
                allDied = false;
                break;
            }
        }

        if (allDied) {
            wake(server);
        } else {
            for (UUID uuid : deathsThisTick) {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p != null) {
                    forceKilling.add(uuid);
                    p.setHealth(1.0F); // ensure positive so kill() actually triggers death
                    p.kill(p.level());
                    forceKilling.remove(uuid);
                }
            }
        }
        deathsThisTick.clear();
    }

    /** Pops the top layer and rolls the server back to its snapshot. */
    public static void wake(MinecraftServer server) {
        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();
        if (layers.isEmpty()) {
            return;
        }

        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        CompoundTag snapshot = top.getCompoundOrEmpty("snapshot");

        // 1. World + players.
        ServerSnapshot.restore(server, snapshot);

        // 2. Blocks (reverting containers may drop their contents as items).
        rollingBack = true;
        try {
            ServerSnapshot.restoreBlockChanges(server, top.getListOrEmpty("blockChanges"));
        } finally {
            rollingBack = false;
        }

        // 3. Entities LAST, so the items dropped in step 2 are also removed.
        ServerSnapshot.restoreEntities(server, snapshot.getListOrEmpty("entities"));

        // Players that were in the snapshot but are now offline get their rollback
        // applied the next time they log in.
        ListTag players = snapshot.getListOrEmpty("players");
        for (int i = 0; i < players.size(); i++) {
            CompoundTag pt = (CompoundTag) players.get(i);
            String uuid = pt.getStringOr("uuid", "");
            if (server.getPlayerList().getPlayer(UUID.fromString(uuid)) == null) {
                data.putPendingRollback(uuid, pt);
            }
        }

        layers.remove(layers.size() - 1);
        data.rebuildBlockKeys();
        data.markDirty();

        LOGGER.info("[wakeup] wake: layers after pop={}", layers.size());
    }

    /** Applies any rollback that happened while this player was offline. */
    public static void handleJoin(ServerPlayer player) {
        WakeUpSavedData data = getData(player.level().getServer());
        String uuid = player.getUUID().toString();
        if (data.hasPendingRollback(uuid)) {
            CompoundTag pt = data.takePendingRollback(uuid);
            if (!pt.isEmpty()) {
                ServerSnapshot.restorePlayer(player, pt);
            }
        }
    }

    private static WakeUpSavedData getData(MinecraftServer server) {
        return WakeUpSavedData.get(server.overworld());
    }

    private static long randomDuration(ServerPlayer player) {
        int min = WakeUpConfig.DREAM_MIN_SECONDS.get();
        int max = WakeUpConfig.DREAM_MAX_SECONDS.get();
        RandomSource random = player.getRandom();
        int seconds = min >= max ? min : min + random.nextInt(max - min + 1);
        return seconds * 20L;
    }
}
