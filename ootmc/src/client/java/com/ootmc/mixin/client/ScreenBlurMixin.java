package com.ootmc.mixin.client;

import com.ootmc.client.OotMcClient;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.21 blurs the world behind menus. In Hyrule the "world" behind the inventory is Zelda's picture, which Minecraft
 * can't see: the blur only smears its empty layer into a grey haze. Skip it so Hyrule shows behind the menu.
 */
@Mixin(Screen.class)
public abstract class ScreenBlurMixin {
    @Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void ootmc$noBlur(float partialTick, CallbackInfo ci) {
        if (OotMcClient.overlayMode()) ci.cancel();
    }
}
