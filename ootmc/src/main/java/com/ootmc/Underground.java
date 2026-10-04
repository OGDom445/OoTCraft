package com.ootmc;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What lies under Hyrule is a real Minecraft 1.21 world. The hidden "ootmc:deep" dimension is generated exactly
 * like a vanilla overworld (same seed); nobody goes there. When digging uncovers a block of Hyrule's ground deep
 * enough to be below its surface layers, the block that far below the deep world's own surface is copied in:
 * stone, deepslate, dirt and gravel pockets, every ore, caves and aquifers, dungeons and their chests, mineshafts,
 * geodes, lush caves, trial chambers, ancient cities, and bedrock at the bottom.
 */
public final class Underground {
    public static final ResourceKey<Level> DEEP =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(OotMc.MOD_ID, "deep"));

    /**
     * Fill a dug block of Hyrule with what vanilla generation has {@code depth} blocks under its surface at the same
     * column. Returns false if that's open air (a cave, a tunnel, a room): nothing is placed.
     */
    static boolean fill(ServerLevel hyrule, BlockPos pos, int depth) {
        ServerLevel deep = hyrule.getServer().getLevel(DEEP);
        if (deep == null) {
            hyrule.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            return true;
        }
        var source = deep.getChunkSource();
        int surface = source.getGenerator().getBaseHeight(pos.getX(), pos.getZ(), Heightmap.Types.OCEAN_FLOOR_WG, deep,
            source.randomState());
        int y = surface - depth;
        if (y < deep.getMinBuildHeight()) {
            hyrule.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            return true;
        }
        BlockPos from = new BlockPos(pos.getX(), y, pos.getZ());
        BlockState state = deep.getBlockState(from); // generates that chunk (terrain, carvers, features, structures)
        if (state.isAir()) return false;
        hyrule.setBlock(pos, state, Block.UPDATE_CLIENTS);
        BlockEntity src = deep.getBlockEntity(from);
        if (src != null) {
            CompoundTag tag = src.saveWithoutMetadata(hyrule.registryAccess());
            // Minecraft's mobs aren't drawn in Zelda's view: spawners stay as blocks but never wake up
            if (tag.contains("RequiredPlayerRange")) tag.putShort("RequiredPlayerRange", (short) 0);
            if (tag.contains("required_player_range")) tag.putInt("required_player_range", 0);
            BlockEntity dst = hyrule.getBlockEntity(pos);
            if (dst != null) {
                dst.loadWithComponents(tag, hyrule.registryAccess());
                dst.setChanged();
            }
        }
        return true;
    }

    /**
     * Hyrule's own height (Zelda's ground level, Minecraft y 128) lines up with the generated world's sea level:
     * past the edges of the map, Minecraft's world continues at the same coordinates, this many blocks higher.
     */
    public static final int WORLD_Y_SHIFT = Bridge.ORIGIN_Y_BLOCKS - 64;

    /** Fill a dug block past the edge of the map with the Minecraft world's block there (air stays air). */
    static boolean fillWorld(ServerLevel hyrule, BlockPos pos) {
        ServerLevel deep = hyrule.getServer().getLevel(DEEP);
        if (deep == null) return false;
        BlockPos from = pos.below(WORLD_Y_SHIFT);
        if (from.getY() < deep.getMinBuildHeight()) return false;
        BlockState state = deep.getBlockState(from);
        if (state.isAir()) return false;
        hyrule.setBlock(pos, state, Block.UPDATE_CLIENTS);
        copyBlockEntity(deep, from, hyrule, pos);
        return true;
    }

    /** Is this spot (past the edge of the map) under the Minecraft world's ground, i.e. in a cave? */
    static boolean belowWorldSurface(ServerLevel hyrule, BlockPos pos) {
        ServerLevel deep = hyrule.getServer().getLevel(DEEP);
        if (deep == null) return false;
        var source = deep.getChunkSource();
        int surface = source.getGenerator().getBaseHeight(pos.getX(), pos.getZ(), Heightmap.Types.OCEAN_FLOOR_WG, deep,
            source.randomState());
        return pos.getY() - WORLD_Y_SHIFT < surface - 1;
    }

    /** A chest's contents, a spawner's mob... copied along with the block. */
    static void copyBlockEntity(ServerLevel deep, BlockPos from, ServerLevel hyrule, BlockPos pos) {
        BlockEntity src = deep.getBlockEntity(from);
        if (src == null) return;
        CompoundTag tag = src.saveWithoutMetadata(hyrule.registryAccess());
        // Minecraft's mobs aren't drawn in Zelda's view: spawners stay as blocks but never wake up
        if (tag.contains("RequiredPlayerRange")) tag.putShort("RequiredPlayerRange", (short) 0);
        if (tag.contains("required_player_range")) tag.putInt("required_player_range", 0);
        BlockEntity dst = hyrule.getBlockEntity(pos);
        if (dst != null) {
            dst.loadWithComponents(tag, hyrule.registryAccess());
            dst.setChanged();
        }
    }

    /**
     * Drill a 16x16 core down from the deep world's surface to bedrock under a Hyrule column and count what's in it
     * (proof of what digging there will find). Returned as a readable summary.
     */
    public static String coreSample(net.minecraft.server.MinecraftServer server, int x, int z) {
        ServerLevel deep = server.getLevel(DEEP);
        if (deep == null) return "deep world missing";
        var source = deep.getChunkSource();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int air = 0, total = 0;
        int cx = x & ~15, cz = z & ~15;
        int topSurface = Integer.MIN_VALUE;
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int surface = source.getGenerator().getBaseHeight(cx + dx, cz + dz, Heightmap.Types.OCEAN_FLOOR_WG,
                    deep, source.randomState());
                topSurface = Math.max(topSurface, surface);
                for (int y = surface - 3; y >= deep.getMinBuildHeight(); y--) {
                    BlockState st = deep.getBlockState(new BlockPos(cx + dx, y, cz + dz));
                    total++;
                    if (st.isAir()) { air++; continue; }
                    String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    counts.merge(name, 1, Integer::sum);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("core under %d,%d: %d blocks from ~%d down to bedrock, %d open (caves/tunnels):",
            x, z, total, topSurface - 3, air));
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue()));
        // Nearest underground structures (like /locate), measured from this column
        var structures = deep.registryAccess().registryOrThrow(Registries.STRUCTURE);
        for (String id : new String[] { "mineshaft", "trial_chambers", "ancient_city", "stronghold" }) {
            var key = ResourceKey.create(Registries.STRUCTURE, ResourceLocation.withDefaultNamespace(id));
            var holder = structures.getHolder(key);
            if (holder.isEmpty()) continue;
            var found = source.getGenerator().findNearestMapStructure(deep,
                net.minecraft.core.HolderSet.direct(holder.get()), new BlockPos(x, 0, z), 12, false);
            if (found != null) {
                BlockPos p = found.getFirst();
                sb.append(String.format(" | nearest %s: %d blocks away (%d, %d)", id,
                    (int) Math.sqrt(p.distSqr(new BlockPos(x, p.getY(), z))), p.getX(), p.getZ()));
            }
        }
        return sb.toString();
    }

    /** Dungeon spawners and geodes in the chunks around a column (dungeons are features, not structures). */
    public static String featureScan(net.minecraft.server.MinecraftServer server, int x, int z, int radiusChunks) {
        ServerLevel deep = server.getLevel(DEEP);
        if (deep == null) return "deep world missing";
        int spawners = 0, mossy = 0, amethyst = 0, chests = 0;
        java.util.List<String> where = new java.util.ArrayList<>();
        for (int cx = (x >> 4) - radiusChunks; cx <= (x >> 4) + radiusChunks; cx++) {
            for (int cz = (z >> 4) - radiusChunks; cz <= (z >> 4) + radiusChunks; cz++) {
                var chunk = deep.getChunk(cx, cz);
                for (var be : chunk.getBlockEntities().values()) {
                    if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
                        spawners++;
                        where.add(be.getBlockPos().toShortString());
                    }
                    if (be instanceof net.minecraft.world.level.block.entity.ChestBlockEntity) chests++;
                }
                var sections = chunk.getSections();
                for (var sec : sections) {
                    if (sec.hasOnlyAir()) continue;
                    if (sec.maybeHas(st -> st.is(Blocks.MOSSY_COBBLESTONE))) mossy++;
                    if (sec.maybeHas(st -> st.is(Blocks.AMETHYST_BLOCK))) amethyst++;
                }
            }
        }
        return String.format("within %d chunks of %d,%d: %d dungeon spawners %s, %d chests, mossy cobblestone in %d "
            + "sections, amethyst geodes in %d sections", radiusChunks, x, z, spawners, where, chests, mossy, amethyst);
    }

    private Underground() {}
}
