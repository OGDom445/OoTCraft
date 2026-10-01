package com.ootmc.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Block texture pixels, for exporting them to Zelda. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage ootmc$originalImage();
}
