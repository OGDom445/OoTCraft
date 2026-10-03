package com.ootmc.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the hand + HUD layer be rendered at Zelda's view size whatever size the hidden window really is. */
@Mixin(Window.class)
public interface WindowSizeAccessor {
    @Accessor("framebufferWidth") void ootmc$setFramebufferWidth(int w);
    @Accessor("framebufferHeight") void ootmc$setFramebufferHeight(int h);
    @Accessor("width") void ootmc$setWidth(int w);
    @Accessor("height") void ootmc$setHeight(int h);
}
