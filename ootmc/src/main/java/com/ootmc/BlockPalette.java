package com.ootmc;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft block states <-> the ids Zelda stores in its block chunks. Ids are Minecraft's own block-state ids, so
 * every block keeps its identity; the client exports each one's textures and shape the first time it shows up
 * (see BlockTextures on the client side).
 */
public final class BlockPalette {
    public static final short AIR = 0;

    /** States seen in published sections whose looks the client still has to export. */
    public static final ConcurrentLinkedQueue<Integer> PENDING = new ConcurrentLinkedQueue<>();
    private static final Set<Integer> REQUESTED = ConcurrentHashMap.newKeySet();

    public static short toId(BlockState s, BlockGetter level, BlockPos pos) {
        if (s.isAir() || s.is(OotMc.WATER_VOLUME)) return AIR;
        // Barriers and light blocks are invisible; water and lava (caves, aquifers) are drawn even though their
        // render shape is a fluid, not a model
        if (s.getRenderShape() == RenderShape.INVISIBLE && !(s.getBlock() instanceof LiquidBlock)) return AIR;
        int id = Block.getId(s);
        if (id <= 0 || id > 0xFFFF) return AIR;
        if (REQUESTED.add(id)) PENDING.add(id);
        return (short) id;
    }

    public static BlockState toState(int id) {
        BlockState s = Block.stateById(id);
        return s.isAir() ? Blocks.STONE.defaultBlockState() : s;
    }
}
