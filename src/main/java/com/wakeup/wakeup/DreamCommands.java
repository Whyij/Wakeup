package com.wakeup.wakeup;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Test / debug commands for the dream mechanic. Gated at the same permission level
 * as vanilla cheat commands (gamemaster / "cheats enabled").
 */
@EventBusSubscriber(modid = WakeUp.MODID)
public final class DreamCommands {

    private DreamCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                literal("wakeup")
                        .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(literal("insomnia").executes(ctx -> insomnia(ctx.getSource())))
                        .then(literal("force").executes(ctx -> force(ctx.getSource())))
                        .then(literal("dream")
                                .executes(ctx -> dream(ctx.getSource(), false, null))
                                .then(literal("sleep")
                                        .executes(ctx -> dream(ctx.getSource(), false, null))
                                        .then(argument("seconds", IntegerArgumentType.integer(1))
                                                .executes(ctx -> dream(ctx.getSource(), false,
                                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
                                .then(literal("random")
                                        .executes(ctx -> dream(ctx.getSource(), true, null))
                                        .then(argument("seconds", IntegerArgumentType.integer(1))
                                                .executes(ctx -> dream(ctx.getSource(), true,
                                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
                                .then(argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(ctx -> dream(ctx.getSource(), false,
                                                IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(literal("wake").executes(ctx -> wake(ctx.getSource())))
                        .then(literal("time")
                                .then(argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(ctx -> setTime(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "seconds")))))
        );
    }

    private static int status(CommandSourceStack src) {
        if (!DreamManager.isDreaming(src.getServer())) {
            src.sendSuccess(() -> Component.literal("§7当前没有在做梦"), false);
        } else {
            int layers = DreamManager.getLayerCount(src.getServer());
            long remaining = DreamManager.getTopRemaining(src.getServer());
            src.sendSuccess(() -> Component.literal(
                    "§b正在做梦：共 " + layers + " 层，最深层剩余 " + remaining + " tick"), false);
        }
        return 1;
    }

    private static int insomnia(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        int nights = DreamManager.getInsomniaNights(player);
        double chance = DreamManager.getInsomniaChance(player);
        double min = WakeUpConfig.INSOMNIA_MIN_CHANCE.get();
        double max = WakeUpConfig.INSOMNIA_MAX_CHANCE.get();
        double inc = WakeUpConfig.INSOMNIA_INCREASE_PER_NIGHT.get();
        double sleepChance = DreamManager.getSleepDreamChance(player);
        src.sendSuccess(() -> Component.literal(
                "§e失眠状态：已熬夜 " + nights + " 夜，当前每刻进梦概率 " + fmt(chance)
                        + "（最小 " + fmt(min) + "，最大 " + fmt(max) + "，每夜 +" + fmt(inc)
                        + "），睡觉做梦概率 " + String.format(java.util.Locale.ROOT, "%.1f%%", sleepChance * 100)), false);
        return 1;
    }

    private static String fmt(double percent) {
        return String.format(java.util.Locale.ROOT, "%.4f%%", percent);
    }

    private static int force(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        boolean on = DreamManager.toggleForceDream(player);
        src.sendSuccess(() -> Component.literal(
                on ? "§a已开启：下次睡觉必定做梦（再执行 /wakeup force 关闭）" : "§e已关闭：恢复概率做梦"), false);
        return 1;
    }

    private static int dream(CommandSourceStack src, boolean insomnia, Integer seconds) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        long ticks;
        if (seconds != null) {
            ticks = seconds * 20L;
        } else if (insomnia) {
            ticks = DreamManager.randomInsomniaDuration(player);
        } else {
            ticks = DreamManager.randomDuration(player);
        }
        DreamManager.enterDream(player.level().getServer(), player, ticks);
        String kind = insomnia ? "随机梦（失眠）" : "睡觉梦";
        src.sendSuccess(() -> Component.literal(
                "§d已进入" + kind + "（时长 " + (ticks / 20L) + " 秒）"), false);
        return 1;
    }

    private static int wake(CommandSourceStack src) {
        if (!DreamManager.isDreaming(src.getServer())) {
            src.sendFailure(Component.literal("§c当前没有在做梦，无法醒来"));
            return 0;
        }
        DreamManager.wake(src.getServer());
        src.sendSuccess(() -> Component.literal("§a已强制苏醒并回滚到快照"), false);
        return 1;
    }

    private static int setTime(CommandSourceStack src, int seconds) {
        if (!DreamManager.isDreaming(src.getServer())) {
            src.sendFailure(Component.literal("§c当前没有在做梦"));
            return 0;
        }
        DreamManager.setTopRemaining(src.getServer(), seconds * 20L);
        src.sendSuccess(() -> Component.literal("§a梦境剩余时长已设为 " + seconds + " 秒"), false);
        return 1;
    }
}
