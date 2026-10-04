package com.ootmc;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * OoTCraft shared-memory bridge with Ship of Harkinian. Maps %TEMP%/oot_mc_bridge.bin; the layout mirrors
 * soh/soh/Enhancements/Minecraft/Bridge.h byte for byte (little-endian).
 */
public final class Bridge {
    public static final int MAGIC = 0x4D544F4F, VERSION = 3;
    public static final int RING_CAP = 4096, SECTION_SLOTS = 512, SECTION_VOLUME = 4096, DYNA_MAX_TRIS = 4096;
    public static final int SCENE_SPACING_BLOCKS = 2048, ORIGIN_Y_BLOCKS = 128;
    public static final float UNITS_PER_BLOCK = 30.0f;

    static final int LINK = 64, MC = 128, INPUT = 192;
    static final int RING_SIZE = 128 + 32 * RING_CAP;
    static final int RING_OOT_TO_MC = 256;
    static final int RING_MC_TO_OOT = RING_OOT_TO_MC + RING_SIZE;
    static final int SECTIONS = RING_MC_TO_OOT + RING_SIZE;
    static final int SLOT_SIZE = 64 + 2 * SECTION_VOLUME;
    static final int DYNA = SECTIONS + SLOT_SIZE * SECTION_SLOTS;
    static final int ACTORS = DYNA + 64 + 40 * DYNA_MAX_TRIS;
    public static final int MAX_ACTORS = 64;
    public static final int TOTAL = ACTORS + 64 + 40 * MAX_ACTORS;

    // events
    public static final int EV_PLACE_REQUEST = 1, EV_BREAK_REQUEST = 2, EV_EXPLOSION = 3;
    public static final int EV_KEY = 10, EV_MOUSE_BUTTON = 11, EV_CHAR = 12, EV_GIVE_ITEM = 20;
    public static final int EV_PLAYER_HURT = 21, EV_PLAYER_HEAL = 22, EV_NUDGE = 23, EV_FILL = 4;
    public static final int EV_PLAYER_DIED = 40, EV_HIT_ACTOR = 41, EV_CARVE = 42, EV_BLOCK_BROKEN = 43, EV_BLAST = 44,
        EV_USE_ZELDA_ITEM = 45, EV_VOID_OUT = 46, EV_CELL_AIR = 47, EV_RESET_WORLD = 48,
        EV_SET_GAMEMODE = 49, EV_SET_DIFFICULTY = 50, EV_SET_CHEATS = 51;

    // LinkState flags
    public static final int LINK_FROZEN = 1, LINK_HANDOFF = 2, LINK_ZELDA_INPUT = 4, LINK_IN_PLAY = 8,
        LINK_OPEN_WORLD = 16; // an outdoor area: Minecraft's world surrounds Hyrule's map
    // McState flags
    public static final int MC_IN_HYRULE = 1, MC_SCREEN_OPEN = 2, MC_ON_GROUND = 4, MC_SWIMMING = 8,
        MC_SNEAKING = 16, MC_DEAD = 32, MC_THIRD_PERSON = 64, MC_CRAWLING = 128;

    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG = MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private static Bridge instance;

    private final FileChannel channel;
    private final MappedByteBuffer buf;

    public record LinkState(short scene, int room, int age, float x, float y, float z, short yaw, short pitch,
                            int flags, short health, short healthMax, int frame, int meshVersion,
                            int teleportSeq, float tx, float ty, float tz, short tyaw, short gridYOffset) {}

    /** OoT units the block grid is shifted by in the current scene, so Hyrule's floors sit on block boundaries. */
    public static volatile int gridYOffset = 0;

    public record InputState(int mouseDx, int mouseDy, int wheel, float cursorX, float cursorY, int flags) {}

    public record DynaSnapshot(long seq, int scene, int count, float[] tris) {}

    public record ActorInfo(int id, int type, int category, boolean hostile, float x, float y, float z,
                            float radius, float height, int health) {}

    public static final class Event {
        public int type, blockId, x, y, z, scene, radius;
    }

    public static synchronized Bridge get() {
        if (instance == null) {
            try {
                instance = new Bridge();
            } catch (IOException e) {
                OotMc.LOGGER.error("Could not open OoT bridge", e);
            }
        }
        return instance;
    }

    private Bridge() throws IOException {
        Path p = Path.of(System.getProperty("java.io.tmpdir"), "oot_mc_bridge.bin");
        channel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, TOTAL);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt(0) != MAGIC || buf.getInt(4) != VERSION || buf.getInt(8) != TOTAL) {
            for (int i = 0; i < TOTAL; i += 8) buf.putLong(i, 0);
            buf.putInt(4, VERSION);
            buf.putInt(8, TOTAL);
            INT.setRelease(buf, 0, MAGIC);
        }
        buf.putInt(36, (int) ProcessHandle.current().pid());
        OotMc.LOGGER.info("OoT bridge mapped at {} ({} bytes)", p, TOTAL);
    }

    public long ootHeartbeat() {
        return (long) LONG.getAcquire(buf, 16);
    }

    public void beatMc() {
        LONG.getAndAdd(buf, 24, 1L);
    }

    private int seqBegin(int base) {
        int s = buf.getInt(base);
        if ((s & 1) != 0) s++;
        INT.setRelease(buf, base, s + 1);
        return s;
    }

    private void seqEnd(int base, int s) {
        INT.setRelease(buf, base, s + 2);
    }

    /** Seqlock read; null if Zelda never wrote a state or it couldn't be read consistently. */
    public LinkState readLink() {
        int b = LINK;
        for (int tries = 0; tries < 8; tries++) {
            int s1 = (int) INT.getAcquire(buf, b);
            if ((s1 & 1) != 0) continue;
            if (s1 == 0) return null;
            LinkState ls = new LinkState(buf.getShort(b + 4), buf.get(b + 6) & 0xFF, buf.get(b + 7) & 0xFF,
                buf.getFloat(b + 8), buf.getFloat(b + 12), buf.getFloat(b + 16),
                buf.getShort(b + 20), buf.getShort(b + 22), buf.getInt(b + 24),
                buf.getShort(b + 28), buf.getShort(b + 30), buf.getInt(b + 32), buf.getInt(b + 36),
                buf.getInt(b + 40), buf.getFloat(b + 44), buf.getFloat(b + 48), buf.getFloat(b + 52),
                buf.getShort(b + 56), buf.getShort(b + 58));
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(buf, b) == s1) {
                gridYOffset = ls.gridYOffset();
                return ls;
            }
        }
        return null;
    }

    public InputState readInput() {
        int b = INPUT;
        for (int tries = 0; tries < 8; tries++) {
            int s1 = (int) INT.getAcquire(buf, b);
            if ((s1 & 1) != 0) continue;
            InputState in = new InputState(buf.getInt(b + 4), buf.getInt(b + 8), buf.getInt(b + 12),
                buf.getFloat(b + 16), buf.getFloat(b + 20), buf.getInt(b + 24));
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(buf, b) == s1) return in;
        }
        return null;
    }

    /** Minecraft player state for Zelda (position + camera, OoT units / binary angles). */
    public void writeMc(float x, float y, float z, float eyeHeight, float fov, short yaw, short pitch, int flags,
                        float health, float maxHealth, int frame, short scene, int slot,
                        int destroyStage, int destroyX, int destroyY, int destroyZ) {
        int b = MC;
        int s = seqBegin(b);
        buf.putFloat(b + 4, x);
        buf.putFloat(b + 8, y);
        buf.putFloat(b + 12, z);
        buf.putFloat(b + 16, eyeHeight);
        buf.putFloat(b + 20, fov);
        buf.putShort(b + 24, yaw);
        buf.putShort(b + 26, pitch);
        buf.putInt(b + 28, flags);
        buf.putFloat(b + 32, health);
        buf.putFloat(b + 36, maxHealth);
        buf.putInt(b + 40, frame);
        buf.putShort(b + 44, scene);
        buf.put(b + 46, (byte) slot);
        buf.put(b + 47, (byte) destroyStage); // block being mined: crack stage 0-9, -1 none
        buf.putInt(b + 48, destroyX);
        buf.putInt(b + 52, destroyY);
        buf.putInt(b + 56, destroyZ);
        // When this view was sampled (QPC-based on Windows, same clock as Zelda's), so Zelda can blend between
        // samples per drawn frame instead of showing whichever one happened to be newest
        buf.putInt(b + 60, (int) (System.nanoTime() / 1000L));
        seqEnd(b, s);
    }

    /** Pop one Zelda -> Minecraft event (single consumer: the client thread). */
    public boolean popEvent(Event out) {
        int r = RING_OOT_TO_MC;
        int tail = buf.getInt(r + 64);
        int head = (int) INT.getAcquire(buf, r);
        if (tail == head) return false;
        int e = r + 128 + 32 * (tail & (RING_CAP - 1));
        out.type = buf.get(e) & 0xFF;
        out.blockId = buf.getShort(e + 2) & 0xFFFF;
        out.x = buf.getInt(e + 4);
        out.y = buf.getInt(e + 8);
        out.z = buf.getInt(e + 12);
        out.scene = buf.getShort(e + 16);
        out.radius = buf.getShort(e + 18) & 0xFFFF;
        INT.setRelease(buf, r + 64, tail + 1);
        return true;
    }

    /** Push one Minecraft -> Zelda event (single producer: the client thread). */
    public synchronized void pushEvent(int type, int x, int y, int z) {
        pushEvent(type, x, y, z, -1);
    }

    public synchronized void pushEvent(int type, int x, int y, int z, int scene) {
        pushEvent(type, x, y, z, scene, 0);
    }

    public synchronized void pushEvent(int type, int x, int y, int z, int scene, int radius) {
        int r = RING_MC_TO_OOT;
        int head = buf.getInt(r);
        int tail = (int) INT.getAcquire(buf, r + 64);
        if (head - tail >= RING_CAP) return;
        int e = r + 128 + 32 * (head & (RING_CAP - 1));
        buf.put(e, (byte) type);
        buf.putInt(e + 4, x);
        buf.putInt(e + 8, y);
        buf.putInt(e + 12, z);
        buf.putShort(e + 2, (short) 0);
        buf.putShort(e + 16, (short) scene);
        buf.putShort(e + 18, (short) radius);
        INT.setRelease(buf, r, head + 1);
    }

    /** Publish one 16^3 section; ids indexed (y*16+z)*16+x. */
    public void writeSection(int slot, int sx, int sy, int sz, int scene, short[] ids, int nonAir) {
        int s = SECTIONS + slot * SLOT_SIZE;
        int gen = buf.getInt(s);
        if ((gen & 1) != 0) gen++;
        INT.setRelease(buf, s, gen + 1);
        buf.putInt(s + 4, sx);
        buf.putInt(s + 8, sy);
        buf.putInt(s + 12, sz);
        buf.putShort(s + 16, (short) scene);
        buf.putShort(s + 18, (short) nonAir);
        for (int i = 0; i < SECTION_VOLUME; i++) buf.putShort(s + 64 + i * 2, ids[i]);
        INT.setRelease(buf, s, gen + 2);
    }

    /** Moving collision near the player; null if unchanged since `lastSeq`. */
    public DynaSnapshot readDyna(long lastSeq) {
        int b = DYNA;
        for (int tries = 0; tries < 4; tries++) {
            int s1 = (int) INT.getAcquire(buf, b);
            if ((s1 & 1) != 0) continue;
            if (s1 == lastSeq) return null;
            int count = Math.max(0, Math.min(buf.getInt(b + 4), DYNA_MAX_TRIS)); // never trust shared memory
            int scene = buf.getShort(b + 8);
            float[] tris = new float[count * 9];
            for (int i = 0; i < count; i++)
                for (int k = 0; k < 9; k++) tris[i * 9 + k] = buf.getFloat(b + 64 + i * 40 + k * 4);
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(buf, b) == s1) return new DynaSnapshot(s1, scene, count, tris);
        }
        return null;
    }

    /** Zelda actors near the player (for stand-in entities); null if unreadable. */
    public java.util.List<ActorInfo> readActors() {
        int b = ACTORS;
        for (int tries = 0; tries < 4; tries++) {
            int s1 = (int) INT.getAcquire(buf, b);
            if ((s1 & 1) != 0) continue;
            int count = Math.max(0, Math.min(buf.getInt(b + 4), MAX_ACTORS));
            java.util.List<ActorInfo> list = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int o = b + 64 + i * 40;
                list.add(new ActorInfo(buf.getInt(o), buf.getShort(o + 4), buf.get(o + 6) & 0xFF, (buf.get(o + 7) & 1) != 0,
                    buf.getFloat(o + 8), buf.getFloat(o + 12), buf.getFloat(o + 16), buf.getFloat(o + 20),
                    buf.getFloat(o + 24), buf.getShort(o + 28)));
            }
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(buf, b) == s1) return list;
        }
        return null;
    }

    // ---- coordinate mapping (1 block = 30 OoT units)
    public static int originX(int scene) {
        return scene * SCENE_SPACING_BLOCKS;
    }

    public static double mcX(int scene, float ootX) { return originX(scene) + ootX / UNITS_PER_BLOCK; }
    public static double mcY(float ootY) { return ORIGIN_Y_BLOCKS + (ootY - gridYOffset) / UNITS_PER_BLOCK; }
    public static double mcZ(float ootZ) { return ootZ / UNITS_PER_BLOCK; }

    public static float ootX(int scene, double mcX) { return (float) ((mcX - originX(scene)) * UNITS_PER_BLOCK); }
    public static float ootY(double mcY) { return (float) ((mcY - ORIGIN_Y_BLOCKS) * UNITS_PER_BLOCK + gridYOffset); }
    public static float ootZ(double mcZ) { return (float) (mcZ * UNITS_PER_BLOCK); }

    /** OoT binary angle (counter-clockwise from +Z) <-> Minecraft yaw (clockwise degrees from +Z). */
    public static float mcYaw(short ootYaw) { return -ootYaw * 360f / 65536f; }
    public static short ootYaw(float mcYaw) { return (short) Math.round(-mcYaw * 65536f / 360f); }
    /** Minecraft pitch (degrees, + looks down) -> OoT pitch (binary angle, + looks up). */
    public static short ootPitch(float mcPitch) { return (short) Math.round(-mcPitch * 65536f / 360f); }
}
