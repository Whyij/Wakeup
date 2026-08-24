package com.wakeup.wakeup;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

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
                        .then(literal("force").executes(ctx -> force(ctx.getSource())))
                        .then(literal("dream").executes(ctx -> dream(ctx.getSource())))
                        .then(literal("wake").executes(ctx -> wake(ctx.getSource())))
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

    private static int force(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        boolean on = DreamManager.toggleForceDream(player);
        src.sendSuccess(() -> Component.literal(
                on ? "§a已开启：下次睡觉必定做梦（再执行 /wakeup force 关闭）" : "§e已关闭：恢复概率做梦"), false);
        return 1;
    }

    private static int dream(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        DreamManager.enterDream(player.level().getServer(), player);
        src.sendSuccess(() -> Component.literal("§d已进入梦境"), false);
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
}
