package com.ootmc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.ootmc.CollisionField;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds Hyrule's invisible collision to every entity movement in the Hyrule dimension (client and server).
 * collectColliders feeds both normal movement and step-up, so slopes and small ledges are climbed like stairs.
 */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> ootmc$addHyrule(List<VoxelShape> colliders, Entity entity, Level level,
                                                   List<VoxelShape> entityShapes, AABB box) {
        if (level == null) return colliders;
        List<VoxelShape> out = new ArrayList<>(colliders);
        int before = out.size();
        CollisionField.collect(level, box.inflate(1.0E-7), out);
        return out.size() == before ? colliders : out;
    }
}
