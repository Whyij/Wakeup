package com.wakeup.wakeup;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config. Lives in config/wakeup-server.toml and can be edited in-game
 * (single player) or on a dedicated server.
 */
public final class WakeUpConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DREAM_CHANCE = BUILDER
            .comment("每次睡觉跳过夜晚后进入梦境的概率（0.0 - 1.0）。")
            .defineInRange("dreamChance", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue DREAM_MIN_SECONDS = BUILDER
            .comment("梦境最短时长（秒）。每次进梦会在 [最短, 最长] 内随机取一个值。")
            .defineInRange("dreamMinSeconds", 300, 1, 36000);

    public static final ModConfigSpec.IntValue DREAM_MAX_SECONDS = BUILDER
            .comment("梦境最长时长（秒）。")
            .defineInRange("dreamMaxSeconds", 1200, 1, 36000);

    public static final ModConfigSpec.BooleanValue WAKE_ON_DEATH = BUILDER
            .comment("开启后，所有在线玩家同时死亡时会醒梦（单人即该玩家死亡）。")
            .define("wakeOnDeath", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private WakeUpConfig() {
    }
}
