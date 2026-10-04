package com.ootmc.mixin.client;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The cursor Minecraft draws menus with (hover highlight, tooltips, the stack being carried). */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void ootmc$setXpos(double x);

    @Accessor("ypos")
    void ootmc$setYpos(double y);
}
