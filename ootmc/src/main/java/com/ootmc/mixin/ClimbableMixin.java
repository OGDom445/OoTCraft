package com.ootmc.mixin;

import com.ootmc.CollisionField;
import com.ootmc.OotMc;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hyrule's ladders, vines and climbable walls climb like Minecraft ladders. */
@Mixin(LivingEntity.class)
public abstract class ClimbableMixin {
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void ootmc$hyruleClimbable(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player && !self.isSpectator() && self.level().dimension() == OotMc.HYRULE
            && CollisionField.touchingClimbable(self.getBoundingBox().inflate(0.15, 0, 0.15))) {
            cir.setReturnValue(true);
        }
    }
}
