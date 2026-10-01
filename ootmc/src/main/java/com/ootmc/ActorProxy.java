package com.ootmc;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Invisible stand-in for a Zelda actor (enemy, boss, grass, pot, NPC). It has the actor's hitbox so Minecraft's
 * crosshair, swords, sweeps, bows and explosions can hit it; the damage Minecraft computes is forwarded to Zelda,
 * which plays the actor's real reaction. The stand-in itself never dies or moves on its own.
 */
public class ActorProxy extends LivingEntity {
    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(ActorProxy.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(ActorProxy.class, EntityDataSerializers.FLOAT);

    public int ootId;
    public boolean hostile;
    private long lastHitTick = -100;
    private float lastHitAmount;

    public ActorProxy(EntityType<? extends ActorProxy> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setSilent(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(WIDTH, 0.6f);
        builder.define(HEIGHT, 1.8f);
    }

    public void setSize(float width, float height) {
        if (Math.abs(entityData.get(WIDTH) - width) > 0.01f || Math.abs(entityData.get(HEIGHT) - height) > 0.01f) {
            entityData.set(WIDTH, width);
            entityData.set(HEIGHT, height);
            refreshDimensions();
        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (WIDTH.equals(key) || HEIGHT.equals(key)) refreshDimensions();
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || amount <= 0) return false;
        // Minecraft-style invulnerability frames: a stronger hit can still land during them
        long now = level().getGameTime();
        if (now - lastHitTick < 10 && amount <= lastHitAmount) return false;
        lastHitTick = now;
        lastHitAmount = amount;

        int kind = 0; // melee
        Entity direct = source.getDirectEntity();
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            kind = 3;
        } else if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            kind = (direct != null && direct.isOnFire()) ? 2 : 1;
        } else if (source.is(DamageTypeTags.IS_FIRE)) {
            kind = 2;
        }
        Bridge bridge = Bridge.get();
        if (bridge != null) bridge.pushEvent(Bridge.EV_HIT_ACTOR, ootId, kind, Math.round(amount * 100));
        return true;
    }

    @Override
    public void aiStep() {
        // position is driven entirely by Zelda
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return List.of();
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }
}
