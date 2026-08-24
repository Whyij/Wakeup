package com.wakeup.wakeup;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Wires the dream mechanic into the game's events.
 */
@EventBusSubscriber(modid = WakeUp.MODID)
public final class DreamEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void onWakeUp(PlayerWakeUpEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (event.wakeImmediately()) {
            return; // forced wake (bed broken / disconnect / leave bed early)
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        long dayTime = player.level().getDayTime() % 24000;
        LOGGER.info("[wakeup] onWakeUp: updateLevel={}, dayTime={}, force={}",
                event.updateLevel(), dayTime, DreamManager.isForceDream(player));

        // The night was skipped only if it is now day: the server sets the time to
        // morning BEFORE waking players. Leaving the bed early keeps it night.
        if (dayTime >= 13000) {
            return;
        }

        if (DreamManager.isForceDream(player)
                || player.getRandom().nextDouble() < WakeUpConfig.DREAM_CHANCE.get()) {
            DreamManager.queueDream(player);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!WakeUpConfig.WAKE_ON_DEATH.get()) {
            return;
        }
        if (!DreamManager.isDreaming(player.level().getServer())) {
            return;
        }
        // A re-kill we issue ourselves; let it happen normally.
        if (DreamManager.shouldIgnoreDeath(player)) {
            return;
        }

        // Tentatively cancel. The decision — full party wipe (wake) vs normal death —
        // is made at the end of this tick in DreamManager.resolveDeaths.
        event.setCanceled(true);
        player.setHealth(1.0F);
        DreamManager.recordDeath(player);
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DreamManager.handleJoin(player);
        }
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        DreamManager.tick(event.getServer());
    }
}
