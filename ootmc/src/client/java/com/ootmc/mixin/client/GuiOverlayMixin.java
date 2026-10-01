package com.ootmc.mixin.client;

import com.ootmc.client.OotMcClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No vignette / pumpkin / portal tint over Zelda's world; the hotbar, hearts and screens still draw. */
@Mixin(Gui.class)
public abstract class GuiOverlayMixin {
    @Inject(method = "renderCameraOverlays", at = @At("HEAD"), cancellable = true)
    private void ootmc$noCameraOverlays(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (OotMcClient.overlayMode()) ci.cancel();
    }
}
