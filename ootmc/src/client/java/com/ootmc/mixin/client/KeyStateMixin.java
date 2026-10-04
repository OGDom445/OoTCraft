package com.ootmc.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ootmc.client.OotMcClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Menus ask the window whether Shift, Ctrl or Alt is down (shift-click to move a stack, Ctrl+Q to drop one, Shift in
 * the creative inventory). Minecraft's window is hidden and never sees the keyboard: answer with the keys Zelda says
 * are held.
 */
@Mixin(InputConstants.class)
public abstract class KeyStateMixin {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void ootmc$heldInZelda(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (OotMcClient.overlayMode() && OotMcClient.keyHeld(key)) cir.setReturnValue(true);
    }
}
