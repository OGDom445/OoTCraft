package com.ootmc;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OoTCraft, server side. The player lives in the void "ootmc:hyrule" dimension; Hyrule itself is invisible
 * collision (CollisionField) and water volumes, while everything you build is real Minecraft blocks that stream to
 * Zelda as 16^3 sections.
 */
public class OotMc implements ModInitializer {
    public static final String MOD_ID = "ootmc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<Level> HYRULE =
        ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "hyrule"));

    /** Invisible, non-solid block holding a water source: Zelda's water boxes, so Minecraft swimming works. */
    public static Block WATER_VOLUME;
    public static net.minecraft.world.entity.EntityType<ActorProxy> ACTOR_PROXY;
    private final Map<Integer, ActorProxy> proxies = new java.util.HashMap<>();

    /** Sections changed in the Hyrule dimension (filled by ServerLevelMixin). */
    public static final Set<Long> DIRTY_SECTIONS = new HashSet<>();

    /** Zelda requests the client thread received that must run on the server thread. */
    public static final ConcurrentLinkedQueue<Bridge.Event> SERVER_EVENTS = new ConcurrentLinkedQueue<>();
    /** Creative Mode from Zelda's OoTCraft menu: 1 creative, 0 survival, -1 not heard yet (use what the player had). */
    private static volatile int wantedCreative = -1;

    /** Survival unless creative was picked in Zelda's OoTCraft menu (remembered on the player between sessions). */
    private static void applyGameMode(ServerPlayer p) {
        if (wantedCreative == 1) p.addTag("ootmc_creative");
        else if (wantedCreative == 0) p.removeTag("ootmc_creative");
        GameType mode = p.getTags().contains("ootmc_creative") ? GameType.CREATIVE : GameType.SURVIVAL;
        if (p.gameMode.getGameModeForPlayer() != mode) p.setGameMode(mode);
    }

    private final Set<Long> sentSections = new HashSet<>();
    private final Map<Long, Integer> sectionSlots = new LinkedHashMap<>(16, 0.75f, true);
    private int nextSlot = 0;
    /** What each section slot last held (sx, sy, sz, scene), so a new world can blank them in Zelda */
    private final int[][] slotWhat = new int[Bridge.SECTION_SLOTS][];
    private int lastScene = -1;
    private int lastAge = -1;
    private final ArrayDeque<long[]> waterQueue = new ArrayDeque<>();
    private final Set<Integer> waterBuilt = new HashSet<>();
    private final short[] ids = new short[Bridge.SECTION_VOLUME];

    @Override
    public void onInitialize() {
        WATER_VOLUME = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath(MOD_ID, "water_volume"),
            new WaterVolumeBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WATER).noCollission().noLootTable()
                .replaceable().strength(-1.0f, 3600000.0f).noOcclusion()));

        ACTOR_PROXY = Registry.register(BuiltInRegistries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "actor_proxy"),
            net.minecraft.world.entity.EntityType.Builder.<ActorProxy>of(ActorProxy::new, net.minecraft.world.entity.MobCategory.MISC)
                .sized(0.6f, 1.8f).noSave().clientTrackingRange(8).build("actor_proxy"));
        net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(ACTOR_PROXY,
            net.minecraft.world.entity.LivingEntity.createLivingAttributes());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> {
            ServerPlayer p = handler.getPlayer();
            sendToHyrule(p);
            if (!p.isDeadOrDying()) p.setHealth(p.getMaxHealth()); // a fresh session starts healthy, like a Zelda file load
        }));
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
        // Respawns land in the overworld; send the player straight back to Link
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
            newPlayer.server.execute(() -> sendToHyrule(newPlayer)));
        ZeldaItems.register();
        // Proof of what's under Hyrule: a core sample under Kokiri Forest on every start, and /ootcore for anywhere
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server ->
            {
                forgetPublished();
                LOGGER.info("[OoTCraft] {}", Underground.coreSample(server, Bridge.originX(85) - 2, 28));
                LOGGER.info("[OoTCraft] {}", Underground.featureScan(server, Bridge.originX(85) - 2, 28, 4));
            });
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, access, env) ->
            dispatcher.register(net.minecraft.commands.Commands.literal("ootcore")
                // Scans hundreds of thousands of blocks: operators only (matters if the world is opened to LAN)
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                var src = ctx.getSource();
                var pos = net.minecraft.core.BlockPos.containing(src.getPosition());
                String msg = Underground.coreSample(src.getServer(), pos.getX(), pos.getZ());
                src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), false);
                LOGGER.info("[OoTCraft] {}", msg);
                return 1;
            })));
        LOGGER.info("OoTCraft loaded");
    }

    private int safeTicks = 0;

    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sy & 0xFFFFF) << 22) | (sz & 0x3FFFFF);
    }

    private void sendToHyrule(ServerPlayer player) {
        ServerLevel hyrule = player.server.getLevel(HYRULE);
        if (hyrule == null) {
            LOGGER.error("Hyrule dimension missing");
            return;
        }
        Bridge bridge = Bridge.get();
        Bridge.LinkState link = bridge != null ? bridge.readLink() : null;
        if (link != null) {
            CollisionField.ensureScene(link.scene(), link.meshVersion());
            player.teleportTo(hyrule, Bridge.mcX(link.scene(), link.x()), Bridge.mcY(link.y()) + 0.05,
                Bridge.mcZ(link.z()), Bridge.mcYaw(link.yaw()), 0);
        } else {
            player.teleportTo(hyrule, 0.5, Bridge.ORIGIN_Y_BLOCKS + 2, 0.5, 0, 0);
        }
        // Hyrule rules: keep your inventory on death, and only long drops hurt (Link drops off ledges constantly)
        player.server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY).set(true, player.server);
        AttributeInstance safeFall = player.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        if (safeFall != null) safeFall.setBaseValue(8.0);
        // Constant daylight so the hand and items are lit like daytime in Hyrule
        player.server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, player.server);
        hyrule.setDayTime(6000);
        // Survival: Minecraft health is the real one (Zelda's hearts mirror it)
        applyGameMode(player);
        // Every 1.21 recipe in the recipe book from the start (crafting itself never needed unlocking)
        player.awardRecipes(player.server.getRecipeManager().getRecipes());
        // A starter hotbar the first time (the inventory screen isn't drawn into Zelda yet)
        boolean emptyHotbar = true;
        for (int i = 0; i < 9; i++) if (!player.getInventory().getItem(i).isEmpty()) emptyHotbar = false;
        player.addTag("ootmc_kit2");
        if (emptyHotbar) {
            net.minecraft.world.item.Item[] kit = {
                net.minecraft.world.item.Items.DIAMOND_SWORD, net.minecraft.world.item.Items.GRASS_BLOCK,
                net.minecraft.world.item.Items.OAK_PLANKS, net.minecraft.world.item.Items.COBBLESTONE,
                net.minecraft.world.item.Items.OAK_LOG, net.minecraft.world.item.Items.BRICKS,
                net.minecraft.world.item.Items.SAND, net.minecraft.world.item.Items.BOW,
                net.minecraft.world.item.Items.BREAD };
            for (int i = 0; i < kit.length; i++) {
                player.getInventory().setItem(i, new net.minecraft.world.item.ItemStack(kit[i], kit[i].getDefaultMaxStackSize()));
            }
            player.getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 64));
            player.getInventory().setItem(10, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TNT, 16));
            player.getInventory().selected = 1; // start holding grass blocks, ready to build
        }
        // Flint and steel with a stack of TNT (given once to existing players too)
        if (!player.getTags().contains("ootmc_tnt")) {
            player.addTag("ootmc_tnt");
            for (var stack : new net.minecraft.world.item.ItemStack[] {
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL),
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TNT, 64) }) {
                if (!player.getInventory().add(stack)) player.drop(stack, false);
            }
        }
        // Tools for digging into Hyrule (given once to existing players too)
        if (!player.getTags().contains("ootmc_tools")) {
            player.addTag("ootmc_tools");
            var inv = player.getInventory();
            net.minecraft.world.item.Item[] tools = { net.minecraft.world.item.Items.DIAMOND_PICKAXE,
                net.minecraft.world.item.Items.DIAMOND_SHOVEL, net.minecraft.world.item.Items.DIAMOND_AXE };
            int[] hotbarSlots = { 5, 6, -1 }; // pickaxe and shovel on the hotbar (what was there moves to the inventory)
            for (int t = 0; t < tools.length; t++) {
                var stack = new net.minecraft.world.item.ItemStack(tools[t]);
                if (inv.contains(stack)) continue;
                int slot = hotbarSlots[t];
                if (slot >= 0) {
                    var old = inv.getItem(slot);
                    inv.setItem(slot, stack);
                    if (!old.isEmpty() && !inv.add(old)) player.drop(old, false);
                } else if (!inv.add(stack)) {
                    player.drop(stack, false);
                }
            }
        }
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    private void onWorldTick(ServerLevel level) {
        if (level.dimension() != HYRULE) return;
        Bridge bridge = Bridge.get();
        if (bridge == null) return;
        Bridge.LinkState link = bridge.readLink();
        if (link == null) return;

        CollisionField.ensureScene(link.scene(), link.meshVersion());
        if (link.scene() != lastScene) {
            lastScene = link.scene();
            sentSections.clear();
            queueWater(link.scene());
            safeTicks = 60;
        }
        // Scene loads: Steve can drop a little before Hyrule's ground is in place; that never hurts
        if (safeTicks > 0) {
            safeTicks--;
            for (ServerPlayer p : level.players()) p.resetFallDistance();
        }

        // Young / adult Link -> player scale
        if (link.age() != lastAge) {
            lastAge = link.age();
            for (ServerPlayer p : level.players()) {
                AttributeInstance scale = p.getAttribute(Attributes.SCALE);
                if (scale != null) scale.setBaseValue(link.age() == 1 ? 0.72 : 1.0);
                // keep the full half-block step-up at child size (Hyrule has lots of small ledges and stairs)
                AttributeInstance step = p.getAttribute(Attributes.STEP_HEIGHT);
                if (step != null) step.setBaseValue(link.age() == 1 ? 0.84 : 0.6);
            }
        }

        // Requests from Zelda (bombs etc.); results flow back to Zelda as section updates
        Bridge.Event ev;
        while ((ev = SERVER_EVENTS.poll()) != null) {
            BlockPos pos = new BlockPos(ev.x, ev.y, ev.z);
            switch (ev.type) {
                case Bridge.EV_PLACE_REQUEST -> {
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir() || cur.canBeReplaced()) level.setBlock(pos, BlockPalette.toState(ev.blockId), Block.UPDATE_ALL);
                }
                case Bridge.EV_BREAK_REQUEST -> level.destroyBlock(pos, true);
                case Bridge.EV_GIVE_ITEM -> ZeldaItems.give(level, ev.x);
                case Bridge.EV_FILL -> {
                    // Hyrule ground the player started mining becomes real blocks (no physics: sand stays put)
                    BlockState cur = level.getBlockState(pos);
                    if (cur.isAir() || cur.is(WATER_VOLUME)) {
                        if (ev.blockId == 100) {
                            // Deeper down: the real 1.21 underground
                            if (!Underground.fill(level, pos, ev.radius)) {
                                bridge.pushEvent(Bridge.EV_CELL_AIR, pos.getX(), pos.getY(), pos.getZ());
                            }
                        } else {
                            BlockState fill = fillBlock(ev.blockId);
                            if (fill != null) level.setBlock(pos, fill, Block.UPDATE_CLIENTS);
                        }
                    }
                    CollisionField.holdReloadUntil = System.currentTimeMillis() + 750;
                }
                case Bridge.EV_EXPLOSION -> level.explode(null, ev.x + 0.5, ev.y + 0.5, ev.z + 0.5, ev.radius, Level.ExplosionInteraction.TNT);
                case Bridge.EV_PLAYER_HURT -> {
                    // Zelda damage (16 units = one heart) -> Minecraft hearts (2 hp each); armor and shields still apply
                    for (ServerPlayer p : level.players()) p.hurt(level.damageSources().generic(), ev.x / 8.0f);
                }
                case Bridge.EV_PLAYER_HEAL -> {
                    for (ServerPlayer p : level.players()) p.heal(ev.x / 8.0f);
                }
                case Bridge.EV_SET_GAMEMODE -> {
                    // OoTCraft menu: creative or survival, remembered on the player for the next session
                    // (every player on the server: right after joining, the player may not be in Hyrule yet)
                    wantedCreative = ev.x == 1 ? 1 : 0;
                    for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) applyGameMode(p);
                    LOGGER.info("[OoTCraft] game mode from Zelda's menu: {}", ev.x == 1 ? "creative" : "survival");
                }
                default -> {}
            }
        }

        ZeldaItems.tickGrapples(level);
        collectDrops(level);
        buildWater(level, 20000);
        syncProxies(level, bridge, link.scene());

        // Stream sections around the player to Zelda: anything changed, plus anything not yet sent for this scene
        int lx = (int) Math.floor(Bridge.mcX(link.scene(), link.x())) >> 4;
        int ly = (int) Math.floor(Bridge.mcY(link.y())) >> 4;
        int lz = (int) Math.floor(Bridge.mcZ(link.z())) >> 4;
        int budget = 24;
        for (int dy = -2; dy <= 2 && budget > 0; dy++)
            for (int dz = -2; dz <= 2 && budget > 0; dz++)
                for (int dx = -2; dx <= 2 && budget > 0; dx++) {
                    long key = sectionKey(lx + dx, ly + dy, lz + dz);
                    boolean dirty = DIRTY_SECTIONS.remove(key);
                    if (dirty || !sentSections.contains(key)) {
                        publish(bridge, level, link.scene(), lx + dx, ly + dy, lz + dz);
                        sentSections.add(key);
                        budget--;
                    }
                }
        // Farther out (the whole area Zelda shows in third person): only sections with blocks in them, so dug ground
        // and builds across the area are drawn, not holes. Checked once a second.
        if (budget > 0 && ++farScanTick >= 20) {
            farScanTick = 0;
            for (int dy = -4; dy <= 3 && budget > 0; dy++)
                for (int dz = -6; dz <= 6 && budget > 0; dz++)
                    for (int dx = -6; dx <= 6 && budget > 0; dx++) {
                        if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2 && Math.abs(dy) <= 2) continue;
                        int sx = lx + dx, sy = ly + dy, sz = lz + dz;
                        long key = sectionKey(sx, sy, sz);
                        boolean dirty = DIRTY_SECTIONS.remove(key);
                        if (!dirty && sentSections.contains(key)) continue;
                        if (sy < level.getMinSection() || sy >= level.getMaxSection()) continue;
                        LevelChunk chunk = level.getChunk(sx, sz);
                        int idx = chunk.getSectionIndexFromSectionY(sy);
                        if (!dirty && chunk.getSections()[idx].hasOnlyAir()) continue;
                        publish(bridge, level, link.scene(), sx, sy, sz);
                        sentSections.add(key);
                        budget--;
                    }
        }
        DIRTY_SECTIONS.forEach(sentSections::remove);
        DIRTY_SECTIONS.clear();
    }

    private int farScanTick = 0;

    /** Keep one invisible stand-in per nearby Zelda actor, matching its position and hitbox. */
    private void syncProxies(ServerLevel level, Bridge bridge, int scene) {
        java.util.List<Bridge.ActorInfo> actors = bridge.readActors();
        if (actors == null) return;
        java.util.Set<Integer> seen = new HashSet<>();
        for (Bridge.ActorInfo a : actors) {
            seen.add(a.id());
            ActorProxy p = proxies.get(a.id());
            if (p == null || p.isRemoved()) {
                p = ACTOR_PROXY.create(level);
                if (p == null) continue;
                p.ootId = a.id();
                p.hostile = a.hostile();
                p.moveTo(Bridge.mcX(scene, a.x()), Bridge.mcY(a.y()), Bridge.mcZ(a.z()), 0, 0);
                level.addFreshEntity(p);
                proxies.put(a.id(), p);
            }
            p.setSize(Math.max(0.2f, a.radius() * 2 / Bridge.UNITS_PER_BLOCK), Math.max(0.2f, a.height() / Bridge.UNITS_PER_BLOCK));
            p.setPos(Bridge.mcX(scene, a.x()), Bridge.mcY(a.y()), Bridge.mcZ(a.z()));
        }
        proxies.entrySet().removeIf(e -> {
            if (!seen.contains(e.getKey()) || e.getValue().isRemoved()) {
                e.getValue().discard();
                return true;
            }
            return false;
        });
    }

    private void publish(Bridge bridge, ServerLevel level, int scene, int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return;
        LevelChunk chunk = level.getChunk(sx, sz);
        int idx = chunk.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        int nonAir = 0;
        if (idx < 0 || idx >= sections.length || sections[idx].hasOnlyAir()) {
            Arrays.fill(ids, (short) 0);
        } else {
            LevelChunkSection section = sections[idx];
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        mp.set(sx * 16 + x, sy * 16 + y, sz * 16 + z);
                        short id = BlockPalette.toId(section.getBlockState(x, y, z), level, mp);
                        ids[(y * 16 + z) * 16 + x] = id;
                        if (id != 0) nonAir++;
                    }
        }
        long key = sectionKey(sx, sy, sz);
        Integer slot = sectionSlots.get(key);
        if (slot == null) {
            if (sectionSlots.size() >= Bridge.SECTION_SLOTS) {
                long eldest = sectionSlots.keySet().iterator().next();
                slot = sectionSlots.remove(eldest);
            } else {
                slot = nextSlot++;
            }
            sectionSlots.put(key, slot);
        }
        bridge.writeSection(slot, sx, sy, sz, scene, ids, nonAir);
        slotWhat[slot] = new int[] { sx, sy, sz, scene };
    }

    /**
     * A (new) world started: every block this JVM showed Zelda belongs to the old one. Blank them in Zelda and start
     * publishing from scratch (the reset-world option deletes the world and makes a fresh one in the same session).
     */
    private void forgetPublished() {
        Bridge bridge = Bridge.get();
        if (bridge != null) {
            Arrays.fill(ids, (short) 0);
            for (int slot = 0; slot < slotWhat.length; slot++) {
                int[] w = slotWhat[slot];
                if (w != null) bridge.writeSection(slot, w[0], w[1], w[2], w[3], ids, 0);
                slotWhat[slot] = null;
            }
        }
        sectionSlots.clear();
        sentSections.clear();
        nextSlot = 0;
        lastScene = -1;
        waterBuilt.clear();
        waterQueue.clear();
        DIRTY_SECTIONS.clear();
    }

    // ---- Zelda's water boxes become invisible water volumes, filled down to the scene floor

    private void queueWater(int scene) {
        if (waterBuilt.contains(scene)) return;
        CollisionField.Mesh m = CollisionField.mesh();
        if (m == null || m.scene != scene) return;
        waterBuilt.add(scene);
        int ox = Bridge.originX(scene);
        for (short[] w : m.water) {
            int x0 = ox + (int) Math.floor(w[0] / Bridge.UNITS_PER_BLOCK);
            int x1 = ox + (int) Math.floor((w[0] + w[3]) / Bridge.UNITS_PER_BLOCK);
            int z0 = (int) Math.floor(w[2] / Bridge.UNITS_PER_BLOCK);
            int z1 = (int) Math.floor((w[2] + w[4]) / Bridge.UNITS_PER_BLOCK);
            int top = (int) Math.floor(Bridge.mcY(w[1])) - 1;
            if ((long) (x1 - x0) * (z1 - z0) > 400_000) continue;
            for (int x = x0; x < x1; x++)
                for (int z = z0; z < z1; z++) waterQueue.add(new long[] { x, top, z });
        }
    }

    private void buildWater(ServerLevel level, int budget) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (budget > 0 && !waterQueue.isEmpty()) {
            long[] c = waterQueue.poll();
            int x = (int) c[0], top = (int) c[1], z = (int) c[2];
            for (int y = top; y > top - 48 && budget > 0; y--, budget--) {
                if (CollisionField.solidAt(x, y, z)) break;
                pos.set(x, y, z);
                if (level.getBlockState(pos).isAir()) {
                    level.setBlock(pos, WATER_VOLUME.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    /** Blocks Zelda's surface materials turn into when mined (codes from MinecraftTerrain.cpp). */
    static BlockState fillBlock(int code) {
        return switch (code) {
            case 1 -> net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            case 2 -> net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
            case 3 -> net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            case 4 -> net.minecraft.world.level.block.Blocks.SAND.defaultBlockState();
            case 5 -> net.minecraft.world.level.block.Blocks.SANDSTONE.defaultBlockState();
            case 6 -> net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState();
            case 7 -> net.minecraft.world.level.block.Blocks.PACKED_ICE.defaultBlockState();
            case 8 -> net.minecraft.world.level.block.Blocks.MAGMA_BLOCK.defaultBlockState();
            case 9 -> net.minecraft.world.level.block.Blocks.NETHERRACK.defaultBlockState();
            case 10 -> net.minecraft.world.level.block.Blocks.NETHER_WART_BLOCK.defaultBlockState();
            case 11 -> net.minecraft.world.level.block.Blocks.RED_WOOL.defaultBlockState();
            case 12 -> net.minecraft.world.level.block.Blocks.COARSE_DIRT.defaultBlockState();
            case 13 -> net.minecraft.world.level.block.Blocks.COAL_ORE.defaultBlockState();
            case 14 -> net.minecraft.world.level.block.Blocks.IRON_ORE.defaultBlockState();
            case 15 -> net.minecraft.world.level.block.Blocks.GOLD_ORE.defaultBlockState();
            case 16 -> net.minecraft.world.level.block.Blocks.REDSTONE_ORE.defaultBlockState();
            case 17 -> net.minecraft.world.level.block.Blocks.LAPIS_ORE.defaultBlockState();
            case 18 -> net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState();
            case 19 -> net.minecraft.world.level.block.Blocks.ANDESITE.defaultBlockState();
            case 20 -> net.minecraft.world.level.block.Blocks.DEEPSLATE.defaultBlockState();
            case 21 -> net.minecraft.world.level.block.Blocks.COPPER_ORE.defaultBlockState();
            case 22 -> net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();
            case 23 -> net.minecraft.world.level.block.Blocks.GRAVEL.defaultBlockState();
            case 24 -> net.minecraft.world.level.block.Blocks.GRANITE.defaultBlockState();
            case 25 -> net.minecraft.world.level.block.Blocks.DIORITE.defaultBlockState();
            case 26 -> net.minecraft.world.level.block.Blocks.TUFF.defaultBlockState();
            case 27 -> net.minecraft.world.level.block.Blocks.DEEPSLATE_IRON_ORE.defaultBlockState();
            case 28 -> net.minecraft.world.level.block.Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState();
            case 29 -> net.minecraft.world.level.block.Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState();
            case 30 -> net.minecraft.world.level.block.Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState();
            default -> null;
        };
    }

    /**
     * Mined blocks drop their item, but Zelda's view doesn't draw dropped items, so drops near the player fly
     * straight to them (anything the player threw away themselves stays where it fell).
     */
    private static void collectDrops(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isDeadOrDying()) continue;
            for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, p.getBoundingBox().inflate(8.0))) {
                if (item.getOwner() != null || item.isRemoved()) continue;
                item.setNoPickUpDelay();
                item.setPos(p.getX(), p.getY() + 0.25, p.getZ());
                item.playerTouch(p);
            }
        }
    }
}
