package com.ootmc.mixin.client;

import com.ootmc.client.OotMcClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs once per rendered frame: apply Zelda's mouse/keys and publish the camera back to Zelda. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"))
    private void ootmc$frame(CallbackInfo ci) {
        OotMcClient.onFrame();
    }
}
