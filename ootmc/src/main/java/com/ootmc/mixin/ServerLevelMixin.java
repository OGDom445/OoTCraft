package com.ootmc.mixin;

import com.ootmc.Bridge;
import com.ootmc.OotMc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change that clients are told about (placing, breaking, explosions, pistons, redstone, fluids) marks
 * its 16^3 section so the server re-sends it to Zelda.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void ootmc$onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        if (self.dimension() == OotMc.HYRULE && !newState.is(OotMc.WATER_VOLUME)) {
            OotMc.DIRTY_SECTIONS.add(OotMc.sectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            // Mined away: if it was dug-out Hyrule ground, Zelda turns the ground around it into blocks too
            if (newState.isAir() && !oldState.isAir() && !oldState.is(OotMc.WATER_VOLUME)) {
                Bridge bridge = Bridge.get();
                if (bridge != null) bridge.pushEvent(Bridge.EV_BLOCK_BROKEN, pos.getX(), pos.getY(), pos.getZ());
            }
        }
    }
}
