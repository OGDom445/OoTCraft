package com.ootmc.mixin.client;

import com.ootmc.client.OotMcClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While Zelda is drawing the world, Minecraft only draws the layers Zelda composites on top: the first-person hand
 * and the HUD. The world pass is skipped and the frame is cleared to transparent.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void ootmc$skipWorld(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                 GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f frustum,
                                 Matrix4f projection, CallbackInfo ci) {
        if (OotMcClient.overlayMode()) {
            // The skipped pass would have pointed entity rendering at this camera; the inventory's player
            // preview needs it (it crashed on a null camera)
            Minecraft mc = Minecraft.getInstance();
            mc.getEntityRenderDispatcher().prepare(mc.level, camera, mc.crosshairPickEntity);
            RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, false);
            ci.cancel();
        }
    }
}
