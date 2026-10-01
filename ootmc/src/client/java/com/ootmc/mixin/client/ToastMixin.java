package com.ootmc.mixin.client;

import net.minecraft.client.gui.components.toasts.RecipeToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No "new recipes" / tutorial pop-ups over Zelda's screen (advancements still show). */
@Mixin(ToastComponent.class)
public abstract class ToastMixin {
    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void ootmc$quietToasts(Toast toast, CallbackInfo ci) {
        if (toast instanceof RecipeToast || toast instanceof TutorialToast) ci.cancel();
    }
}
