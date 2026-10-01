package com.ootmc.client;

import com.ootmc.OotMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Shares the player's Minecraft skin with Zelda so Link is drawn as this Steve (or the player's own skin).
 * File %TEMP%/oot_mc_skin.bin: "SKIN" u32, version u32, width u32, height u32, then 64x64 RGBA.
 */
final class SkinShare {
    private static ResourceLocation lastTexture;
    private static int version = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
    private static int cooldown;

    /** Call on the render thread; re-exports when the skin texture changes (e.g. a downloaded skin arrives). */
    static void update(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || --cooldown > 0) return;
        cooldown = 100;
        ResourceLocation tex = player.getSkin().texture();
        // Built-in default skins are picked per account (Alex, Ari, Zuri...); Link should be Steve unless the player
        // has their own skin
        if (tex.getPath().startsWith("textures/entity/player/")) {
            tex = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
        }
        if (tex.equals(lastTexture)) return;
        AbstractTexture texture = mc.getTextureManager().getTexture(tex);
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w != 64 || h != 64) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            return; // legacy 64x32 skins aren't supported; Zelda keeps its built-in Steve
        }
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            ByteBuffer out = ByteBuffer.allocate(16 + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x4E494B53).putInt(++version).putInt(w).putInt(h);
            out.put(pixels);
            Path dir = Path.of(System.getProperty("java.io.tmpdir"));
            Path tmp = dir.resolve("oot_mc_skin.bin.tmp");
            Files.write(tmp, out.array());
            Files.move(tmp, dir.resolve("oot_mc_skin.bin"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastTexture = tex;
            OotMc.LOGGER.info("Shared skin {} with Zelda", tex);
        } catch (IOException e) {
            OotMc.LOGGER.error("Could not share skin", e);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }
}
