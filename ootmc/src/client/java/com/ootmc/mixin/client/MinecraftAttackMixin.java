package com.ootmc.mixin.client;

import com.ootmc.client.OotMcClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Holding the attack button only keeps mining while Minecraft has grabbed the mouse, which a hidden window never
 * does. Zelda's window owns the mouse instead, so holding left click mines blocks as usual.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean ootmc$keepMining(boolean leftClick) {
        Minecraft mc = (Minecraft) (Object) this;
        if (!leftClick && OotMcClient.overlayMode() && mc.screen == null && mc.player != null
            && mc.options.keyAttack.isDown() && !mc.player.isUsingItem()) {
            leftClick = true;
        }
        if (leftClick && mc.screen == null) OotMcClient.mineHyruleGround();
        return leftClick;
    }
}
