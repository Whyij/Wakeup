package com.wakeup.wakeup;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * Server-wide persistent state, saved with the overworld (level.dat).
 *
 * <ul>
 *   <li>{@code layers} — the dream stack. Each element is a CompoundTag holding
 *       {@code remainingTicks} (long) and {@code snapshot} (a whole-server snapshot).</li>
 *   <li>{@code pendingRollbacks} — player snapshots for players who were offline when a
 *       dream ended, applied the next time they log in.</li>
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
    /** In-memory dedup cache of already-recorded block positions for the top layer (not persisted). */
    private final Set<String> blockKeys = new HashSet<>();

    public WakeUpSavedData() {
    }

    private WakeUpSavedData(CompoundTag tag) {
        this.layers.addAll(tag.getListOrEmpty("layers"));
        CompoundTag pending = tag.getCompoundOrEmpty("pendingRollbacks");
        for (String key : pending.keySet()) {
            this.pendingRollbacks.put(key, pending.getCompoundOrEmpty(key));
        }
        rebuildBlockKeys();
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put("layers", layers);
        tag.put("pendingRollbacks", pendingRollbacks);
        return tag;
    }

    public static WakeUpSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public ListTag getLayers() {
        return layers;
    }

    public void markDirty() {
        setDirty();
    }

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

    /** Tries to mark a block position as recorded; returns false if it was already recorded. */
    public boolean tryRecordBlock(String key) {
        return blockKeys.add(key);
    }

    public void resetBlockKeys() {
        blockKeys.clear();
    }

    public void rebuildBlockKeys() {
        blockKeys.clear();
        if (layers.isEmpty()) {
            return;
        }
        CompoundTag top = (CompoundTag) layers.get(layers.size() - 1);
        ListTag changes = top.getListOrEmpty("blockChanges");
        for (int i = 0; i < changes.size(); i++) {
            CompoundTag c = (CompoundTag) changes.get(i);
            blockKeys.add(c.getStringOr("dim", "") + ':' + c.getIntOr("x", 0) + ':' + c.getIntOr("y", 0) + ':' + c.getIntOr("z", 0));
        }
    }
}
