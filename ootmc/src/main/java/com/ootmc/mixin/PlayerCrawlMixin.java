package com.ootmc.mixin;

import com.ootmc.CollisionField;
import com.ootmc.OotMc;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Crawling through Hyrule's crawlspaces. Walking into a crawlspace mouth (the wall Zelda marks for Link to crawl
 * through) drops Steve into Minecraft's crawl pose; inside, Hyrule's low ceiling keeps him crawling, and he gets up
 * once there's room again. Runs on client and server so both agree on Steve's size.
 */
@Mixin(Player.class)
public abstract class PlayerCrawlMixin {
    @Unique private int ootmc$crawlTicks;

    /** Vanilla only asks real blocks whether a pose fits; Hyrule's ceilings count too. */
    @Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("RETURN"), cancellable = true)
    private void ootmc$hyruleCeiling(Pose pose, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (!cir.getReturnValueZ() || self.level().dimension() != OotMc.HYRULE) return;
        AABB box = self.getDimensions(pose).makeBoundingBox(self.position());
        // Only headroom matters: skip the floor and step-up height (slopes), and keep well clear of the walls he
        // brushes against (Hyrule's collision is a little thick), or standing next to a wall would mean crawling
        double low = pose == Pose.SWIMMING ? 0.35 : 0.65;
        AABB head = new AABB(box.minX + 0.2, box.minY + low, box.minZ + 0.2, box.maxX - 0.2, box.maxY, box.maxZ - 0.2);
        if (CollisionField.blocked(self.level(), head)) cir.setReturnValue(false);
    }

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void ootmc$enterCrawlspace(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self.level().dimension() != OotMc.HYRULE || self.isSpectator() || self.isPassenger()
            || self.getAbilities().flying || self.isInWater()) {
            ootmc$crawlTicks = 0;
            return;
        }
        // Feet-high probe a little ahead of where he's facing
        float yaw = self.getYRot() * Mth.DEG_TO_RAD;
        double ax = -Mth.sin(yaw) * 0.4, az = Mth.cos(yaw) * 0.4;
        AABB b = self.getBoundingBox();
        AABB probe = new AABB(b.minX + ax - 0.1, b.minY + 0.05, b.minZ + az - 0.1, b.maxX + ax + 0.1, b.minY + 0.6,
            b.maxZ + az + 0.1);
        if (CollisionField.touchingCrawlspace(probe)) ootmc$crawlTicks = 6;
        if (ootmc$crawlTicks > 0) {
            ootmc$crawlTicks--;
            self.setPose(Pose.SWIMMING); // on land this is Minecraft's crawl
            ci.cancel();
        }
    }
}
