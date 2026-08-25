package com.wakeup.wakeup;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common config. Lives in config/wakeup-common.toml and can be edited from the main
 * menu or in-game via the Mods list.
 */
public final class WakeUpConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue DREAM_CHANCE = BUILDER
            .comment("每次睡觉跳过夜晚后进入梦境的概率（0.0 - 1.0）。")
            .defineInRange("dreamChance", 0.2, 0.0, 1.0);

    public static final ModConfigSpec.IntValue DREAM_MIN_SECONDS = BUILDER
            .comment("梦境最短时长（秒）。每次进梦会在 [最短, 最长] 内随机取一个值。")
            .defineInRange("dreamMinSeconds", 300, 1, 36000);

    public static final ModConfigSpec.IntValue DREAM_MAX_SECONDS = BUILDER
            .comment("梦境最长时长（秒）。")
            .defineInRange("dreamMaxSeconds", 1200, 1, 36000);

    public static final ModConfigSpec.BooleanValue INSOMNIA_ENABLED = BUILDER
            .comment("开启后，玩家长期不睡觉会随机进入梦境（失眠机制）。")
            .define("insomniaEnabled", true);

    public static final ModConfigSpec.DoubleValue INSOMNIA_MIN_CHANCE = BUILDER
            .comment("失眠触发的最小每刻概率（百分比）。玩家入睡后会清零，重新从该值开始。")
            .defineInRange("insomniaMinChance", 0.0, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue INSOMNIA_MAX_CHANCE = BUILDER
            .comment("失眠触发的最大每刻概率（百分比）。")
            .defineInRange("insomniaMaxChance", 0.15, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue INSOMNIA_INCREASE_PER_NIGHT = BUILDER
            .comment("每熬一夜（不睡觉）增加的每刻概率（百分点）。")
            .defineInRange("insomniaIncreasePerNight", 0.004, 0.0, 100.0);

    public static final ModConfigSpec.DoubleValue INSOMNIA_DURATION_MULTIPLIER = BUILDER
            .comment("失眠随机进梦的持续时长倍率（相对睡觉进梦的随机时长范围）。默认 0.5。")
            .defineInRange("insomniaDurationMultiplier", 0.5, 0.01, 100.0);

    public static final ModConfigSpec.BooleanValue WAKE_ON_DEATH = BUILDER
            .comment("开启后，所有在线玩家同时死亡时会醒梦（单人即该玩家死亡）。")
            .define("wakeOnDeath", true);

    public static final ModConfigSpec.DoubleValue WAKE_WARNING_SECONDS = BUILDER
            .comment("梦境剩余多少秒时进入\"即将苏醒\"阶段（开始施加苏醒前效果）。设为 0 可关闭。")
            .defineInRange("wakeWarningSeconds", 1.5, 0.0, 60.0);

    public static final ModConfigSpec.ConfigValue<String> WAKE_BEFORE_EFFECTS = BUILDER
            .comment("即将苏醒时依次施加的药水效果，多个用分号 ; 分隔。每项格式：效果ID:延迟秒:持续秒:等级，例如 minecraft:darkness:0:5:1。")
            .define("wakeBeforeEffects", "minecraft:nausea:0:9:1;minecraft:slowness:0:5:1;minecraft:darkness:0.5:5:1");

    public static final ModConfigSpec.ConfigValue<String> WAKE_AFTER_EFFECTS = BUILDER
            .comment("苏醒后依次施加的药水效果，格式同上。")
            .define("wakeAfterEffects", "minecraft:blindness:0:3:1;minecraft:slowness:0:1.5:4;minecraft:slowness:1.5:2:1");

    public static final ModConfigSpec.ConfigValue<String> WAKE_AFTER_PARTICLES = BUILDER
            .comment("苏醒后依次产生的粒子，多个用分号 ; 分隔。每项格式：粒子ID:延迟秒:数量，例如 minecraft:end_rod:0:40。")
            .define("wakeAfterParticles", "minecraft:end_rod:0:40");

    public static final ModConfigSpec.ConfigValue<String> WAKE_SOUND_ID = BUILDER
            .comment("苏醒时播放的音效ID（例如 minecraft:block.portal.travel）。设为空字符串可关闭。")
            .define("wakeSoundId", "minecraft:block.portal.travel");

    public static final ModConfigSpec.ConfigValue<String> WAKE_SOUND_WHEN = BUILDER
            .comment("音效播放时机：none=不播放，before=即将苏醒时，after=苏醒后，both=前后都播放。")
            .define("wakeSoundWhen", "before");

    public static final ModConfigSpec SPEC = BUILDER.build();

    private WakeUpConfig() {
    }
}
