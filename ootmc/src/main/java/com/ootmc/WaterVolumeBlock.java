package com.ootmc;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Still water with no visible block: Zelda draws its own water, Minecraft only needs the fluid so swimming,
 * drowning and buoyancy work. It never schedules fluid ticks, so it can't spill out over Hyrule's invisible terrain.
 */
public class WaterVolumeBlock extends Block {
    public WaterVolumeBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return Fluids.WATER.getSource(false);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
