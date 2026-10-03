package com.ootmc.mixin;

import com.ootmc.OotMc;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Single-player world, and Hyrule's collision lives in the client's copy of Zelda's mesh: the built-in server's
 * "moved wrongly" check re-runs each step against its own idea of Steve (different pose, e.g. standing in a
 * crawlspace) and kept snapping him back through tunnels. In Hyrule the client's movement is trusted.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveMixin {
    @Shadow public ServerPlayer player;

    @Redirect(method = "handleMovePlayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;isChangingDimension()Z"))
    private boolean ootmc$trustInHyrule(ServerPlayer p) {
        return p.isChangingDimension() || p.level().dimension() == OotMc.HYRULE;
    }
}
