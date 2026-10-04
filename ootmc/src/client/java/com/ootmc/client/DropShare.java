package com.ootmc.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.ootmc.Bridge;
import com.ootmc.OotMc;
import com.ootmc.mixin.client.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Items lying on the ground (thrown with Q, dropped by mobs, spilled from a broken chest) for Zelda to draw: Zelda
 * draws Hyrule, so without this nothing Minecraft drops would be visible. Shared through %TEMP%/oot_mc_drops.bin:
 * <pre>
 *   0  u32 seq (odd while writing)   4  u32 count   8  i32 scene
 *   64 + i * 32: f32 x, y, z (Zelda units, resting point), f32 spin (radians), f32 bob (Zelda units),
 *                u32 sprite slot, u32 flags (1 = block: drawn as a small cube), u32 entity id
 *   4160: u32 sprite version[256]      5184: sprite pixels[256][16 * 16 * 4] (RGBA)
 * </pre>
 */
public final class DropShare {
    static final int MAX_DROPS = 128, SLOTS = 256, ENTRIES = 64, VERSIONS = ENTRIES + MAX_DROPS * 32,
        PIXELS = VERSIONS + SLOTS * 4, SIZE = PIXELS + SLOTS * 1024;
    private static MappedByteBuffer buf;
    private static boolean failed = false;
    private static int seq = 0;
    private static final Map<Item, Integer> slotOf = new HashMap<>();
    private static final int[] versions = new int[SLOTS];
    private static int nextSlot = 0;

    private static boolean open() {
        if (buf != null) return true;
        if (failed) return false;
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "oot_mc_drops.bin");
            try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
            }
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(0, 0);
            buf.putInt(4, 0);
            return true;
        } catch (IOException e) {
            failed = true;
            OotMc.LOGGER.error("[OoTCraft] can't share dropped items with Zelda", e);
            return false;
        }
    }

    /** The sprite slot holding this item's icon, written the first time it's needed. */
    private static int slot(Minecraft mc, ItemEntity e, BakedModel model) {
        Item item = e.getItem().getItem();
        Integer s = slotOf.get(item);
        if (s != null) return s;
        if (nextSlot >= SLOTS) { // seen too many kinds this session: start over
            slotOf.clear();
            nextSlot = 0;
        }
        int slot = nextSlot++;
        slotOf.put(item, slot);
        TextureAtlasSprite sprite = model.getParticleIcon();
        SpriteContents c = sprite.contents();
        NativeImage img = ((SpriteContentsAccessor) c).ootmc$originalImage();
        int w = c.width(), h = Math.min(c.height(), w); // animated textures: first frame
        int base = PIXELS + slot * 1024;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int abgr = img.getPixelRGBA(x * w / 16, y * h / 16);
                int o = base + (y * 16 + x) * 4;
                buf.put(o, (byte) (abgr & 0xFF));
                buf.put(o + 1, (byte) ((abgr >> 8) & 0xFF));
                buf.put(o + 2, (byte) ((abgr >> 16) & 0xFF));
                buf.put(o + 3, (byte) (abgr >>> 24));
            }
        }
        buf.putInt(VERSIONS + slot * 4, ++versions[slot]);
        return slot;
    }

    /** Every rendered frame: the items near the player, where Minecraft draws them. */
    public static void publish(Minecraft mc, int scene) {
        if (mc.level == null || mc.player == null || mc.level.dimension() != OotMc.HYRULE || !open()) return;
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        var items = mc.level.getEntitiesOfClass(ItemEntity.class, mc.player.getBoundingBox().inflate(32.0));
        buf.putInt(0, ++seq | 1);
        seq |= 1;
        int n = 0;
        for (ItemEntity e : items) {
            if (n >= MAX_DROPS) break;
            if (e.isRemoved() || e.getItem().isEmpty()) continue;
            BakedModel model = mc.getItemRenderer().getModel(e.getItem(), mc.level, null, e.getId());
            int slot = slot(mc, e, model);
            Vec3 pos = e.getPosition(partial);
            float age = e.getAge() + partial;
            int o = ENTRIES + n * 32;
            buf.putFloat(o, Bridge.ootX(scene, pos.x));
            buf.putFloat(o + 4, Bridge.ootY(pos.y));
            buf.putFloat(o + 8, (float) (pos.z * Bridge.UNITS_PER_BLOCK));
            buf.putFloat(o + 12, e.getSpin(partial));
            buf.putFloat(o + 16, (Mth.sin(age / 10.0f + e.bobOffs) * 0.1f + 0.1f) * Bridge.UNITS_PER_BLOCK);
            buf.putInt(o + 20, slot);
            buf.putInt(o + 24, model.isGui3d() ? 1 : 0);
            buf.putInt(o + 28, e.getId());
            n++;
        }
        buf.putInt(4, n);
        buf.putInt(8, scene);
        buf.putInt(0, ++seq); // even: complete
    }
}
