package com.ootmc.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.ootmc.BlockPalette;
import com.ootmc.OotMc;
import com.ootmc.mixin.client.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.RandomAccessFile;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exports how each Minecraft block looks to %TEMP%/oot_mc_blocktex.bin so Zelda draws placed blocks with the real
 * textures (whatever resource pack is loaded, with grass/leaf tints and overlays baked in), the right shape and the
 * right collision. Layout (little-endian):
 *   0 magic "BTEX", 4 session (changes every launch), 8 slots used
 *   64: u16[65536] block-state id -> slot + 1 (0 = not exported yet)
 *   64 + 131072: slots of 16 bytes (kind, flags, box min xyz, box max xyz in sixteenths) + 6 faces of 16x16 RGBA
 *                (order -x, +x, -y, +y, -z, +z)
 */
public final class BlockTextures {
    static final int MAGIC = 0x58455442, HEADER = 64, TABLE = 65536 * 2, ENTRY = 16 + 6 * 1024, MAX_SLOTS = 2048;
    static final long SIZE = HEADER + TABLE + (long) ENTRY * MAX_SLOTS;
    static final int KIND_BOX = 0, KIND_CROSS = 1;
    static final int FLAG_OCCLUDES = 1, FLAG_COLLIDES = 2, FLAG_CUTOUT = 4, FLAG_WOOD = 8;
    private static final Direction[] FACES = { Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP,
        Direction.NORTH, Direction.SOUTH };

    private static MappedByteBuffer buf;
    private static int slots = 0;
    private static boolean failed = false;

    private BlockTextures() {}

    private static boolean open() {
        if (buf != null) return true;
        if (failed) return false;
        try {
            Path path = Path.of(System.getProperty("java.io.tmpdir"), "oot_mc_blocktex.bin");
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                if (raf.length() < SIZE) raf.setLength(SIZE);
                buf = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            }
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(0, 0);
            for (int i = 0; i < TABLE; i += 8) buf.putLong(HEADER + i, 0L);
            buf.putInt(8, 0);
            buf.putInt(4, ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
            VarHandle.releaseFence();
            buf.putInt(0, MAGIC);
            return true;
        } catch (Exception e) {
            OotMc.LOGGER.error("Can't create the block texture share", e);
            failed = true;
            return false;
        }
    }

    /** Export blocks that showed up in published sections (render thread). */
    /** Pseudo block ids Zelda reads Minecraft's 10 mining-crack textures from. */
    static final int CRACK_BASE_ID = 65526;
    private static boolean cracksExported = false;

    public static void process() {
        Minecraft mc = Minecraft.getInstance();
        if (!cracksExported && mc.level != null && open()) {
            cracksExported = true;
            var atlas = mc.getModelManager().getAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            for (int i = 0; i < 10; i++) {
                try {
                    TextureAtlasSprite crack = atlas.getSprite(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("block/destroy_stage_" + i));
                    int[] tex = new int[256];
                    blit(tex, crack, -1);
                    int[][] faces = { tex, tex, tex, tex, tex, tex };
                    writeEntry(CRACK_BASE_ID + i, KIND_BOX, FLAG_CUTOUT, new AABB(0, 0, 0, 1, 1, 1), faces);
                } catch (Exception e) {
                    OotMc.LOGGER.warn("Couldn't export crack texture {}", i, e);
                }
            }
        }
        if (mc.level == null || BlockPalette.PENDING.isEmpty() || !open()) return;
        for (int n = 0; n < 256; n++) {
            Integer id = BlockPalette.PENDING.poll();
            if (id == null) break;
            if (slots >= MAX_SLOTS) continue;
            try {
                export(mc, id);
            } catch (Exception e) {
                OotMc.LOGGER.warn("Couldn't export block {}", Block.stateById(id), e);
            }
        }
    }

    private static void export(Minecraft mc, int id) {
        BlockState s = Block.stateById(id);
        BlockPos zero = BlockPos.ZERO;
        VoxelShape shape, col;
        try {
            shape = s.getShape(EmptyBlockGetter.INSTANCE, zero);
            col = s.getCollisionShape(EmptyBlockGetter.INSTANCE, zero);
        } catch (Exception e) {
            shape = net.minecraft.world.phys.shapes.Shapes.block();
            col = shape;
        }
        boolean collides = !col.isEmpty();
        AABB box = !shape.isEmpty() ? shape.bounds() : collides ? col.bounds() : new AABB(0, 0, 0, 1, 1, 1);
        boolean flat = box.maxY - box.minY <= 0.2;
        int kind = collides || flat ? KIND_BOX : KIND_CROSS;
        if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            // Water and lava: a full see-through block with the still texture (lava glows, water gets its blue)
            boolean water = s.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
            int[] tex = new int[256];
            blit(tex, mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(s), water ? 0x3F76E4 : -1);
            if (water) for (int i = 0; i < 256; i++) tex[i] = (tex[i] & 0x00FFFFFF) | (0xA0 << 24);
            int[][] faces = { tex, tex, tex, tex, tex, tex };
            writeEntry(id, KIND_BOX, water ? FLAG_CUTOUT : 0, new AABB(0, 0, 0, 1, 0.875, 1), faces);
            return;
        }

        BakedModel model = mc.getBlockRenderer().getBlockModel(s);
        RandomSource rnd = RandomSource.create(42L);
        int[][] faces = new int[6][256];
        if (kind == KIND_CROSS) {
            List<BakedQuad> quads = model.getQuads(s, null, rnd);
            int[] tex = new int[256];
            if (quads.isEmpty()) blit(tex, model.getParticleIcon(), -1);
            else blit(tex, quads.get(0).getSprite(), tint(mc, s, quads.get(0)));
            for (int f = 0; f < 6; f++) faces[f] = tex;
        } else {
            List<BakedQuad> general = model.getQuads(s, null, rnd);
            for (int f = 0; f < 6; f++) {
                Direction d = FACES[f];
                List<BakedQuad> quads = new ArrayList<>(model.getQuads(s, d, rnd));
                if (quads.isEmpty()) {
                    for (BakedQuad q : general) if (q.getDirection() == d) quads.add(q);
                }
                if (quads.isEmpty()) {
                    blit(faces[f], model.getParticleIcon(), -1);
                } else {
                    for (BakedQuad q : quads) blit(faces[f], q.getSprite(), tint(mc, s, q));
                }
            }
        }

        boolean cutout = false;
        for (int[] face : faces) for (int px : face) if ((px >>> 24) < 128) cutout = true;
        if (kind == KIND_CROSS) cutout = true;
        if (!cutout) for (int[] face : faces) for (int i = 0; i < 256; i++) face[i] |= 0xFF000000;

        int flags = 0;
        if (s.canOcclude() && Block.isShapeFullBlock(shape) && !cutout) flags |= FLAG_OCCLUDES;
        if (collides) flags |= FLAG_COLLIDES;
        if (cutout) flags |= FLAG_CUTOUT;
        if (s.is(BlockTags.LOGS) || s.is(BlockTags.PLANKS)) flags |= FLAG_WOOD;

        writeEntry(id, kind, flags, box, faces);
    }

    private static void writeEntry(int id, int kind, int flags, AABB box, int[][] faces) {
        if (slots >= MAX_SLOTS) return;
        int slot = slots++;
        int e = HEADER + TABLE + slot * ENTRY;
        buf.put(e, (byte) kind);
        buf.put(e + 1, (byte) flags);
        buf.put(e + 2, (byte) clamp16(Math.floor(box.minX * 16)));
        buf.put(e + 3, (byte) clamp16(Math.floor(box.minY * 16)));
        buf.put(e + 4, (byte) clamp16(Math.floor(box.minZ * 16)));
        buf.put(e + 5, (byte) clamp16(Math.ceil(box.maxX * 16)));
        buf.put(e + 6, (byte) clamp16(Math.ceil(box.maxY * 16)));
        buf.put(e + 7, (byte) clamp16(Math.ceil(box.maxZ * 16)));
        for (int f = 0; f < 6; f++) {
            int o = e + 16 + f * 1024;
            for (int i = 0; i < 256; i++) {
                int argb = faces[f][i];
                buf.put(o + i * 4, (byte) (argb >> 16));
                buf.put(o + i * 4 + 1, (byte) (argb >> 8));
                buf.put(o + i * 4 + 2, (byte) argb);
                buf.put(o + i * 4 + 3, (byte) (argb >>> 24));
            }
        }
        VarHandle.releaseFence();
        buf.putShort(HEADER + id * 2, (short) (slot + 1));
        VarHandle.releaseFence();
        buf.putInt(8, slots);
    }

    private static int clamp16(double v) {
        return (int) Math.max(0, Math.min(16, v));
    }

    private static int tint(Minecraft mc, BlockState s, BakedQuad q) {
        if (!q.isTinted()) return -1;
        try {
            return mc.getBlockColors().getColor(s, null, null, q.getTintIndex());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Alpha-blend a sprite's first frame, resampled to 16x16 and tinted (RGB, -1 = none), over dst (ARGB). */
    private static void blit(int[] dst, TextureAtlasSprite sprite, int tint) {
        SpriteContents c = sprite.contents();
        NativeImage img = ((SpriteContentsAccessor) c).ootmc$originalImage();
        int w = c.width(), h = c.height();
        int tr = tint == -1 ? 255 : (tint >> 16) & 0xFF, tg = tint == -1 ? 255 : (tint >> 8) & 0xFF,
            tb = tint == -1 ? 255 : tint & 0xFF;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int abgr = img.getPixelRGBA(x * w / 16, y * h / 16);
                int a = abgr >>> 24;
                if (a == 0) continue;
                int r = (abgr & 0xFF) * tr / 255, g = ((abgr >> 8) & 0xFF) * tg / 255,
                    b = ((abgr >> 16) & 0xFF) * tb / 255;
                int i = y * 16 + x, old = dst[i];
                int oa = old >>> 24;
                int na = a + oa * (255 - a) / 255;
                if (na == 0) continue;
                int nr = (r * a + ((old >> 16) & 0xFF) * oa * (255 - a) / 255) / na;
                int ng = (g * a + ((old >> 8) & 0xFF) * oa * (255 - a) / 255) / na;
                int nb = (b * a + (old & 0xFF) * oa * (255 - a) / 255) / na;
                dst[i] = (na << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }
    }
}
