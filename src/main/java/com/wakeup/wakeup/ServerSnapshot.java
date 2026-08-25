package com.wakeup.wakeup;

import com.wakeup.wakeup.mixin.ChunkMapInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Captures and restores the world, players and — on a per-chunk basis — entities and block
 * changes. Chunk records are the unit of rollback: each chunk loaded during a dream keeps a
 * one-time entity baseline plus any block changes made in it, and is restored lazily.
 */
public final class ServerSnapshot {

    private ServerSnapshot() {
    }

    /** Block coordinate -> chunk coordinate (16 blocks per chunk, floors correctly). */
    public static int chunkCoord(double v) {
        return Mth.floor(v) >> 4;
    }

    /** Creates an empty chunk record (baseline not yet captured). */
    public static CompoundTag newChunkRecord(String dim, int cx, int cz) {
        CompoundTag rec = new CompoundTag();
        rec.putString("dim", dim);
        rec.putInt("cx", cx);
        rec.putInt("cz", cz);
        rec.put("entities", new ListTag());
        rec.put("blockEntities", new ListTag());
        rec.put("blocks", new ListTag());
        rec.putBoolean("captured", false);
        return rec;
    }

    /** Serializes one entity to NBT (its position/UUID/etc. are all included). */
    public static CompoundTag saveEntity(Entity entity, ServerLevel level) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        entity.save(output);
        return output.buildResult();
    }

    /** Serializes every block entity in a chunk to NBT (its original pre-dream state). */
    public static ListTag captureBlockEntities(ServerLevel level, int cx, int cz) {
        ListTag list = new ListTag();
        LevelChunk chunk = level.getChunk(cx, cz);
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockPos pos = entry.getKey();
            CompoundTag rec = new CompoundTag();
            rec.putInt("x", pos.getX());
            rec.putInt("y", pos.getY());
            rec.putInt("z", pos.getZ());
            rec.put("nbt", entry.getValue().saveWithFullMetadata(level.registryAccess()));
            list.add(rec);
        }
        return list;
    }

    /** Captures all non-player, persistent entities currently in the chunk into the record. */
    public static void captureChunkEntities(MinecraftServer server, CompoundTag rec) {
        ServerLevel level = levelFor(server, rec.getStringOr("dim", "minecraft:overworld"));
        if (level == null) {
            return;
        }
        int cx = rec.getIntOr("cx", 0);
        int cz = rec.getIntOr("cz", 0);
        ListTag entities = rec.getListOrEmpty("entities");
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ServerPlayer || entity instanceof EnderDragon || !entity.shouldBeSaved()) {
                continue;
            }
            if (chunkCoord(entity.getX()) == cx && chunkCoord(entity.getZ()) == cz) {
                entities.add(saveEntity(entity, level));
            }
        }
        rec.put("entities", entities);
    }

    private static ServerLevel levelFor(MinecraftServer server, String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
    }

    /** Captures world state (time + weather) and every online player. */
    public static CompoundTag capture(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();

        ServerLevel overworld = server.overworld();
        ServerLevelData lvlData = (ServerLevelData) overworld.getLevelData();
        tag.putLong("dayTime", overworld.getDayTime());
        tag.putBoolean("raining", lvlData.isRaining());
        tag.putInt("rainTime", lvlData.getRainTime());
        tag.putBoolean("thundering", lvlData.isThundering());
        tag.putInt("thunderTime", lvlData.getThunderTime());

        ListTag players = new ListTag();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.add(capturePlayer(player));
        }
        tag.put("players", players);

        return tag;
    }

    /**
     * Captures the entity baseline for every currently-loaded chunk (including empty ones).
     * Empty chunks are left with {@code captured=false} so they can be finalized later once
     * we know no entities are still loading in.
     */
    public static ListTag captureEntryChunks(MinecraftServer server) {
        ListTag chunks = new ListTag();
        Map<String, CompoundTag> index = new HashMap<>();

        // 1. Enumerate loaded chunks (blocks already in memory) and create a record per chunk.
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().identifier().toString();
            Stream<ChunkHolder> holders = ((ChunkMapInvoker) level.getChunkSource().chunkMap)
                    .wakeup$allChunksWithAtLeastStatus(ChunkStatus.FULL);
            for (ChunkHolder holder : holders.toList()) {
                ChunkPos pos = holder.getPos();
                String key = WakeUpSavedData.chunkKey(dim, pos.x, pos.z);
                CompoundTag rec = newChunkRecord(dim, pos.x, pos.z);
                rec.put("blockEntities", captureBlockEntities(level, pos.x, pos.z));
                chunks.add(rec);
                index.put(key, rec);
            }
        }

        // 2. Fill each chunk's baseline from the entities already present in the world.
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().identifier().toString();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer || entity instanceof EnderDragon || !entity.shouldBeSaved()) {
                    continue;
                }
                int cx = chunkCoord(entity.getX());
                int cz = chunkCoord(entity.getZ());
                String key = WakeUpSavedData.chunkKey(dim, cx, cz);
                CompoundTag rec = index.get(key);
                if (rec == null) {
                    rec = newChunkRecord(dim, cx, cz);
                    chunks.add(rec);
                    index.put(key, rec);
                }
                ListTag entities = rec.getListOrEmpty("entities");
                entities.add(saveEntity(entity, level));
                rec.put("entities", entities);
                rec.putBoolean("captured", true);
            }
        }

        // All chunks are fully settled at entry; finalize every record (empty or not).
        for (int i = 0; i < chunks.size(); i++) {
            ((CompoundTag) chunks.get(i)).putBoolean("captured", true);
        }

        return chunks;
    }

    /** Restores world state and online players from a captured snapshot. */
    public static void restore(MinecraftServer server, CompoundTag snapshot) {
        ServerLevel overworld = server.overworld();
        ServerLevelData lvlData = (ServerLevelData) overworld.getLevelData();
        overworld.setDayTime(snapshot.getLongOr("dayTime", overworld.getDayTime()));
        lvlData.setRaining(snapshot.getBooleanOr("raining", false));
        lvlData.setRainTime(snapshot.getIntOr("rainTime", 0));
        lvlData.setThundering(snapshot.getBooleanOr("thundering", false));
        lvlData.setThunderTime(snapshot.getIntOr("thunderTime", 0));

        ListTag players = snapshot.getListOrEmpty("players");
        for (int i = 0; i < players.size(); i++) {
            CompoundTag pt = (CompoundTag) players.get(i);
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(pt.getStringOr("uuid", "")));
            if (player != null) {
                restorePlayer(player, pt);
            }
        }
    }

    /** True if the chunk this record targets is currently loaded. */
    public static boolean isChunkLoaded(MinecraftServer server, CompoundTag rec) {
        ServerLevel level = levelFor(server, rec.getStringOr("dim", "minecraft:overworld"));
        return level != null && level.getChunkSource().hasChunk(rec.getIntOr("cx", 0), rec.getIntOr("cz", 0));
    }

    /** Restores the blocks recorded in a chunk record (requires the chunk to be loaded). */
    public static void restoreChunkBlocks(MinecraftServer server, CompoundTag rec) {
        ServerLevel level = levelFor(server, rec.getStringOr("dim", "minecraft:overworld"));
        if (level == null) {
            return;
        }
        ListTag blocks = rec.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag b = (CompoundTag) blocks.get(i);
            BlockPos pos = new BlockPos(b.getIntOr("x", 0), b.getIntOr("y", 0), b.getIntOr("z", 0));
            BlockState state = NbtUtils.readBlockState(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), b.getCompoundOrEmpty("state"));
            level.setBlockAndUpdate(pos, state);
            if (b.contains("be")) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            level.registryAccess(), b.getCompoundOrEmpty("be")));
                }
            }
        }

        // Restore baseline block entities (e.g. a chest's contents from before the dream). This
        // runs AFTER block states so a freshly re-created block entity exists to load into, and
        // overwrites the break-time state captured above.
        ListTag blockEntities = rec.getListOrEmpty("blockEntities");
        for (int i = 0; i < blockEntities.size(); i++) {
            CompoundTag bt = (CompoundTag) blockEntities.get(i);
            BlockPos pos = new BlockPos(bt.getIntOr("x", 0), bt.getIntOr("y", 0), bt.getIntOr("z", 0));
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                        level.registryAccess(), bt.getCompoundOrEmpty("nbt")));
            }
        }
    }

    /** Discards the chunk's current non-player entities (spawned/moved-in/dropped during the dream). */
    public static void clearChunkEntities(MinecraftServer server, CompoundTag rec) {
        ServerLevel level = levelFor(server, rec.getStringOr("dim", "minecraft:overworld"));
        if (level == null) {
            return;
        }
        int cx = rec.getIntOr("cx", 0);
        int cz = rec.getIntOr("cz", 0);

        List<Entity> toRemove = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof ServerPlayer || entity instanceof EnderDragon) {
                continue; // the ender dragon is owned by the dragon fight, not by chunk rollback
            }
            if (chunkCoord(entity.getX()) == cx && chunkCoord(entity.getZ()) == cz) {
                toRemove.add(entity);
            }
        }
        for (Entity entity : toRemove) {
            entity.discard();
        }
    }

    /** Respawns the chunk's baseline entities. */
    public static void respawnChunkEntities(MinecraftServer server, CompoundTag rec) {
        ServerLevel level = levelFor(server, rec.getStringOr("dim", "minecraft:overworld"));
        if (level == null) {
            return;
        }
        ListTag entities = rec.getListOrEmpty("entities");
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag et = (CompoundTag) entities.get(i);
            Entity entity = EntityType.loadEntityRecursive(et, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity != null) {
                level.addFreshEntity(entity);
            }
        }
    }

    /** Clears then respawns one chunk's entities (single-chunk path, no cross-chunk UUID clash). */
    public static void restoreChunkEntities(MinecraftServer server, CompoundTag rec) {
        clearChunkEntities(server, rec);
        respawnChunkEntities(server, rec);
    }

    /** Full immediate restore of one chunk: blocks first, then entities. */
    public static void restoreChunkNow(MinecraftServer server, CompoundTag rec) {
        restoreChunkBlocks(server, rec);
        restoreChunkEntities(server, rec);
    }

    private static CompoundTag capturePlayer(ServerPlayer player) {
        CompoundTag tag = new CompoundTag();
        tag.putString("uuid", player.getUUID().toString());
        tag.putString("dim", player.level().dimension().identifier().toString());
        tag.putDouble("x", player.getX());
        tag.putDouble("y", player.getY());
        tag.putDouble("z", player.getZ());
        tag.putFloat("yaw", player.getYRot());
        tag.putFloat("pitch", player.getXRot());
        tag.putFloat("health", player.getHealth());
        tag.putFloat("absorption", player.getAbsorptionAmount());
        tag.putInt("foodLevel", player.getFoodData().getFoodLevel());
        tag.putFloat("saturation", player.getFoodData().getSaturationLevel());
        tag.putInt("xpLevel", player.experienceLevel);
        tag.putFloat("xpProgress", player.experienceProgress);
        tag.putInt("xpTotal", player.totalExperience);
        tag.putInt("selected", player.getInventory().getSelectedSlot());

        ListTag effects = new ListTag();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            var key = effect.getEffect().unwrapKey();
            if (key.isEmpty()) {
                continue;
            }
            CompoundTag et = new CompoundTag();
            et.putString("id", key.get().identifier().toString());
            et.putInt("amp", effect.getAmplifier());
            et.putInt("dur", effect.getDuration());
            et.putBoolean("ambient", effect.isAmbient());
            et.putBoolean("visible", effect.isVisible());
            et.putBoolean("icon", effect.showIcon());
            effects.add(et);
        }
        tag.put("effects", effects);

        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        ListTag inv = new ListTag();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            Tag itemTag = ItemStack.OPTIONAL_CODEC.encodeStart(ops, inventory.getItem(i)).getOrThrow();
            inv.add(itemTag);
        }
        tag.put("inventory", inv);

        return tag;
    }

    /** Restores a single player's state from a captured player snapshot. */
    public static void restorePlayer(ServerPlayer player, CompoundTag pt) {
        ServerLevel target = player.level().getServer()
                .getLevel(ResourceKey.create(Registries.DIMENSION,
                        Identifier.parse(pt.getStringOr("dim", "minecraft:overworld"))));
        if (target == null) {
            target = player.level();
        }

        player.teleportTo(target, pt.getDoubleOr("x", player.getX()), pt.getDoubleOr("y", player.getY()),
                pt.getDoubleOr("z", player.getZ()), Set.of(),
                pt.getFloatOr("yaw", player.getYRot()), pt.getFloatOr("pitch", player.getXRot()), false);
        player.setHealth(pt.getFloatOr("health", 20.0F));
        player.setAbsorptionAmount(pt.getFloatOr("absorption", 0.0F));
        player.getFoodData().setFoodLevel(pt.getIntOr("foodLevel", 20));
        player.getFoodData().setSaturation(pt.getFloatOr("saturation", 5.0F));
        player.experienceLevel = pt.getIntOr("xpLevel", 0);
        player.experienceProgress = pt.getFloatOr("xpProgress", 0.0F);
        player.totalExperience = pt.getIntOr("xpTotal", 0);
        player.getInventory().setSelectedSlot(pt.getIntOr("selected", 0));

        player.removeAllEffects();
        ListTag effects = pt.getListOrEmpty("effects");
        for (int i = 0; i < effects.size(); i++) {
            CompoundTag et = (CompoundTag) effects.get(i);
            Holder<MobEffect> holder = player.registryAccess().holderOrThrow(
                    ResourceKey.create(Registries.MOB_EFFECT, Identifier.parse(et.getStringOr("id", ""))));
            player.addEffect(new MobEffectInstance(holder, et.getIntOr("dur", 1), et.getIntOr("amp", 0),
                    et.getBooleanOr("ambient", false), et.getBooleanOr("visible", true), et.getBooleanOr("icon", true)));
        }

        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, player.registryAccess());
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        ListTag inv = pt.getListOrEmpty("inventory");
        for (int i = 0; i < inv.size() && i < inventory.getContainerSize(); i++) {
            ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, inv.get(i)).getOrThrow();
            inventory.setItem(i, stack);
        }

        // Sync the restored inventory and held slot to the client, otherwise the client keeps
        // showing the pre-wake hand/inventory (e.g. an empty hand that still places blocks).
        player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
    }
}
