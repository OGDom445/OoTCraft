package com.ootmc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ootmc.client.OotMcClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Full-screen "inside a block" / underwater / fire tints would cover Zelda's view of the world. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectMixin {
    @Inject(method = "renderScreenEffect", at = @At("HEAD"), cancellable = true)
    private static void ootmc$noScreenEffect(Minecraft mc, PoseStack poseStack, CallbackInfo ci) {
        if (OotMcClient.overlayMode()) ci.cancel();
    }
}
