package com.ootmc.mixin.client;

import com.mojang.blaze3d.platform.Window;
import com.ootmc.client.OotMcClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The frame is complete (world skipped, hand + HUD drawn) right before it's presented: hand it to Zelda. */
@Mixin(Window.class)
public abstract class WindowMixin {
    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void ootmc$capture(CallbackInfo ci) {
        OotMcClient.onFrameComplete();
    }
}
