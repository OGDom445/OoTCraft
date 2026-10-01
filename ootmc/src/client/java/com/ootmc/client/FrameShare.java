package com.ootmc.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.ootmc.OotMc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minecraft's hand + HUD layer, shared with Zelda through %TEMP%/oot_mc_frame.bin (SkyCraft's CPU fallback path).
 * Each frame is read back asynchronously through two pixel-pack buffers, flipped to top-down rows and published under
 * a seqlock. Header: seq u32, width u32, height u32, frame u32, wantW u32, wantH u32 (written by Zelda), flags u32.
 */
public final class FrameShare {
    public static final int MAX_W = 1920, MAX_H = 1080, HEADER = 64;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private static MappedByteBuffer buf;
    private static final int[] pbo = new int[2];
    private static int pboW, pboH, pboIndex;
    private static boolean pboPrimed;
    private static int frame;

    static synchronized boolean open() {
        if (buf != null) return true;
        try {
            Path p = Path.of(System.getProperty("java.io.tmpdir"), "oot_mc_frame.bin");
            FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, HEADER + (long) MAX_W * MAX_H * 4);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            return true;
        } catch (IOException e) {
            OotMc.LOGGER.error("Could not open frame share", e);
            return false;
        }
    }

    /** Window size Zelda would like (its game view), or 0 if unknown. */
    static int wantWidth() { return open() ? buf.getInt(16) : 0; }
    static int wantHeight() { return open() ? buf.getInt(20) : 0; }

    static void setActive(boolean active) {
        if (open()) buf.putInt(24, active ? 1 : 0);
    }

    /** Zelda is currently showing the overlay (no point reading frames back otherwise). */
    static boolean zeldaShowing() {
        return open() && buf.getInt(28) != 0;
    }

    /** Read back the finished frame (call with the frame fully rendered into `target`). */
    static void capture(RenderTarget target) {
        if (!open()) return;
        int w = Math.min(target.width, MAX_W), h = Math.min(target.height, MAX_H);
        if (w <= 0 || h <= 0) return;
        int bytes = w * h * 4;
        if (pbo[0] == 0 || w != pboW || h != pboH) {
            if (pbo[0] != 0) GL15.glDeleteBuffers(pbo);
            GL15.glGenBuffers(pbo);
            for (int id : pbo) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pboW = w;
            pboH = h;
            pboPrimed = false;
        }
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[pboIndex]);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        // Publish the previous frame's pixels (one frame of latency, no GPU stall)
        int other = pboIndex ^ 1;
        pboIndex = other;
        if (pboPrimed) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[other]);
            ByteBuffer px = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, bytes, null);
            if (px != null) {
                int seq = buf.getInt(0);
                if ((seq & 1) != 0) seq++;
                INT.setRelease(buf, 0, seq + 1);
                buf.putInt(4, w);
                buf.putInt(8, h);
                buf.putInt(12, frame++);
                int rowBytes = w * 4;
                ByteBuffer dst = buf.duplicate();
                for (int y = 0; y < h; y++) {
                    ByteBuffer row = px.duplicate();
                    row.position((h - 1 - y) * rowBytes).limit((h - y) * rowBytes);
                    dst.position(HEADER + y * rowBytes);
                    dst.put(row);
                }
                INT.setRelease(buf, 0, seq + 2);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        }
        pboPrimed = true;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }
}
