package com.ootmc;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hyrule's level geometry as invisible collision for Minecraft (SkyCraft's "CollisionField").
 *
 * Zelda exports its scene collision triangles; they are voxelized lazily per 16^3 section at 1/8-block
 * resolution and merged into boxes. These are not blocks: you can build right against them, and slopes become
 * 1/8-block micro-steps that Minecraft's 0.6-block step-up climbs smoothly.
 */
public final class CollisionField {
    static final int RES = 8;           // cells per block for the static scene
    static final int N = 16 * RES;      // cells per section edge
    static final VoxelShape[] NONE = new VoxelShape[0];

    public static final class Mesh {
        public final int scene;
        final float[] tris;   // Minecraft coords, 9 per triangle
        final int[] flags;
        /** Lowest point of Hyrule's ground in this scene (Minecraft Y): nothing below it but void */
        public float lowestY = -1e9f;
        final Map<Long, int[]> buckets = new HashMap<>();
        /** Fingerprint of each section's triangles, so a reload only rebuilds the sections that changed */
        final Map<Long, Integer> bucketHash = new HashMap<>();
        final List<short[]> water = new ArrayList<>(); // xMin, ySurface, zMin, xLength, zLength (OoT units)
        /** Dug blocks by section: Zelda's collision inside them is gone, whatever the cut left (block x, y, z) */
        final Map<Long, List<int[]>> dug = new HashMap<>();

        public int scene() {
            return scene;
        }

        Mesh(int scene, float[] tris, int[] flags) {
            this.scene = scene;
            this.tris = tris;
            this.flags = flags;
        }
    }

    private static volatile Mesh mesh;
    private static volatile int loadedMeshVersion = -1;
    private static final Map<Long, Section> cache = new ConcurrentHashMap<>();

    record Section(AABB[] boxes, VoxelShape[] shapes) {}

    private record Rect(int x0, int x1, int z0, int z1) {}

    // ---- moving collision (Zelda's dynapoly actors near the player), rebuilt when it changes
    private static volatile Section dyna = new Section(new AABB[0], NONE);
    private static volatile long dynaSeq = -1;
    private static int dynaHash = 0;
    private static double dynaCenterX, dynaCenterY, dynaCenterZ;

    public static Mesh mesh() {
        return mesh;
    }

    /** Load the mesh Zelda exported for this scene if it changed. Safe to call from any thread. */
    /** Nearest point on a hookshot-target surface of Hyrule along a segment, or null. */
    public static net.minecraft.world.phys.Vec3 clipHookshot(net.minecraft.world.phys.Vec3 from,
                                                           net.minecraft.world.phys.Vec3 to) {
        Mesh m = mesh;
        if (m == null) return null;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double bestT = Double.MAX_VALUE;
        float[] t = m.tris;
        for (int i = 0; i < m.flags.length; i++) {
            if ((m.flags[i] & 1) == 0) continue;
            // Moller-Trumbore
            double ax = t[i * 9], ay = t[i * 9 + 1], az = t[i * 9 + 2];
            double e1x = t[i * 9 + 3] - ax, e1y = t[i * 9 + 4] - ay, e1z = t[i * 9 + 5] - az;
            double e2x = t[i * 9 + 6] - ax, e2y = t[i * 9 + 7] - ay, e2z = t[i * 9 + 8] - az;
            double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-9) continue;
            double inv = 1.0 / det;
            double sx = from.x - ax, sy = from.y - ay, sz = from.z - az;
            double u = (sx * px + sy * py + sz * pz) * inv;
            if (u < 0 || u > 1) continue;
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double v = (dx * qx + dy * qy + dz * qz) * inv;
            if (v < 0 || u + v > 1) continue;
            double tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
            if (tt >= 0 && tt <= 1 && tt < bestT) bestT = tt;
        }
        return bestT == Double.MAX_VALUE ? null : new net.minecraft.world.phys.Vec3(from.x + dx * bestT, from.y + dy * bestT, from.z + dz * bestT);
    }

    /** Is this box touching one of Hyrule's climbable surfaces (ladders, vines, climbable walls)? */
    public static boolean touchingClimbable(net.minecraft.world.phys.AABB box) {
        return touching(box, 2);
    }

    /** Is this box touching the mouth of one of Hyrule's crawlspaces? */
    public static boolean touchingCrawlspace(net.minecraft.world.phys.AABB box) {
        return touching(box, 4);
    }

    /** Does Hyrule's collision (scene or moving platforms) overlap this box? */
    public static boolean blocked(Level level, AABB box) {
        List<VoxelShape> shapes = new ArrayList<>();
        collect(level, box, shapes);
        for (VoxelShape shape : shapes) {
            for (AABB b : shape.toAabbs()) if (b.intersects(box)) return true;
        }
        return false;
    }

    private static boolean touching(net.minecraft.world.phys.AABB box, int flag) {
        Mesh m = mesh;
        if (m == null) return false;
        double cx = (box.minX + box.maxX) / 2, cy = (box.minY + box.maxY) / 2, cz = (box.minZ + box.maxZ) / 2;
        double hx = (box.maxX - box.minX) / 2, hy = (box.maxY - box.minY) / 2, hz = (box.maxZ - box.minZ) / 2;
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (int sx = floorDiv16((float) box.minX); sx <= floorDiv16((float) box.maxX); sx++)
            for (int sy = floorDiv16((float) box.minY); sy <= floorDiv16((float) box.maxY); sy++)
                for (int sz = floorDiv16((float) box.minZ); sz <= floorDiv16((float) box.maxZ); sz++) {
                    int[] list = m.buckets.get(OotMc.sectionKey(sx, sy, sz));
                    if (list == null) continue;
                    for (int k = 1; k <= list[0]; k++) {
                        int i = list[k];
                        if ((m.flags[i] & flag) == 0 || !seen.add(i)) continue;
                        float[] t = m.tris;
                        double ax = t[i * 9], ay = t[i * 9 + 1], az = t[i * 9 + 2];
                        double bx = t[i * 9 + 3], by = t[i * 9 + 4], bz = t[i * 9 + 5];
                        double qx = t[i * 9 + 6], qy = t[i * 9 + 7], qz = t[i * 9 + 8];
                        if (Math.max(ax, Math.max(bx, qx)) < box.minX || Math.min(ax, Math.min(bx, qx)) > box.maxX
                            || Math.max(ay, Math.max(by, qy)) < box.minY || Math.min(ay, Math.min(by, qy)) > box.maxY
                            || Math.max(az, Math.max(bz, qz)) < box.minZ || Math.min(az, Math.min(bz, qz)) > box.maxZ) continue;
                        double ux = bx - ax, uy = by - ay, uz = bz - az, vx = qx - ax, vy = qy - ay, vz = qz - az;
                        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (len < 1e-6) continue;
                        nx /= len; ny /= len; nz /= len;
                        double dist = Math.abs(nx * (cx - ax) + ny * (cy - ay) + nz * (cz - az));
                        double reach = Math.abs(nx) * hx + Math.abs(ny) * hy + Math.abs(nz) * hz;
                        if (dist <= reach) return true;
                    }
                }
        return false;
    }

    /** Mined Hyrule ground: keep the old collision until the replacement blocks have reached the client. */
    public static volatile long holdReloadUntil = 0;

    public static synchronized void ensureScene(int scene, int meshVersion) {
        Mesh m = mesh;
        if (m != null && m.scene == scene && meshVersion == loadedMeshVersion) return;
        if (m != null && m.scene == scene && System.currentTimeMillis() < holdReloadUntil) return;
        long started = System.nanoTime();
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "oot_mc_mesh_" + scene + ".bin");
        if (!Files.exists(file)) return;
        try {
            ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x4D544F4F || b.getInt(4) != 2) return;
            int count = b.getInt(12), waterCount = b.getInt(16);
            // The file comes from %TEMP%: make sure the counts match its size before allocating anything
            if (count < 0 || waterCount < 0 || count > 2_000_000 || waterCount > 10_000
                || 20L + count * 40L + waterCount * 12L > b.capacity()) {
                OotMc.LOGGER.warn("Ignoring malformed collision mesh for scene {}", scene);
                return;
            }
            float[] tris = new float[count * 9];
            int[] flags = new int[count];
            double ox = Bridge.originX(scene), oy = Bridge.ORIGIN_Y_BLOCKS;
            for (int i = 0; i < count; i++) {
                int o = 20 + i * 40;
                for (int v = 0; v < 3; v++) {
                    tris[i * 9 + v * 3] = (float) (ox + b.getFloat(o + v * 12) / Bridge.UNITS_PER_BLOCK);
                    tris[i * 9 + v * 3 + 1] = (float) Bridge.mcY(b.getFloat(o + v * 12 + 4));
                    tris[i * 9 + v * 3 + 2] = (float) (b.getFloat(o + v * 12 + 8) / Bridge.UNITS_PER_BLOCK);
                }
                flags[i] = b.getInt(o + 36);
            }
            Mesh nm = new Mesh(scene, tris, flags);
            float lowest = Float.MAX_VALUE;
            for (int i = 0; i < count * 3; i++) lowest = Math.min(lowest, tris[i * 3 + 1]);
            nm.lowestY = count > 0 ? lowest : -1e9f;
            int wo = 20 + count * 40;
            for (int i = 0; i < waterCount; i++) {
                short[] w = new short[5];
                for (int k = 0; k < 5; k++) w[k] = b.getShort(wo + i * 12 + k * 2);
                nm.water.add(w);
            }
            int dugAt = wo + waterCount * 12;
            if (b.capacity() >= dugAt + 8 && b.getInt(dugAt) == 0x31475544) {
                int dugCount = b.getInt(dugAt + 4);
                if (dugCount >= 0 && dugCount <= 4_000_000 && dugAt + 8L + dugCount * 12L <= b.capacity()) {
                    for (int i = 0; i < dugCount; i++) {
                        int[] c = { b.getInt(dugAt + 8 + i * 12), b.getInt(dugAt + 12 + i * 12), b.getInt(dugAt + 16 + i * 12) };
                        long key = OotMc.sectionKey(Math.floorDiv(c[0], 16), Math.floorDiv(c[1], 16), Math.floorDiv(c[2], 16));
                        nm.dug.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
                    }
                }
            }
            // bucket triangles by every section their bounds touch
            for (int i = 0; i < count; i++) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                for (int v = 0; v < 3; v++) {
                    float x = tris[i * 9 + v * 3], y = tris[i * 9 + v * 3 + 1], z = tris[i * 9 + v * 3 + 2];
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                }
                // include one cell of thickening below/behind the surface
                for (int sx = floorDiv16(minX - 0.5f); sx <= floorDiv16(maxX + 0.5f); sx++)
                    for (int sy = floorDiv16(minY - 0.5f); sy <= floorDiv16(maxY + 0.5f); sy++)
                        for (int sz = floorDiv16(minZ - 0.5f); sz <= floorDiv16(maxZ + 0.5f); sz++) {
                            long key = OotMc.sectionKey(sx, sy, sz);
                            int[] list = nm.buckets.get(key);
                            if (list == null) {
                                list = new int[] { 0, 0, 0, 0, 0 };
                            }
                            if (list[0] + 1 >= list.length) {
                                list = java.util.Arrays.copyOf(list, list.length * 2);
                            }
                            list[++list[0]] = i;
                            nm.buckets.put(key, list);
                        }
            }
            for (Map.Entry<Long, int[]> e : nm.buckets.entrySet()) {
                int[] list = e.getValue();
                int h = list[0];
                for (int k = 1; k <= list[0]; k++) {
                    int t = list[k];
                    for (int c = 0; c < 9; c++) h = h * 31 + Float.floatToIntBits(tris[t * 9 + c]);
                    h = h * 31 + flags[t];
                }
                for (int[] c : nm.dug.getOrDefault(e.getKey(), List.of())) h = (h * 31 + c[0]) * 31 + c[1] * 7 + c[2];
                nm.bucketHash.put(e.getKey(), h);
            }
            // Digging changes a few sections: keep every other section's collision instead of re-voxelizing them
            // all on the game thread (that was the hitch after each mined block)
            int kept = 0;
            if (m != null && m.scene == scene) {
                for (var it = cache.entrySet().iterator(); it.hasNext(); ) {
                    Long key = it.next().getKey();
                    if (java.util.Objects.equals(m.bucketHash.get(key), nm.bucketHash.get(key))) kept++;
                    else it.remove();
                }
            } else {
                cache.clear();
            }
            mesh = nm;
            loadedMeshVersion = meshVersion;
            OotMc.LOGGER.info("Collision field for scene {}: {} triangles, {} sections ({} kept), {} water boxes ({} ms)",
                scene, count, nm.buckets.size(), kept, waterCount, (System.nanoTime() - started) / 1_000_000);
        } catch (IOException e) {
            OotMc.LOGGER.error("Reading collision mesh for scene {}", scene, e);
        }
    }

    private static int floorDiv16(float v) {
        return Math.floorDiv((int) Math.floor(v), 16);
    }

    /** Collision boxes overlapping the query (static scene + moving platforms). */
    public static void collect(Level level, AABB query, List<VoxelShape> out) {
        if (level.dimension() != OotMc.HYRULE) return;
        Mesh m = mesh;
        if (m != null) {
            int sx0 = Math.floorDiv((int) Math.floor(query.minX), 16), sx1 = Math.floorDiv((int) Math.floor(query.maxX), 16);
            int sy0 = Math.floorDiv((int) Math.floor(query.minY), 16), sy1 = Math.floorDiv((int) Math.floor(query.maxY), 16);
            int sz0 = Math.floorDiv((int) Math.floor(query.minZ), 16), sz1 = Math.floorDiv((int) Math.floor(query.maxZ), 16);
            for (int sx = sx0; sx <= sx1; sx++)
                for (int sy = sy0; sy <= sy1; sy++)
                    for (int sz = sz0; sz <= sz1; sz++) {
                        Section s = section(m, sx, sy, sz);
                        for (int i = 0; i < s.boxes.length; i++) {
                            if (s.boxes[i].intersects(query)) out.add(s.shapes[i]);
                        }
                    }
        }
        Section d = dyna;
        for (int i = 0; i < d.boxes.length; i++) {
            if (d.boxes[i].intersects(query)) out.add(d.shapes[i]);
        }
    }

    /** First hit of a ray against Hyrule's collision (static scene + moving platforms), or null. */
    public static net.minecraft.world.phys.BlockHitResult clip(Level level, net.minecraft.world.phys.Vec3 from,
                                                              net.minecraft.world.phys.Vec3 to) {
        if (level.dimension() != OotMc.HYRULE) return null;
        List<VoxelShape> shapes = new ArrayList<>();
        collect(level, new AABB(from, to).inflate(0.01), shapes);
        net.minecraft.world.phys.BlockHitResult best = null;
        double bestDist = Double.MAX_VALUE;
        for (VoxelShape shape : shapes) {
            net.minecraft.world.phys.BlockHitResult h = shape.clip(from, to, net.minecraft.core.BlockPos.ZERO);
            if (h != null) {
                double d = h.getLocation().distanceToSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = h;
                }
            }
        }
        return best;
    }

    /** True if the block cell's centre is inside Zelda's level geometry (used to size water volumes). */
    public static boolean solidAt(int x, int y, int z) {
        Mesh m = mesh;
        if (m == null) return false;
        Section s = section(m, Math.floorDiv(x, 16), Math.floorDiv(y, 16), Math.floorDiv(z, 16));
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        for (AABB b : s.boxes) {
            if (b.contains(cx, cy, cz) || (b.minX <= cx && b.maxX >= cx && b.minZ <= cz && b.maxZ >= cz && b.minY >= y && b.maxY <= y + 1)) {
                return true;
            }
        }
        return false;
    }

    private static Section section(Mesh m, int sx, int sy, int sz) {
        long key = OotMc.sectionKey(sx, sy, sz);
        Section s = cache.get(key);
        if (s != null) return s;
        int[] list = m.buckets.get(key);
        if (list == null) {
            s = new Section(new AABB[0], NONE);
        } else {
            // Crawlspace walls (flag 4) aren't solid: they're where Steve crawls in
            int[] solid = new int[list[0] + 1];
            for (int k = 1; k <= list[0]; k++) if ((m.flags[list[k]] & 4) == 0) solid[++solid[0]] = list[k];
            s = voxelize(m.tris, solid, 1, solid[0], sx * 16.0, sy * 16.0, sz * 16.0, RES, 2, m.dug.get(key));
        }
        if (cache.size() > 4000) cache.clear();
        cache.put(key, s);
        return s;
    }

    /** Rebuild the moving-collision boxes from Zelda's dyna region (call ~20 Hz). */
    public static void updateDyna(Bridge bridge, double px, double py, double pz) {
        Bridge.DynaSnapshot snap = bridge.readDyna(dynaSeq);
        if (snap == null) return; // unchanged
        dynaSeq = snap.seq();
        int hash = java.util.Arrays.hashCode(snap.tris()) * 31 + snap.count();
        double movedSq = (px - dynaCenterX) * (px - dynaCenterX) + (py - dynaCenterY) * (py - dynaCenterY)
            + (pz - dynaCenterZ) * (pz - dynaCenterZ);
        if (hash == dynaHash && movedSq < 64) return; // same platforms, player still well inside the built cube
        dynaHash = hash;
        dynaCenterX = px;
        dynaCenterY = py;
        dynaCenterZ = pz;
        Mesh m = mesh;
        int scene = m != null ? m.scene : snap.scene();
        double ox = Bridge.originX(scene), oy = Bridge.ORIGIN_Y_BLOCKS;
        float[] tris = new float[snap.count() * 9];
        int[] idx = new int[snap.count() + 1];
        idx[0] = snap.count();
        for (int i = 0; i < snap.count(); i++) {
            for (int v = 0; v < 3; v++) {
                tris[i * 9 + v * 3] = (float) (ox + snap.tris()[i * 9 + v * 3] / Bridge.UNITS_PER_BLOCK);
                tris[i * 9 + v * 3 + 1] = (float) Bridge.mcY(snap.tris()[i * 9 + v * 3 + 1]);
                tris[i * 9 + v * 3 + 2] = (float) (snap.tris()[i * 9 + v * 3 + 2] / Bridge.UNITS_PER_BLOCK);
            }
            idx[i + 1] = i;
        }
        // 32-block cube around the player at 1/4-block resolution
        double cx = Math.floor(px) - 16, cy = Math.floor(py) - 16, cz = Math.floor(pz) - 16;
        dyna = snap.count() == 0 ? new Section(new AABB[0], NONE) : voxelize(tris, idx, 1, idx[0], cx, cy, cz, 4, 1);
    }

    /**
     * Voxelize triangles tris[list[from..to]] into a 128^3 grid at `res` cells per block starting at (ox,oy,oz),
     * project along each triangle's dominant axis, thicken `thick` cells into the surface, and greedily merge.
     */
    static Section voxelize(float[] tris, int[] list, int from, int to, double ox, double oy, double oz, int res, int thick) {
        return voxelize(tris, list, from, to, ox, oy, oz, res, thick, null);
    }

    /** As above, then empties the dug blocks ("clear", block coordinates): mined Hyrule ground is always open. */
    static Section voxelize(float[] tris, int[] list, int from, int to, double ox, double oy, double oz, int res, int thick,
                            List<int[]> clear) {
        final int n = 128;
        long[] bits = new long[n * n * n / 64];
        double[] p = new double[9];
        for (int li = from; li <= to; li++) {
            int t = list[li];
            for (int k = 0; k < 9; k++) {
                double o = (k % 3 == 0) ? ox : (k % 3 == 1) ? oy : oz;
                p[k] = (tris[t * 9 + k] - o) * res;
            }
            double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
            double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
            if (ax + ay + az < 1e-9) continue;
            int d = (ax >= ay && ax >= az) ? 0 : (ay >= az ? 1 : 2); // dominant axis
            int a = d == 0 ? 1 : 0, b = d == 2 ? 1 : 2;               // projection axes
            double[] nn = { nx, ny, nz };
            double nd = nn[d], na = nn[a], nb = nn[b];
            double a0 = p[a], b0 = p[b], a1 = p[3 + a], b1 = p[3 + b], a2 = p[6 + a], b2 = p[6 + b];
            int ia0 = Math.max(0, (int) Math.floor(Math.min(a0, Math.min(a1, a2))));
            int ia1 = Math.min(n - 1, (int) Math.ceil(Math.max(a0, Math.max(a1, a2))));
            int ib0 = Math.max(0, (int) Math.floor(Math.min(b0, Math.min(b1, b2))));
            int ib1 = Math.min(n - 1, (int) Math.ceil(Math.max(b0, Math.max(b1, b2))));
            if (ia0 > ia1 || ib0 > ib1) continue;
            double area = (a1 - a0) * (b2 - b0) - (b1 - b0) * (a2 - a0);
            if (Math.abs(area) < 1e-12) continue;
            double sign = Math.signum(area);
            int inward = nd > 0 ? -1 : 1; // thicken against the normal
            final double eps = 1e-4 * Math.abs(area);
            for (int ia = ia0; ia <= ia1; ia++) {
                double ca = ia + 0.5;
                for (int ib = ib0; ib <= ib1; ib++) {
                    double cb = ib + 0.5;
                    double w0 = ((a1 - ca) * (b2 - cb) - (b1 - cb) * (a2 - ca)) * sign;
                    double w1 = ((a2 - ca) * (b0 - cb) - (b2 - cb) * (a0 - ca)) * sign;
                    double w2 = ((a0 - ca) * (b1 - cb) - (b0 - cb) * (a1 - ca)) * sign;
                    if (w0 < -eps || w1 < -eps || w2 < -eps) continue;
                    double depth = p[d] - (na * (ca - a0) + nb * (cb - b0)) / nd;
                    // a surface exactly on a cell boundary belongs to the cell just inside it, so floors are flush
                    int id = (int) Math.floor(depth + inward * 0.01);
                    for (int k = 0; k <= thick; k++) {
                        int dd = id + k * inward;
                        if (dd < 0 || dd >= n) continue;
                        int[] c = new int[3];
                        c[d] = dd; c[a] = ia; c[b] = ib;
                        int index = (c[1] * n + c[2]) * n + c[0];
                        bits[index >>> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
        if (clear != null) {
            for (int[] c : clear) {
                int x0 = (int) Math.round((c[0] - ox) * res), y0 = (int) Math.round((c[1] - oy) * res), z0 = (int) Math.round((c[2] - oz) * res);
                for (int y = Math.max(0, y0); y < Math.min(n, y0 + res); y++)
                    for (int z = Math.max(0, z0); z < Math.min(n, z0 + res); z++)
                        for (int x = Math.max(0, x0); x < Math.min(n, x0 + res); x++) {
                            int index = (y * n + z) * n + x;
                            bits[index >>> 6] &= ~(1L << (index & 63));
                        }
            }
        }
        return merge(bits, n, ox, oy, oz, res);
    }

    private static boolean get(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /** Greedy merge: x runs -> z rows -> identical rectangles stacked in y. */
    private static Section merge(long[] bits, int n, double ox, double oy, double oz, int res) {
        List<AABB> boxes = new ArrayList<>();
        Map<Rect, int[]> open = new HashMap<>(); // rect -> {y0, yLast}
        for (int y = 0; y < n; y++) {
            // 2D merge for this layer
            List<Rect> rects = new ArrayList<>();
            Map<Long, Integer> openRows = new HashMap<>(); // (x0,x1) -> index in rects, open at previous z
            Map<Long, Integer> nextOpen = new HashMap<>();
            for (int z = 0; z < n; z++) {
                nextOpen.clear();
                int x = 0;
                while (x < n) {
                    int base = (y * n + z) * n;
                    if (!get(bits, base + x)) { x++; continue; }
                    int x0 = x;
                    while (x < n && get(bits, base + x)) x++;
                    long rowKey = ((long) x0 << 16) | x;
                    Integer ri = openRows.get(rowKey);
                    if (ri != null) {
                        Rect r = rects.get(ri);
                        rects.set(ri, new Rect(r.x0, r.x1, r.z0, z + 1));
                        nextOpen.put(rowKey, ri);
                    } else {
                        rects.add(new Rect(x0, x, z, z + 1));
                        nextOpen.put(rowKey, rects.size() - 1);
                    }
                }
                Map<Long, Integer> tmp = openRows;
                openRows = nextOpen;
                nextOpen = tmp;
            }
            // extend identical rectangles from the previous layer upward
            Map<Rect, int[]> nextLayer = new HashMap<>();
            for (Rect r : rects) {
                int[] span = open.remove(r);
                if (span != null && span[1] == y - 1) {
                    span[1] = y;
                    nextLayer.put(r, span);
                } else {
                    nextLayer.put(r, new int[] { y, y });
                }
            }
            for (Map.Entry<Rect, int[]> e : open.entrySet()) emit(boxes, e.getKey(), e.getValue(), ox, oy, oz, res);
            open = nextLayer;
        }
        for (Map.Entry<Rect, int[]> e : open.entrySet()) emit(boxes, e.getKey(), e.getValue(), ox, oy, oz, res);
        AABB[] arr = boxes.toArray(new AABB[0]);
        VoxelShape[] shapes = new VoxelShape[arr.length];
        for (int i = 0; i < arr.length; i++) shapes[i] = Shapes.create(arr[i]);
        return new Section(arr, shapes);
    }

    private static void emit(List<AABB> out, Rect r, int[] span, double ox, double oy, double oz, int res) {
        out.add(new AABB(ox + (double) r.x0() / res, oy + (double) span[0] / res, oz + (double) r.z0() / res,
            ox + (double) r.x1() / res, oy + (double) (span[1] + 1) / res, oz + (double) r.z1() / res));
    }
}
