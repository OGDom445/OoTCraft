package com.ootmc;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Minecraft's world past the edges of Hyrule's map is revealed only by digging into it (see Underground.fillWorld):
 * nothing of it shows in Zelda until it's broken into. A short-lived test version placed whole chunks of terrain
 * around the map in advance; this clears those chunks (listed in the world's ootmc_frontier.txt), one per tick.
 */
public final class Frontier {
    private Frontier() {}

    private static Deque<Long> toClear;
    private static Path listFile;

    static void cleanup(ServerLevel hyrule) {
        if (CollisionField.mesh() == null) return;
        Path file = hyrule.getServer().getWorldPath(LevelResource.ROOT).resolve("ootmc_frontier.txt");
        if (toClear == null || !file.equals(listFile)) {
            listFile = file;
            toClear = new ArrayDeque<>();
            try {
                if (Files.exists(file)) {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        try {
                            toClear.add(Long.parseLong(line.trim()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                OotMc.LOGGER.warn("[OoTCraft] couldn't read {}", file, e);
            }
        }
        if (toClear.isEmpty()) return;
        long k = toClear.poll();
        int cx = (int) (k >> 42), cz = (int) (k << 42 >> 42); // keyed like sections: (x, 0, z)
        clear(hyrule, cx, cz);
        if (toClear.isEmpty()) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                OotMc.LOGGER.warn("[OoTCraft] couldn't delete {}", file, e);
            }
            OotMc.LOGGER.info("[OoTCraft] cleared the terrain placed around the map in advance");
        }
    }

    private static void clear(ServerLevel hyrule, int cx, int cz) {
        LevelChunk chunk = hyrule.getChunk(cx, cz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Set<Long> touched = new java.util.HashSet<>();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = cx * 16 + x, wz = cz * 16 + z;
                if (CollisionField.onMap(wx, wz)) continue;
                for (int y = hyrule.getMinBuildHeight(); y < hyrule.getMaxBuildHeight(); y++) {
                    pos.set(wx, y, wz);
                    if (chunk.getBlockState(pos).isAir()) continue;
                    chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false);
                    touched.add(OotMc.sectionKey(cx, y >> 4, cz));
                }
            }
        }
        if (touched.isEmpty()) return;
        chunk.setUnsaved(true);
        var packet = new ClientboundLevelChunkWithLightPacket(chunk, hyrule.getLightEngine(), null, null);
        for (ServerPlayer p : hyrule.players()) p.connection.send(packet);
        OotMc.DIRTY_SECTIONS.addAll(touched);
    }
}
