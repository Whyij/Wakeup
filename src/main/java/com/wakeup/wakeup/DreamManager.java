package com.wakeup.wakeup;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Core dream logic. The dream is server-wide: one stack of whole-server snapshots. Only the
 * top (deepest) layer counts down; deeper layers wait until the layer above them wakes up,
 * which gives the "dream within a dream" behaviour.
 *
 * <p>Rollback is chunk-granular and lazy: each layer keeps a per-chunk entity baseline (plus
 * that chunk's block changes). On wake, loaded chunks are restored immediately; unloaded
 * chunks are moved to a pending list and restored the next time they load — no force-loading.
 */
public final class DreamManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ticks to wait after a chunk loads before treating its (still absent) entities as final. */
    private static final int CHUNK_GRACE_TICKS = 3;

    /** Players whose death was tentatively cancelled this tick (to detect a full party wipe). */
    private static final Set<UUID> deathsThisTick = new HashSet<>();

    /** Players being force-killed by us, so the death handler lets the death through. */
    private static final Set<UUID> forceKilling = new HashSet<>();

    /** Players who will always trigger a dream on their next night-skip (test command). */
    private static final Set<UUID> forceDream = new HashSet<>();

    /** Players queued to enter a dream at the end of the tick (after wake-up completes). */
    private static final Set<UUID> pendingDream = new HashSet<>();

    /** Players queued to enter an insomnia dream (scaled duration) at the end of the tick. */
    private static final Set<UUID> pendingInsomniaDream = new HashSet<>();

    /** True while restoring blocks, to avoid re-recording those changes. */
    private static boolean rollingBack = false;

    /** Timed tasks scheduled to run after a delay (for sequential wake effects). */
    private static final List<TimedTask> timedTasks = new ArrayList<>();

    /** Chunks whose entity baseline is still unknown, chunkKey -> first-loaded tick. */
    private static final Map<String, Long> pendingEmpty = new HashMap<>();

    /** Pending entity restores waiting for a chunk's entities to finish loading. */
    private static final Map<String, PendingRestore> restorePending = new HashMap<>();

    /** Insomnia: player UUID -> nights spent without sleeping. */
    private static final Map<UUID, Integer> insomniaNights = new HashMap<>();
    /** Insomnia: whether each online player slept through the current night. */
    private static final Map<UUID, Boolean> sleptThisNight = new HashMap<>();
    /** Previous overworld day time, used to detect day/night boundaries. */
    private static long prevDayTime = -1L;

    private record TimedTask(long runTick, Runnable task) {
    }

    private record PendingRestore(long loadedTick, CompoundTag rec) {
    }

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

    /** Sets the top dream layer's remaining time (in ticks). */
    public static void setTopRemaining(MinecraftServer server, long ticks) {
        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();
        if (layers.isEmpty()) {
            return;
        }
        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        top.putLong("remainingTicks", Math.max(0, ticks));
        top.putBoolean("warned", false);
        data.markDirty();
    }

    /** Queues a dream entry; the snapshot is actually taken at the end of this tick. */
    public static void queueDream(ServerPlayer player) {
        pendingDream.add(player.getUUID());
    }

    /** Queues an insomnia-triggered dream (uses the scaled duration). */
    public static void queueInsomniaDream(ServerPlayer player) {
        pendingInsomniaDream.add(player.getUUID());
    }

    /** Resets a player's insomnia counter after they sleep through a night. */
    public static void resetInsomnia(ServerPlayer player) {
        insomniaNights.put(player.getUUID(), 0);
        sleptThisNight.put(player.getUUID(), true);
    }

    /** Returns how many nights this player has gone without sleeping. */
    public static int getInsomniaNights(ServerPlayer player) {
        return insomniaNights.getOrDefault(player.getUUID(), 0);
    }

    /** Returns this player's current per-tick insomnia dream chance (percent). */
    public static double getInsomniaChance(ServerPlayer player) {
        int nights = getInsomniaNights(player);
        double min = WakeUpConfig.INSOMNIA_MIN_CHANCE.get();
        double max = WakeUpConfig.INSOMNIA_MAX_CHANCE.get();
        double inc = WakeUpConfig.INSOMNIA_INCREASE_PER_NIGHT.get();
        return Math.min(min + nights * inc, max);
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

    /** Pushes a new dream layer with a random duration. */
    public static void enterDream(MinecraftServer server, ServerPlayer trigger) {
        enterDream(server, trigger, randomDuration(trigger));
    }

    /** Pushes a new dream layer with a fixed duration (in ticks). */
    public static void enterDream(MinecraftServer server, ServerPlayer trigger, long durationTicks) {
        WakeUpSavedData data = getData(server);
        ListTag layers = data.getLayers();

        CompoundTag layer = new CompoundTag();
        layer.putLong("remainingTicks", durationTicks);
        layer.put("snapshot", ServerSnapshot.capture(server));
        layer.put("chunks", ServerSnapshot.captureEntryChunks(server));
        Tag dragonFight = snapshotDragonFightData(server);
        if (dragonFight != null) {
            layer.put("dragonFight", dragonFight);
        }
        layers.add(layer);

        pendingEmpty.clear();
        data.rebuildIndexes();
        data.markDirty();

        LOGGER.info("[wakeup] enter dream: layers={}, dayTime={}, remainingTicks={}",
                layers.size(), server.overworld().getDayTime(), durationTicks);
    }

    private static ListTag chunksOf(CompoundTag layer) {
        ListTag chunks = layer.getListOrEmpty("chunks");
        layer.put("chunks", chunks);
        return chunks;
    }

    /** Records a block change during the dream (its original state), into its chunk's record. */
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

        String dim = level.dimension().identifier().toString();
        String posKey = dim + ':' + pos.getX() + ':' + pos.getY() + ':' + pos.getZ();
        if (!data.tryRecordBlock(posKey)) {
            return; // first change wins
        }

        int cx = ServerSnapshot.chunkCoord(pos.getX());
        int cz = ServerSnapshot.chunkCoord(pos.getZ());
        String key = WakeUpSavedData.chunkKey(dim, cx, cz);

        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        CompoundTag rec = data.getChunkRecord(key);
        if (rec == null) {
            rec = ServerSnapshot.newChunkRecord(dim, cx, cz);
            chunksOf(top).add(rec);
            data.indexChunk(key, rec);
        }

        CompoundTag change = new CompoundTag();
        change.putInt("x", pos.getX());
        change.putInt("y", pos.getY());
        change.putInt("z", pos.getZ());
        change.put("state", NbtUtils.writeBlockState(state));
        if (beTag != null && !beTag.isEmpty()) {
            change.put("be", beTag);
        }
        ListTag blocks = rec.getListOrEmpty("blocks");
        blocks.add(change);
        rec.put("blocks", blocks);
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
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag beTag = be == null ? null : be.saveWithFullMetadata(level.registryAccess());
        recordBlockChange(level, pos, oldState, beTag);
    }

    /**
     * Called from {@code ChunkEvent.Load}: handles a pending restore (blocks now, entities
     * after the chunk's entities load) and creates the top layer's baseline record.
     */
    public static void onChunkLoad(ServerLevel level, int cx, int cz) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        WakeUpSavedData data = getData(server);
        String dim = level.dimension().identifier().toString();
        String key = WakeUpSavedData.chunkKey(dim, cx, cz);
        long now = server.getTickCount();

        // 1. A previously-woken dream left a pending restore for this chunk.
        CompoundTag pendingRec = null;
        if (data.getPendingRestores().contains(key)) {
            pendingRec = data.getPendingRestores().getCompoundOrEmpty(key);
            data.getPendingRestores().remove(key);
            data.markDirty();
            rollingBack = true;
            try {
                ServerSnapshot.restoreChunkBlocks(server, pendingRec);
            } finally {
                rollingBack = false;
            }
            restorePending.put(key, new PendingRestore(now, pendingRec));
        }

        // 2. Baseline record for the top dream layer (if any dream is active).
        ListTag layers = data.getLayers();
        if (layers.isEmpty()) {
            return;
        }
        if (data.getChunkRecord(key) == null) {
            CompoundTag rec = ServerSnapshot.newChunkRecord(dim, cx, cz);
            if (pendingRec != null && !pendingRec.isEmpty()) {
                // This chunk is being restored from a lower (already-woken) dream layer while
                // this layer is still active: seed this layer's baseline from that pre-dream
                // state so a later wake of this layer still rolls back to the right entities.
                rec.put("entities", pendingRec.getListOrEmpty("entities"));
                rec.put("blockEntities", pendingRec.getListOrEmpty("blockEntities"));
                rec.putBoolean("captured", true);
            } else {
                rec.put("blockEntities", ServerSnapshot.captureBlockEntities(level, cx, cz));
                pendingEmpty.put(key, now);
            }
            chunksOf((CompoundTag) layers.get(layers.size() - 1)).add(rec);
            data.indexChunk(key, rec);
            data.markDirty();
        }
    }

    /** Called once per server tick (end of tick): queues, deaths, countdown, chunk queues. */
    public static void tick(MinecraftServer server) {
        processTimedTasks(server);
        processPendingDreams(server);
        resolveDeaths(server);
        processChunkQueues(server);
        tickInsomnia(server);

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
        long remaining = top.getLongOr("remainingTicks", 0L);

        int warningTicks = (int) (WakeUpConfig.WAKE_WARNING_SECONDS.get() * 20);
        if (warningTicks > 0) {
            boolean warned = top.getBooleanOr("warned", false);
            if (!warned && remaining <= warningTicks) {
                applyBeforeWakeEffects(server);
                top.putBoolean("warned", true);
                data.markDirty();
            }
        }

        long next = remaining - 1;
        if (next <= 0) {
            wake(server);
        } else {
            top.putLong("remainingTicks", next);
            data.markDirty();
        }
    }

    /** Flushes baseline captures and grace-period finalizations collected this tick. */
    private static void processChunkQueues(MinecraftServer server) {
        WakeUpSavedData data = getData(server);
        long now = server.getTickCount();

        // 1. Capture each newly-loaded chunk's entity baseline once its (async) entities have
        //    had time to arrive. Querying the live chunk also catches worldgen entities and
        //    programmatic spawns (e.g. the ender dragon) that never fire loadedFromDisk.
        if (!pendingEmpty.isEmpty()) {
            pendingEmpty.entrySet().removeIf(e -> {
                if (now - e.getValue() >= CHUNK_GRACE_TICKS) {
                    CompoundTag rec = data.getChunkRecord(e.getKey());
                    if (rec != null && !rec.getBooleanOr("captured", false)) {
                        ServerSnapshot.captureChunkEntities(server, rec);
                        rec.putBoolean("captured", true);
                        data.markDirty();
                    }
                    return true;
                }
                return false;
            });
        }

        // 2. Finish pending restores once the chunk's entities have had time to load.
        if (!restorePending.isEmpty()) {
            restorePending.entrySet().removeIf(e -> {
                if (now - e.getValue().loadedTick() >= CHUNK_GRACE_TICKS) {
                    CompoundTag rec = e.getValue().rec();
                    String key = e.getKey();
                    if (ServerSnapshot.isChunkLoaded(server, rec)) {
                        ServerSnapshot.restoreChunkEntities(server, rec);
                        data.markDirty();
                    } else {
                        // Chunk unloaded again before we could restore; defer it once more.
                        data.getPendingRestores().put(key, rec);
                        data.markDirty();
                    }
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Insomnia mechanic: staying awake at night raises a per-tick chance of drifting into a
     * dream, growing each unslept night from {@code insomniaMinChance} up to
     * {@code insomniaMaxChance}.
     */
    private static void tickInsomnia(MinecraftServer server) {
        if (!WakeUpConfig.INSOMNIA_ENABLED.get()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        long dayTime = overworld.getDayTime();
        long prev = prevDayTime;
        prevDayTime = dayTime;
        if (prev < 0) {
            return; // first tick after (re)load: no boundary detection yet
        }

        long prevMod = Math.floorMod(prev, 24000L);
        long curMod = Math.floorMod(dayTime, 24000L);

        // Night begins: nobody has slept through the (new) night yet.
        if (prevMod < 13000 && curMod >= 13000) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                sleptThisNight.put(p.getUUID(), false);
            }
        }

        // Morning: count a missed night for anyone who stayed awake.
        if (prevMod >= 13000 && curMod < 13000) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!sleptThisNight.getOrDefault(p.getUUID(), false)) {
                    insomniaNights.merge(p.getUUID(), 1, Integer::sum);
                }
                sleptThisNight.put(p.getUUID(), false);
            }
        }

        // Per-tick roll while awake at night (skip if a dream is already running).
        if (curMod < 13000 || isDreaming(server) || server.getPlayerList().getPlayerCount() <= 0) {
            return;
        }
        double min = WakeUpConfig.INSOMNIA_MIN_CHANCE.get();
        double max = WakeUpConfig.INSOMNIA_MAX_CHANCE.get();
        double inc = WakeUpConfig.INSOMNIA_INCREASE_PER_NIGHT.get();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSleeping()) {
                continue;
            }
            int nights = insomniaNights.getOrDefault(p.getUUID(), 0);
            double chance = Math.min(min + nights * inc, max);
            if (chance > 0.0 && p.getRandom().nextDouble() * 100.0 < chance) {
                LOGGER.info("[wakeup] insomnia dream for {} (nights={}, chance={}%)",
                        p.getName().getString(), nights, chance);
                queueInsomniaDream(p);
            }
        }
    }

    /** Serializes the current ender dragon fight state (for the layer snapshot at dream entry). */
    private static Tag snapshotDragonFightData(MinecraftServer server) {
        try {
            return EndDragonFight.Data.CODEC
                    .encodeStart(NbtOps.INSTANCE, server.getWorldData().endDragonFightData()).getOrThrow();
        } catch (Exception e) {
            LOGGER.warn("[wakeup] 无法快照末地龙战状态: {}", e.getMessage());
            return null;
        }
    }

    /** Restores the ender dragon fight state from the top layer's snapshot. */
    private static void restoreDragonFight(MinecraftServer server, CompoundTag top) {
        if (!top.contains("dragonFight")) {
            return;
        }
        try {
            EndDragonFight.Data d = EndDragonFight.Data.CODEC
                    .parse(NbtOps.INSTANCE, top.get("dragonFight")).getOrThrow();
            ServerLevel end = server.getLevel(Level.END);
            if (end != null) {
                EndDragonFight old = end.getDragonFight();
                if (old != null) {
                    // Detach every player from the old fight's boss bar so the client's health
                    // bar disappears (e.g. after a death-triggered wake mid-fight).
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                        old.removePlayer(p);
                    }
                }
                server.getWorldData().setEndDragonFightData(d);
                long seed = server.getWorldData().worldGenOptions().seed();
                end.setDragonFight(new EndDragonFight(end, seed, d));
            }
        } catch (Exception e) {
            LOGGER.warn("[wakeup] 无法恢复末地龙战状态: {}", e.getMessage());
        }
    }

    private static void processPendingDreams(MinecraftServer server) {
        for (UUID uuid : pendingDream) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                enterDream(server, player);
            }
        }
        pendingDream.clear();

        for (UUID uuid : pendingInsomniaDream) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                enterDream(server, player, randomInsomniaDuration(player));
            }
        }
        pendingInsomniaDream.clear();
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
                    p.setHealth(1.0F);
                    p.kill(p.level());
                    forceKilling.remove(uuid);
                }
            }
        }
        deathsThisTick.clear();
    }

    /** Pops the top layer and rolls the server back lazily, then applies the after-wake effects. */
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

        // 2. Chunks: restore loaded ones now, defer unloaded ones. Three passes —
        //    blocks (may drop items) -> clear entities -> respawn baselines — so a moved
        //    entity's old instance is discarded before its baseline respawns (otherwise the
        //    "UUID already exists" check rejects the respawn and the entity vanishes).
        List<CompoundTag> loaded = new ArrayList<>();
        ListTag chunks = top.getListOrEmpty("chunks");
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag rec = (CompoundTag) chunks.get(i);
            String key = WakeUpSavedData.chunkKey(
                    rec.getStringOr("dim", "minecraft:overworld"),
                    rec.getIntOr("cx", 0), rec.getIntOr("cz", 0));
            if (ServerSnapshot.isChunkLoaded(server, rec)) {
                loaded.add(rec);
            } else {
                data.getPendingRestores().put(key, rec); // overwrite (outermost wins)
            }
        }
        rollingBack = true;
        try {
            for (CompoundTag rec : loaded) {
                ServerSnapshot.restoreChunkBlocks(server, rec);
            }
        } finally {
            rollingBack = false;
        }
        for (CompoundTag rec : loaded) {
            ServerSnapshot.clearChunkEntities(server, rec);
        }
        for (CompoundTag rec : loaded) {
            ServerSnapshot.respawnChunkEntities(server, rec);
        }
        restoreDragonFight(server, top);

        // 苏醒后：效果 + 粒子（回滚会清空药水效果，故必须在回滚后施加）。
        applyAfterWakeEffects(server);

        // Players that were in the snapshot but are now offline get their rollback on next login.
        ListTag players = snapshot.getListOrEmpty("players");
        for (int i = 0; i < players.size(); i++) {
            CompoundTag pt = (CompoundTag) players.get(i);
            String uuid = pt.getStringOr("uuid", "");
            if (server.getPlayerList().getPlayer(UUID.fromString(uuid)) == null) {
                data.putPendingRollback(uuid, pt);
            }
        }

        layers.remove(layers.size() - 1);
        pendingEmpty.clear();
        data.rebuildIndexes();
        data.markDirty();

        LOGGER.info("[wakeup] wake: layers after pop={}", layers.size());
    }

    /** 即将苏醒：按配置依次施加苏醒前效果，并按配置播放音效。 */
    private static void applyBeforeWakeEffects(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (String spec : splitSpecs(WakeUpConfig.WAKE_BEFORE_EFFECTS.get())) {
                applyEffect(server, player.getUUID(), spec);
            }
        }
        if (soundIn("before")) {
            playWakeSound(server);
        }
    }

    /** 苏醒后：按配置依次施加苏醒后效果与粒子，并按配置播放音效。 */
    private static void applyAfterWakeEffects(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (String spec : splitSpecs(WakeUpConfig.WAKE_AFTER_EFFECTS.get())) {
                applyEffect(server, player.getUUID(), spec);
            }
            for (String spec : splitSpecs(WakeUpConfig.WAKE_AFTER_PARTICLES.get())) {
                applyParticle(server, player.getUUID(), spec);
            }
        }
        if (soundIn("after")) {
            playWakeSound(server);
        }
    }

    /** 把分号分隔的字符串拆成若干项（忽略空项）。 */
    private static List<String> splitSpecs(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : joined.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean soundIn(String phase) {
        String when = WakeUpConfig.WAKE_SOUND_WHEN.get();
        return phase.equals(when) || "both".equals(when);
    }

    /** 解析并（延迟）施加一个效果，格式："效果ID:延迟秒:持续秒:等级"。 */
    private static void applyEffect(MinecraftServer server, UUID playerUuid, String spec) {
        try {
            String[] parts = spec.split(":");
            if (parts.length < 4) {
                return;
            }
            double delay = Double.parseDouble(parts[parts.length - 3]);
            double duration = Double.parseDouble(parts[parts.length - 2]);
            int amplifier = Integer.parseInt(parts[parts.length - 1]);
            if (duration <= 0) {
                return;
            }
            String idStr = String.join(":", Arrays.copyOf(parts, parts.length - 3));
            if (!idStr.contains(":")) {
                idStr = "minecraft:" + idStr;
            }
            Identifier id = Identifier.parse(idStr);

            Runnable task = () -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerUuid);
                if (p == null) {
                    return;
                }
                try {
                    Holder<MobEffect> effect = p.registryAccess().holderOrThrow(
                            ResourceKey.create(Registries.MOB_EFFECT, id));
                    p.addEffect(new MobEffectInstance(effect, (int) (duration * 20), amplifier, false, false, false));
                } catch (Exception e) {
                    LOGGER.warn("[wakeup] 无法应用效果 '{}': {}", spec, e.getMessage());
                }
            };

            int delayTicks = (int) (delay * 20);
            schedule(server, delayTicks, task);
        } catch (Exception e) {
            LOGGER.warn("[wakeup] 无法解析效果 '{}': {}", spec, e.getMessage());
        }
    }

    /** 解析并（延迟）生成粒子，格式："粒子ID:延迟秒:数量"。 */
    private static void applyParticle(MinecraftServer server, UUID playerUuid, String spec) {
        try {
            String[] parts = spec.split(":");
            if (parts.length < 3) {
                return;
            }
            double delay = Double.parseDouble(parts[parts.length - 2]);
            int count = Integer.parseInt(parts[parts.length - 1]);
            if (count <= 0) {
                return;
            }
            String idStr = String.join(":", Arrays.copyOf(parts, parts.length - 2));
            if (!idStr.contains(":")) {
                idStr = "minecraft:" + idStr;
            }
            Identifier id = Identifier.parse(idStr);

            Runnable task = () -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerUuid);
                if (p == null) {
                    return;
                }
                try {
                    ParticleType<?> type = p.level().registryAccess().lookupOrThrow(Registries.PARTICLE_TYPE)
                            .getValue(ResourceKey.create(Registries.PARTICLE_TYPE, id));
                    if (type instanceof ParticleOptions options) {
                        p.level().sendParticles(options,
                                p.getX(), p.getY() + 1.0, p.getZ(),
                                count, 0.5, 1.0, 0.5, 0.02);
                    }
                } catch (Exception e) {
                    LOGGER.warn("[wakeup] 无法应用粒子 '{}': {}", spec, e.getMessage());
                }
            };

            int delayTicks = (int) (delay * 20);
            schedule(server, delayTicks, task);
        } catch (Exception e) {
            LOGGER.warn("[wakeup] 无法解析粒子 '{}': {}", spec, e.getMessage());
        }
    }

    /** 播放配置的音效（直接向玩家发送声音包）。 */
    private static void playWakeSound(MinecraftServer server) {
        String soundId = WakeUpConfig.WAKE_SOUND_ID.get();
        if (soundId == null || soundId.isBlank()) {
            return;
        }
        try {
            SoundEvent sound = server.overworld().registryAccess().lookupOrThrow(Registries.SOUND_EVENT)
                    .getValue(ResourceKey.create(Registries.SOUND_EVENT, Identifier.parse(soundId)));
            if (sound == null) {
                LOGGER.warn("[wakeup] 找不到音效 '{}'", soundId);
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.connection.send(new ClientboundSoundEntityPacket(
                        Holder.direct(sound), SoundSource.PLAYERS, player,
                        1.0F, 1.0F, player.getRandom().nextLong()));
            }
        } catch (Exception e) {
            LOGGER.warn("[wakeup] 无法播放音效 '{}': {}", soundId, e.getMessage());
        }
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

    /** Schedules a task to run after a delay (in ticks); delay <= 0 runs immediately. */
    private static void schedule(MinecraftServer server, long delayTicks, Runnable task) {
        if (delayTicks <= 0) {
            task.run();
        } else {
            timedTasks.add(new TimedTask(server.getTickCount() + delayTicks, task));
        }
    }

    /** Runs any timed tasks whose delay has elapsed. */
    private static void processTimedTasks(MinecraftServer server) {
        if (timedTasks.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<Runnable> toRun = new ArrayList<>();
        timedTasks.removeIf(t -> {
            if (t.runTick() <= now) {
                toRun.add(t.task());
                return true;
            }
            return false;
        });
        for (Runnable task : toRun) {
            task.run();
        }
    }

    private static WakeUpSavedData getData(MinecraftServer server) {
        return WakeUpSavedData.get(server.overworld());
    }

    public static long randomDuration(ServerPlayer player) {
        int min = WakeUpConfig.DREAM_MIN_SECONDS.get();
        int max = WakeUpConfig.DREAM_MAX_SECONDS.get();
        RandomSource random = player.getRandom();
        int seconds = min >= max ? min : min + random.nextInt(max - min + 1);
        return seconds * 20L;
    }

    /** Random insomnia dream duration: the normal range scaled by insomniaDurationMultiplier. */
    public static long randomInsomniaDuration(ServerPlayer player) {
        int min = WakeUpConfig.DREAM_MIN_SECONDS.get();
        int max = WakeUpConfig.DREAM_MAX_SECONDS.get();
        RandomSource random = player.getRandom();
        int seconds = min >= max ? min : min + random.nextInt(max - min + 1);
        double mult = WakeUpConfig.INSOMNIA_DURATION_MULTIPLIER.get();
        long scaled = Math.max(1L, (long) (seconds * mult));
        return scaled * 20L;
    }
}
