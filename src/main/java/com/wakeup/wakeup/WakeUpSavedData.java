package com.wakeup.wakeup;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Server-wide persistent state, saved with the overworld (level.dat).
 *
 * <ul>
 *   <li>{@code layers} — the dream stack. Each element holds {@code remainingTicks},
 *       {@code snapshot} (world + players) and {@code chunks} (per-chunk entity baselines
 *       plus that chunk's changed blocks).</li>
 *   <li>{@code pendingRollbacks} — player snapshots for players who were offline when a
 *       dream ended, applied the next time they log in.</li>
 *   <li>{@code pendingRestores} — chunk records awaiting lazy restoration the next time
 *       their chunk loads.</li>
 * </ul>
 */
public final class WakeUpSavedData extends SavedData {

    private static final String NAME = "wakeup_dreams";

    // NOTE: CODEC must be declared before TYPE, because TYPE references CODEC in its
    // constructor and Java initializes static fields in declaration order.
    public static final Codec<WakeUpSavedData> CODEC =
            CompoundTag.CODEC.xmap(WakeUpSavedData::new, WakeUpSavedData::toTag);

    public static final SavedDataType<WakeUpSavedData> TYPE =
            new SavedDataType<>(NAME, WakeUpSavedData::new, WakeUpSavedData.CODEC);

    private final ListTag layers = new ListTag();
    private final CompoundTag pendingRollbacks = new CompoundTag();
    private final CompoundTag pendingRestores = new CompoundTag();

    /** In-memory index of the top layer's chunk records: chunkKey -> ChunkRecord (mutable ref). */
    private final Map<String, CompoundTag> chunkIndex = new HashMap<>();
    /** In-memory block-position dedup for the top layer (first change wins). */
    private final Set<String> blockKeys = new HashSet<>();

    public WakeUpSavedData() {
    }

    private WakeUpSavedData(CompoundTag tag) {
        this.layers.addAll(tag.getListOrEmpty("layers"));
        CompoundTag pending = tag.getCompoundOrEmpty("pendingRollbacks");
        for (String key : pending.keySet()) {
            this.pendingRollbacks.put(key, pending.getCompoundOrEmpty(key));
        }
        CompoundTag restores = tag.getCompoundOrEmpty("pendingRestores");
        for (String key : restores.keySet()) {
            this.pendingRestores.put(key, restores.getCompoundOrEmpty(key));
        }
        rebuildIndexes();
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("layers", layers);
        tag.put("pendingRollbacks", pendingRollbacks);
        tag.put("pendingRestores", pendingRestores);
        return tag;
    }

    public static WakeUpSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public static String chunkKey(String dim, int cx, int cz) {
        return dim + ':' + cx + ':' + cz;
    }

    public ListTag getLayers() {
        return layers;
    }

    public CompoundTag getPendingRestores() {
        return pendingRestores;
    }

    public void markDirty() {
        setDirty();
    }

    // --- offline player rollbacks ---

    public boolean hasPendingRollback(String uuid) {
        return pendingRollbacks.contains(uuid);
    }

    public CompoundTag takePendingRollback(String uuid) {
        CompoundTag tag = pendingRollbacks.getCompoundOrEmpty(uuid);
        pendingRollbacks.remove(uuid);
        setDirty();
        return tag;
    }

    public void putPendingRollback(String uuid, CompoundTag snapshot) {
        pendingRollbacks.put(uuid, snapshot);
        setDirty();
    }

    // --- block-position dedup ---

    public boolean tryRecordBlock(String key) {
        return blockKeys.add(key);
    }

    // --- top-layer chunk index ---

    public CompoundTag getChunkRecord(String key) {
        return chunkIndex.get(key);
    }

    public void indexChunk(String key, CompoundTag record) {
        chunkIndex.put(key, record);
    }

    /** Rebuilds the in-memory chunk index and block-key set from the current top layer. */
    public void rebuildIndexes() {
        chunkIndex.clear();
        blockKeys.clear();
        if (layers.isEmpty()) {
            return;
        }
        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        ListTag chunks = top.getListOrEmpty("chunks");
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag rec = (CompoundTag) chunks.get(i);
            String dim = rec.getStringOr("dim", "minecraft:overworld");
            chunkIndex.put(chunkKey(dim, rec.getIntOr("cx", 0), rec.getIntOr("cz", 0)), rec);
            ListTag blocks = rec.getListOrEmpty("blocks");
            for (int j = 0; j < blocks.size(); j++) {
                CompoundTag b = (CompoundTag) blocks.get(j);
                blockKeys.add(dim + ':' + b.getIntOr("x", 0) + ':' + b.getIntOr("y", 0) + ':' + b.getIntOr("z", 0));
            }
        }
    }
}
