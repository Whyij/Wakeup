package com.wakeup.wakeup.mixin;

import com.wakeup.wakeup.DreamManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks {@link Level#setBlock} so that every block change (player actions, explosions,
 * fluid flow, pistons, etc.) during a dream is recorded and can be reverted on wake.
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD")
    )
    private void wakeup$recordBlockChange(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                          CallbackInfoReturnable<Boolean> cir) {
        Level level = (Level) (Object) this;
        if (level.isClientSide() || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        DreamManager.onBlockChanged(serverLevel, pos, newState);
    }
}
