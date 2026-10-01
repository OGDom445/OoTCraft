package com.ootmc.mixin.client;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which block the player is mining (for the crack overlay Zelda draws). */
@Mixin(MultiPlayerGameMode.class)
public interface GameModeAccessor {
    @Accessor("destroyBlockPos")
    BlockPos ootmc$destroyBlockPos();
}
