package com.wakeup.wakeup;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Captures and restores a whole-server "situation": world state + every online player
 * + every loaded non-player entity. This is the backup a dream rolls back to on waking.
 */
public final class ServerSnapshot {

    private static final String DIM_KEY = "__dim";

    private ServerSnapshot() {
    }

    /** Captures the current server situation into a CompoundTag. */
    public static CompoundTag capture(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();

        // World situation (overworld time + weather)
        ServerLevel overworld = server.overworld();
        ServerLevelData lvlData = (ServerLevelData) overworld.getLevelData();
        tag.putLong("dayTime", overworld.getDayTime());
        tag.putBoolean("raining", lvlData.isRaining());
        tag.putInt("rainTime", lvlData.getRainTime());
        tag.putBoolean("thundering", lvlData.isThundering());
        tag.putInt("thunderTime", lvlData.getThunderTime());

        // Online players
        ListTag players = new ListTag();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            players.add(capturePlayer(player));
        }
        tag.put("players", players);

        // Loaded non-player entities
        tag.put("entities", captureEntities(server));

        return tag;
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
                continue; // skip effects without a registry key (defensive)
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

    /** Saves every loaded non-player entity (in all loaded dimensions) as NBT. */
    private static ListTag captureEntities(MinecraftServer server) {
        ListTag entities = new ListTag();
        for (ServerLevel level : server.getAllLevels()) {
            String dim = level.dimension().identifier().toString();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer || !entity.shouldBeSaved()) {
                    continue;
                }
                TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                entity.save(output);
                CompoundTag et = output.buildResult();
                et.putString(DIM_KEY, dim);
                entities.add(et);
            }
        }
        return entities;
    }

    /** Restores a previously captured situation: world, players, then entities. */
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

    /** Removes entities that appeared during the dream and respawns the saved ones. */
    public static void restoreEntities(MinecraftServer server, ListTag entities) {
        // Remove current non-player entities (collect first to avoid concurrent modification).
        List<Entity> toRemove = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ServerPlayer)) {
                    toRemove.add(entity);
                }
            }
        }
        for (Entity entity : toRemove) {
            entity.discard();
        }

        // Respawn the snapshot entities in their saved dimensions.
        for (int i = 0; i < entities.size(); i++) {
            CompoundTag et = (CompoundTag) entities.get(i);
            String dim = et.getStringOr(DIM_KEY, "minecraft:overworld");
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
            if (level == null) {
                continue;
            }
            Entity entity = EntityType.loadEntityRecursive(et, level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
            if (entity != null) {
                level.addFreshEntity(entity);
            }
        }
    }

    /** Restores blocks that were changed during the dream. */
    public static void restoreBlockChanges(MinecraftServer server, ListTag changes) {
        for (int i = 0; i < changes.size(); i++) {
            CompoundTag change = (CompoundTag) changes.get(i);
            String dim = change.getStringOr("dim", "minecraft:overworld");
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
            if (level == null) {
                continue;
            }
            BlockPos pos = new BlockPos(change.getIntOr("x", 0), change.getIntOr("y", 0), change.getIntOr("z", 0));
            BlockState state = NbtUtils.readBlockState(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK), change.getCompoundOrEmpty("state"));
            level.setBlockAndUpdate(pos, state);
            if (change.contains("be")) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be != null) {
                    be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                            level.registryAccess(), change.getCompoundOrEmpty("be")));
                }
            }
        }
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
    }
}
