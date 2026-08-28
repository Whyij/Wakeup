package com.wakeup.wakeup;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRuleType;
import net.minecraft.world.level.gamerules.GameRuleTypeVisitor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.RegisterGameRuleCategoryEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Entry point of the Wake Up mod.
 *
 * <p>The mod implements a server-wide "dream" mechanic: when players sleep through
 * the night, there is a configurable chance that the whole server is snapshotted and
 * enters a timed dream. When the dream ends, the server rolls back to that snapshot.
 * Nested dreams (dream within a dream) are supported via a snapshot stack.</p>
 */
@Mod(WakeUp.MODID)
public final class WakeUp {

    public static final String MODID = "wakeup";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MODID);

    /** The Inception-style totem: spin it to tell dream from reality. */
    public static final DeferredItem<Item> TOTEM_ITEM = ITEMS.register("totem",
            key -> new TotemItem(new Item.Properties()
                    .setId(ResourceKey.create(Registries.ITEM, key))
                    .rarity(Rarity.EPIC)
                    .stacksTo(1)));

    public static final Supplier<EntityType<SpinningTopEntity>> SPINNING_TOP = ENTITY_TYPES.register("spinning_top",
            () -> EntityType.Builder.of(SpinningTopEntity::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(MODID, "spinning_top"))));

    /** Game-rule category shown in the world creation / game-rules screens. */
    public static final GameRuleCategory GAME_RULE_CATEGORY =
            new GameRuleCategory(Identifier.fromNamespaceAndPath(MODID, "wakeup"));

    public static final DeferredRegister<GameRule<?>> GAME_RULES =
            DeferredRegister.create(Registries.GAME_RULE, MODID);

    /** Whether each new player starts with a totem (default false; toggle in the world's game rules). */
    public static final Supplier<GameRule<Boolean>> GIVE_TOTEM = GAME_RULES.register("give_totem",
            () -> new GameRule<>(GAME_RULE_CATEGORY, GameRuleType.BOOL, BoolArgumentType.bool(),
                    GameRuleTypeVisitor::visitBoolean, Codec.BOOL,
                    value -> value ? 1 : 0, false, FeatureFlagSet.of()));

    public WakeUp(IEventBus modEventBus, ModContainer container) {
        ITEMS.register(modEventBus);
        ENTITY_TYPES.register(modEventBus);
        GAME_RULES.register(modEventBus);
        modEventBus.addListener(RegisterGameRuleCategoryEvent.class,
                event -> event.register(GAME_RULE_CATEGORY));
        // COMMON-type config: editable from the main menu and in-game (config/wakeup-common.toml).
        container.registerConfig(ModConfig.Type.COMMON, WakeUpConfig.SPEC);
        // In-game config screen, opened from the Mods list.
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mc, parent) -> new ConfigurationScreen(mc, parent));

        // Entity renderers are registered on the mod bus, client side only.
        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            modEventBus.addListener(SpinningTopClient::registerRenderers);
        }
    }

    /**
     * Gives a new player a totem once per world, if the {@code give_totem} game rule is enabled.
     * Called on player login; a persistent per-player flag prevents repeat handouts.
     */
    public static void giveStartingTotem(ServerPlayer player) {
        if (!player.level().getGameRules().get(GIVE_TOTEM.get())) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (data.getBooleanOr("wakeup.gaveTotem", false)) {
            return;
        }
        data.putBoolean("wakeup.gaveTotem", true);
        ItemStack totem = new ItemStack(TOTEM_ITEM.get());
        if (!player.getInventory().add(totem)) {
            player.spawnAtLocation(player.level(), totem);
        }
    }
}
