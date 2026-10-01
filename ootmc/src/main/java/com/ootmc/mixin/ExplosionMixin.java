package com.ootmc.mixin;

import com.ootmc.Bridge;
import com.ootmc.OotMc;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TNT (and Zelda's bombs, which explode here too) blow craters into Hyrule's own ground. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private double x;
    @Shadow @Final private double y;
    @Shadow @Final private double z;
    @Shadow @Final private float radius;

    @Inject(method = "explode", at = @At("HEAD"))
    private void ootmc$blastHyrule(CallbackInfo ci) {
        if (level.isClientSide || level.dimension() != OotMc.HYRULE) return;
        Bridge bridge = Bridge.get();
        if (bridge != null) {
            bridge.pushEvent(Bridge.EV_BLAST, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), -1,
                Math.round(radius * 10)); // radius in tenths of a block
        }
    }
}
