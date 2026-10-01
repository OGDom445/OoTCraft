package com.ootmc.mixin.client;

import com.ootmc.CollisionField;
import com.ootmc.OotMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the crosshair hit Hyrule's invisible ground and walls, so blocks can be placed on Zelda's terrain.
 * The hit points at the air cell in front of the surface; placing there puts the block right on Hyrule.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPickMixin {
    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void ootmc$pickHyrule(float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        com.ootmc.client.OotMcClient.terrainTarget = null;
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.level == null || mc.player == null || mc.level.dimension() != OotMc.HYRULE) return;
        double range = mc.player.blockInteractionRange();
        Vec3 from = cam.getEyePosition(partialTick);
        Vec3 to = from.add(cam.getViewVector(partialTick).scale(range));
        BlockHitResult hit = CollisionField.clip(mc.level, from, to);
        if (hit == null) return;
        HitResult current = mc.hitResult;
        double ours = hit.getLocation().distanceToSqr(from);
        // A real block wins unless Hyrule's ground is clearly in front of it (blocks often sit right on that ground,
        // and the block is what should be mined)
        boolean realBlock = current != null && current.getType() == HitResult.Type.BLOCK;
        double currentDist = current == null ? Double.MAX_VALUE : Math.sqrt(current.getLocation().distanceToSqr(from));
        if (current == null || current.getType() == HitResult.Type.MISS
            || (!realBlock && currentDist * currentDist > ours)
            || (realBlock && Math.sqrt(ours) < currentDist - 0.3)) {
            Vec3 outside = hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.01));
            mc.hitResult = new BlockHitResult(hit.getLocation(), hit.getDirection(), BlockPos.containing(outside), false);
            // The block of Hyrule ground under the crosshair (mining it turns that ground into real blocks). Its
            // collision is a thin 1/8-block skin over the real surface, so step well past it
            Vec3 inside = hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.2));
            com.ootmc.client.OotMcClient.terrainTarget = BlockPos.containing(inside);
            mc.crosshairPickEntity = null;
        }
    }
}
