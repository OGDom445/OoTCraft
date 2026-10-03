package com.ootmc.mixin;

import com.ootmc.CollisionField;
import com.ootmc.OotMc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fire on Hyrule's ground. Zelda's ground isn't made of Minecraft blocks, so vanilla fire can't be lit on it and would
 * go out at once. Here it lights on Hyrule's ground and burns for about 25 seconds before going out.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Unique
    private static boolean ootmc$onHyruleGround(LevelReader reader, BlockPos pos) {
        if (!(reader instanceof Level level) || level.dimension() != OotMc.HYRULE) return false;
        BlockPos below = pos.below();
        if (reader.getBlockState(below).isFaceSturdy(reader, below, Direction.UP)) return false;
        return CollisionField.blocked(level,
            new AABB(pos.getX() + 0.2, pos.getY() - 0.3, pos.getZ() + 0.2, pos.getX() + 0.8, pos.getY() + 0.1,
                pos.getZ() + 0.8));
    }

    @Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
    private void ootmc$surviveOnHyrule(BlockState state, LevelReader level, BlockPos pos,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ootmc$onHyruleGround(level, pos)) cir.setReturnValue(true);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void ootmc$burnOnHyrule(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                    CallbackInfo ci) {
        if (!ootmc$onHyruleGround(level, pos)) return;
        ci.cancel();
        int age = state.getValue(FireBlock.AGE);
        if (age >= 15 || (level.isRaining() && level.isRainingAt(pos))) {
            level.removeBlock(pos, false);
            return;
        }
        level.setBlock(pos, state.setValue(FireBlock.AGE, age + 1), Block.UPDATE_CLIENTS);
        level.scheduleTick(pos, (Block) (Object) this, 30 + random.nextInt(10));
    }
}
