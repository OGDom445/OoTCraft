package com.ootmc;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Link's items as Minecraft items. Getting an item in Zelda puts a matching item in Steve's inventory; using it
 * (right click) does the Zelda thing: the hookshot and longshot grapple natively in Minecraft, bombs come out as
 * real Zelda bombs, and everything else hands control to Link to use it (F5 comes back).
 */
public final class ZeldaItems {
    public static final String TAG = "ootmc_item";

    record Def(Item base, String name, String use) {}

    private static final Map<Integer, Def> DEFS = new HashMap<>();
    static {
        DEFS.put(0x00, new Def(Items.STICK, "Deku Stick", "Link swings it (it also hits Zelda enemies as a weapon)"));
        DEFS.put(0x01, new Def(Items.BROWN_DYE, "Deku Nut", "Link throws a nut: stuns enemies"));
        DEFS.put(0x02, new Def(Items.GUNPOWDER, "Bombs", "Throws a Zelda bomb"));
        DEFS.put(0x03, new Def(Items.BOW, "Fairy Bow", "Link aims Zelda's bow"));
        DEFS.put(0x04, new Def(Items.FIRE_CHARGE, "Fire Arrows", "Link aims fire arrows"));
        DEFS.put(0x05, new Def(Items.BLAZE_POWDER, "Din's Fire", "Link casts Din's Fire"));
        DEFS.put(0x06, new Def(Items.STRING, "Fairy Slingshot", "Link aims the slingshot"));
        DEFS.put(0x07, new Def(Items.GOAT_HORN, "Fairy Ocarina", "Play the ocarina (arrow keys, A = Space)"));
        DEFS.put(0x08, new Def(Items.GOAT_HORN, "Ocarina of Time", "Play the ocarina (arrow keys, A = Space)"));
        DEFS.put(0x09, new Def(Items.RABBIT_FOOT, "Bombchu", "Link drops a bombchu"));
        DEFS.put(0x0A, new Def(Items.FISHING_ROD, "Hookshot", "Grapple to wood and hookshot targets (12 blocks)"));
        DEFS.put(0x0B, new Def(Items.FISHING_ROD, "Longshot", "Grapple to wood and hookshot targets (24 blocks)"));
        DEFS.put(0x0C, new Def(Items.SNOWBALL, "Ice Arrows", "Link aims ice arrows"));
        DEFS.put(0x0D, new Def(Items.ECHO_SHARD, "Farore's Wind", "Link casts Farore's Wind"));
        DEFS.put(0x0E, new Def(Items.NAUTILUS_SHELL, "Boomerang", "Link throws the boomerang"));
        DEFS.put(0x0F, new Def(Items.SPYGLASS, "Lens of Truth", "Link looks through the Lens of Truth"));
        DEFS.put(0x10, new Def(Items.BEETROOT_SEEDS, "Magic Beans", "Link plants a bean in soft soil"));
        DEFS.put(0x11, new Def(Items.MACE, "Megaton Hammer", "Link swings the hammer"));
        DEFS.put(0x12, new Def(Items.GLOWSTONE_DUST, "Light Arrows", "Link aims light arrows"));
        DEFS.put(0x13, new Def(Items.AMETHYST_SHARD, "Nayru's Love", "Link casts Nayru's Love"));
    }

    static Def defFor(int id) {
        Def d = DEFS.get(id);
        if (d != null) return d;
        if (id >= 0x14 && id <= 0x20) return new Def(Items.GLASS_BOTTLE, bottleName(id), "Link uses the bottle");
        if (id >= 0x24 && id <= 0x2B) return new Def(Items.CARVED_PUMPKIN, maskName(id), "Link puts on the mask");
        return new Def(Items.PAPER, "Zelda Item", "Link uses it (trade and quest items)");
    }

    static String bottleName(int id) {
        return switch (id) {
            case 0x15 -> "Red Potion"; case 0x16 -> "Green Potion"; case 0x17 -> "Blue Potion";
            case 0x18 -> "Bottled Fairy"; case 0x19 -> "Bottled Fish"; case 0x1A -> "Lon Lon Milk";
            case 0x1B -> "Ruto's Letter"; case 0x1C -> "Blue Fire"; case 0x1D -> "Bottled Bug";
            case 0x1E -> "Big Poe"; case 0x1F -> "Lon Lon Milk (Half)"; case 0x20 -> "Bottled Poe";
            default -> "Empty Bottle";
        };
    }

    static String maskName(int id) {
        return switch (id) {
            case 0x24 -> "Keaton Mask"; case 0x25 -> "Skull Mask"; case 0x26 -> "Spooky Mask";
            case 0x27 -> "Bunny Hood"; case 0x28 -> "Goron Mask"; case 0x29 -> "Zora Mask";
            case 0x2A -> "Gerudo Mask"; default -> "Mask of Truth";
        };
    }

    public static int idOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return -1;
        CompoundTag tag = data.copyTag();
        return tag.contains(TAG) ? tag.getInt(TAG) : -1;
    }

    static ItemStack make(int id) {
        Def d = defFor(id);
        ItemStack s = new ItemStack(d.base());
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG, id);
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        s.set(DataComponents.ITEM_NAME, Component.literal(d.name()).withStyle(ChatFormatting.GREEN));
        s.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("Zelda item").withStyle(ChatFormatting.DARK_GREEN),
            Component.literal("Right click: " + d.use()).withStyle(ChatFormatting.GRAY))));
        s.set(DataComponents.MAX_STACK_SIZE, 1);
        return s;
    }

    /** Zelda says Link has this item: make sure Steve has its Minecraft version. */
    static void give(ServerLevel level, int id) {
        if (id < 0 || id > 0x37 || id == 0x03) return; // Steve already has a real bow
        for (ServerPlayer p : level.players()) {
            var inv = p.getInventory();
            boolean has = false;
            for (int i = 0; i < inv.getContainerSize() && !has; i++) has = idOf(inv.getItem(i)) == id;
            if (!has && !inv.add(make(id))) p.drop(make(id), false);
        }
    }

    // ---- Hookshot: pull Steve to what it hits

    record Grapple(Vec3 target, int ticksLeft) {}
    private static final Map<UUID, Grapple> GRAPPLES = new HashMap<>();

    static boolean hookshot(ServerPlayer p, ServerLevel level, double range) {
        Vec3 from = p.getEyePosition();
        Vec3 to = from.add(p.getViewVector(1.0f).scale(range));
        Vec3 best = null;
        // Hyrule's own hookshot targets
        Vec3 hit = CollisionField.clipHookshot(from, to);
        if (hit != null) best = hit;
        // Wooden Minecraft blocks hold a hookshot too
        BlockHitResult b = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (b.getType() == HitResult.Type.BLOCK) {
            var state = level.getBlockState(b.getBlockPos());
            boolean wood = state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_FENCES)
                || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS);
            if (wood && (best == null || b.getLocation().distanceToSqr(from) < best.distanceToSqr(from))) best = b.getLocation();
        }
        level.playSound(null, p.blockPosition(), SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0f, 1.4f);
        if (best == null) return false;
        GRAPPLES.put(p.getUUID(), new Grapple(best, (int) (range * 2) + 10));
        return true;
    }

    static void tickGrapples(ServerLevel level) {
        GRAPPLES.entrySet().removeIf(e -> {
            Player p = level.getPlayerByUUID(e.getKey());
            Grapple g = e.getValue();
            if (p == null || g.ticksLeft() <= 0) return true;
            Vec3 to = g.target().subtract(p.position().add(0, p.getBbHeight() * 0.5, 0));
            if (to.length() < 1.0) {
                p.setDeltaMovement(Vec3.ZERO);
                p.hurtMarked = true;
                return true;
            }
            p.setDeltaMovement(to.normalize().scale(Math.min(1.4, to.length())));
            p.hurtMarked = true;
            p.resetFallDistance();
            e.setValue(new Grapple(g.target(), g.ticksLeft() - 1));
            return false;
        });
    }

    /** Right click with a Zelda item. Returns true if it was one (and the vanilla use is skipped). */
    static boolean use(ServerPlayer p, ServerLevel level, ItemStack stack) {
        int id = idOf(stack);
        if (id < 0) return false;
        Bridge bridge = Bridge.get();
        if (id == 0x0A || id == 0x0B) {
            hookshot(p, level, id == 0x0B ? 24 : 12);
            return true;
        }
        if (bridge != null) bridge.pushEvent(Bridge.EV_USE_ZELDA_ITEM, id, 0, 0);
        return true;
    }

    public static void register() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (idOf(stack) < 0) return InteractionResultHolder.pass(stack);
            if (!world.isClientSide && player instanceof ServerPlayer sp && world instanceof ServerLevel sl) {
                use(sp, sl, stack);
            }
            return InteractionResultHolder.success(stack);
        });
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (idOf(stack) < 0) return net.minecraft.world.InteractionResult.PASS;
            if (!world.isClientSide && player instanceof ServerPlayer sp && world instanceof ServerLevel sl) {
                use(sp, sl, stack);
            }
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
    }

    private ZeldaItems() {}
}
