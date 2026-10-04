package com.ootmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ootmc.Bridge;
import com.ootmc.CollisionField;
import com.ootmc.OotMc;
import com.ootmc.mixin.client.GameRendererAccessor;
import com.ootmc.mixin.client.KeyboardHandlerInvoker;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import com.ootmc.mixin.client.BackupConfirmScreenAccessor;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * OoTCraft, client side. This Minecraft runs hidden: Zelda's window holds focus and forwards keyboard and mouse
 * here; Minecraft moves the player with its own physics against Hyrule's collision and sends position + camera back
 * every frame, and Zelda renders the world from that camera.
 */
public class OotMcClient implements ClientModInitializer {
    public static final String WORLD_NAME = "OoTCraft";

    private static boolean autoWorldStarted = false;
    private static boolean hidden = false;
    private static boolean inputInit = false;
    private static int lastDx, lastDy, lastWheel;
    private static int lastTeleportSeq = Integer.MIN_VALUE;
    private static int currentScene = 0;
    private static int fpsTicks = 0;
    /** Hyrule ground block under the crosshair (null when aiming at a real block, an entity or nothing). */
    public static volatile net.minecraft.core.BlockPos terrainTarget;
    private static net.minecraft.core.BlockPos lastCarve;
    private static long lastCarveTime;
    private static long lastOotBeat = -1, lastOotBeatChangeMs = 0;
    private static boolean everConnected = false;
    private static boolean frozen = false;
    private static boolean reportedDeath = false;
    private static String lastScreenName = "";
    private static Vec3 freezePos = null;
    private static final Bridge.Event ev = new Bridge.Event();
    /** Keys / buttons Zelda says are held; re-asserted every frame (the hidden window loses Minecraft's own key state). */
    private static final java.util.Set<Integer> heldKeys = new java.util.HashSet<>();
    private static final java.util.Set<Integer> heldButtons = new java.util.HashSet<>();
    /** Mouse buttons held down inside a menu, and where the cursor was last (for dragging stacks across slots). */
    private static final java.util.Set<Integer> screenButtons = new java.util.HashSet<>();
    private static double lastGx = -1, lastGy = -1;

    /** Whether Zelda says this key is held (the hidden window's own key state is always "up"). */
    public static boolean keyHeld(int key) {
        return heldKeys.contains(key);
    }

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof TitleScreen && !autoWorldStarted) {
                autoWorldStarted = true;
                if (!ownedGame(mc)) {
                    mc.execute(() -> mc.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
                        () -> mc.stop(),
                        net.minecraft.network.chat.Component.literal("OoTCraft needs your own copy of Minecraft"),
                        net.minecraft.network.chat.Component.literal("Start Minecraft from the official Minecraft "
                            + "Launcher, signed in with the Microsoft account that owns Minecraft: Java Edition, and "
                            + "choose the OoTCraft profile. OoTCraft is free, but Minecraft and Ocarina of Time are "
                            + "not: please own both."),
                        net.minecraft.network.chat.Component.literal("Quit"), true)));
                    return;
                }
                mc.execute(() -> openWorld(mc, screen));
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(OotMcClient::tick);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(OotMc.ACTOR_PROXY,
            net.minecraft.client.renderer.entity.NoopRenderer::new);
    }

    /**
     * OoTCraft only runs on a Minecraft you own: started from the official launcher with a Microsoft account. The
     * development environment (contributors running the mod from source) is exempt.
     */
    private static boolean ownedGame(Minecraft mc) {
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) return true;
        boolean owned = mc.getUser().getType() == net.minecraft.client.User.Type.MSA;
        if (!owned) OotMc.LOGGER.warn("[OoTCraft] not started with a Microsoft account: OoTCraft won't run");
        return owned;
    }

    // ---- start-up: jump straight into the OoTCraft world, creating it the first time

    private static void openWorld(Minecraft mc, Screen parent) {
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
        mc.options.framerateLimit().set(120); // hand and menus keep up with Zelda's high-refresh frames
        // Minecraft only draws the HUD and hand here; Zelda draws the world
        mc.options.renderDistance().set(2);
        mc.options.simulationDistance().set(5);
        mc.options.entityShadows().set(false);
        mc.options.particles().set(net.minecraft.client.ParticleStatus.MINIMAL);
        if (mc.getLevelSource().levelExists(WORLD_NAME)) {
            mc.createWorldOpenFlows().openWorld(WORLD_NAME, () -> mc.setScreen(parent));
        } else {
            LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.SURVIVAL, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, WorldOptions.defaultWithRandomSeed(),
                WorldPresets::createNormalWorldDimensions, parent);
        }
    }

    /**
     * Zelda's "Reset Minecraft World": leave the world, delete it, and let the title screen hook make a fresh one
     * (new seed, empty inventory, starter kit again). Hyrule's digs are wiped on Zelda's side at the same time.
     */
    private static void resetWorld(Minecraft mc) {
        OotMc.LOGGER.info("[OoTCraft] resetting the Minecraft world");
        if (mc.level != null) mc.level.disconnect();
        mc.disconnect();
        try (var access = mc.getLevelSource().createAccess(WORLD_NAME)) {
            access.deleteLevel();
        } catch (Exception e) {
            OotMc.LOGGER.error("Couldn't delete the old world", e);
        }
        autoWorldStarted = false;
        mc.setScreen(new TitleScreen()); // AFTER_INIT opens (creates) the world again
    }

    /** Auto-accept "experimental settings" / backup prompts while the world is being opened. */
    private static void acceptConfirmScreen(Minecraft mc) {
        Screen s = mc.screen;
        if (s == null || mc.level != null) return;
        if (s instanceof BackupConfirmScreen backup) {
            // "Worlds using Experimental Settings": the Hyrule dimension comes from this mod's data pack
            OotMc.LOGGER.info("Auto-continuing past the experimental settings warning");
            ((BackupConfirmScreenAccessor) backup).ootmc$onProceed().proceed(false, false);
            return;
        }
        for (Class<?> c = s.getClass(); c != null && c != Screen.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (BooleanConsumer.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        BooleanConsumer cb = (BooleanConsumer) f.get(s);
                        if (cb != null) {
                            OotMc.LOGGER.info("Auto-confirming {}", s.getClass().getSimpleName());
                            cb.accept(true);
                            return;
                        }
                    } catch (ReflectiveOperationException ignored) {}
                }
            }
        }
    }

    // ---- every client tick (20 Hz)

    private static void tick(Minecraft mc) {
        Bridge bridge = Bridge.get();
        if (bridge == null) return;
        acceptConfirmScreen(mc);
        BlockTextures.process();
        catchVoidFall(mc);
        if (++fpsTicks >= 200) { // every 10 s
            fpsTicks = 0;
            OotMc.LOGGER.info("[OoTCraft] Minecraft at {} FPS", mc.getFps());
        }

        // Zelda alive? Quit with it.
        long beat = bridge.ootHeartbeat();
        long now = System.currentTimeMillis();
        if (beat != lastOotBeat) {
            lastOotBeat = beat;
            lastOotBeatChangeMs = now;
            if (beat != 0) everConnected = true;
        } else if (everConnected && now - lastOotBeatChangeMs > 10_000) {
            OotMc.LOGGER.info("Zelda closed; stopping Minecraft");
            mc.stop();
            return;
        }

        LocalPlayer player = mc.player;
        String screenName = mc.screen == null ? "none" : mc.screen.getClass().getName();
        if (!screenName.equals(lastScreenName)) {
            OotMc.LOGGER.debug("Minecraft screen: {}", screenName);
            lastScreenName = screenName;
        }
        if (player == null || mc.level == null) return;

        // "Loading terrain" waits for chunks to be drawn, which never happens while Zelda draws the world
        if (mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen && overlayMode()) {
            mc.setScreen(null);
        }

        if (everConnected && !hidden && !Boolean.getBoolean("ootmc.showWindow")) {
            // Fix the render size while the window is still visible (hidden windows don't resize reliably);
            // Zelda stretches the 1280x720 hand + HUD layer over its own view
            if (mc.getWindow().getWidth() != 1280 || mc.getWindow().getHeight() != 720) {
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1280, 720);
                return; // hide on a later tick, after Minecraft has applied the new size
            }
            hidden = true;
            GLFW.glfwHideWindow(mc.getWindow().getWindow());
        }

        // Render the hand + HUD + menus at exactly the shape of Zelda's view. The hidden window rarely takes the
        // size it's given (it was stuck at 726x487, so the hotbar and inventory came out squashed and blurry)
        if (hidden && overlayMode()) {
            int ww = FrameShare.wantWidth(), wh = FrameShare.wantHeight();
            var win = mc.getWindow();
            if (ww >= 320 && wh >= 240 && ww <= FrameShare.MAX_W && wh <= FrameShare.MAX_H
                && (win.getWidth() != ww || win.getHeight() != wh)) {
                var acc = (com.ootmc.mixin.client.WindowSizeAccessor) (Object) win;
                acc.ootmc$setFramebufferWidth(ww);
                acc.ootmc$setFramebufferHeight(wh);
                acc.ootmc$setWidth(ww);
                acc.ootmc$setHeight(wh);
                mc.resizeDisplay();
                OotMc.LOGGER.info("[OoTCraft] Minecraft layer now renders at {}x{}", ww, wh);
            }
        }

        Bridge.LinkState link = bridge.readLink();
        if (link == null) return;
        currentScene = link.scene();
        CollisionField.ensureScene(link.scene(), link.meshVersion());

        // Zelda relocated the player (scene spawn, void-out, warp)
        if (link.teleportSeq() != lastTeleportSeq) {
            lastTeleportSeq = link.teleportSeq();
            teleport(mc, link.scene(), link.tx(), link.ty(), link.tz(), link.tyaw());
        }

        // Died: Zelda shows its Game Over (Minecraft health is the real one), and the player respawns at Link
        if (player.isDeadOrDying()) {
            if (!reportedDeath) {
                reportedDeath = true;
                bridge.pushEvent(Bridge.EV_PLAYER_DIED, 0, 0, 0);
            }
            player.respawn();
            return;
        }
        reportedDeath = false;

        // Fell below anything Zelda has: put the player back where Link is
        if (mc.level.dimension() == OotMc.HYRULE && player.getY() < Bridge.ORIGIN_Y_BLOCKS - 140) {
            teleport(mc, link.scene(), link.x(), link.y(), link.z(), link.yaw());
        }

        // Cutscenes, dialogue, transitions, pause, or collision not loaded yet: Minecraft must not move the player
        CollisionField.Mesh mesh = CollisionField.mesh();
        boolean meshReady = mesh != null && mesh.scene() == link.scene();
        boolean nowFrozen = (link.flags() & Bridge.LINK_FROZEN) != 0 || !meshReady;
        if (nowFrozen && !frozen) {
            freezePos = player.position();
        }
        if (!nowFrozen && frozen) {
            player.setNoGravity(false);
        }
        frozen = nowFrozen;
        // Link mode: Link moves himself with Zelda's controls; the Minecraft player rides along at his position
        if ((link.flags() & Bridge.LINK_HANDOFF) != 0 && mc.level.dimension() == OotMc.HYRULE) {
            freezePos = new Vec3(Bridge.mcX(link.scene(), link.x()), Bridge.mcY(link.y()), Bridge.mcZ(link.z()));
            player.setYRot(Bridge.mcYaw(link.yaw()));
            frozen = true;
        }
        if (frozen && freezePos != null) {
            player.setNoGravity(true);
            player.setDeltaMovement(Vec3.ZERO);
            player.setPos(freezePos);
        }

        if (mc.level.dimension() == OotMc.HYRULE) {
            CollisionField.updateDyna(bridge, player.getX(), player.getY(), player.getZ());
        }
    }

    private static void teleport(Minecraft mc, int scene, float x, float y, float z, short yaw) {
        double mx = Bridge.mcX(scene, x), my = Bridge.mcY(y) + 0.05, mz = Bridge.mcZ(z);
        float myaw = Bridge.mcYaw(yaw);
        if (mc.player != null && mc.level != null && mc.level.dimension() == OotMc.HYRULE) {
            OotMc.LOGGER.info("[OoTCraft] Zelda teleported Steve from {} to ({}, {}, {})",
                mc.player.position(), String.format("%.1f", mx), String.format("%.1f", my), String.format("%.1f", mz));
            mc.player.setPos(mx, my, mz);
            mc.player.setYRot(myaw);
            mc.player.setDeltaMovement(Vec3.ZERO);
        }
        freezePos = new Vec3(mx, my, mz);
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) return;
        var uuid = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            ServerLevel hyrule = server.getLevel(OotMc.HYRULE);
            if (sp != null && hyrule != null) {
                sp.teleportTo(hyrule, mx, my, mz, myaw, sp.getXRot());
            }
        });
    }

    // ---- overlay: Minecraft draws only hand + HUD, Zelda composites them

    /** True when Zelda is up and the player is in Hyrule: skip Minecraft's world pass and share frames. */
    public static boolean overlayMode() {
        Minecraft mc = Minecraft.getInstance();
        return everConnected && mc.level != null && mc.level.dimension() == OotMc.HYRULE;
    }

    private static net.minecraft.world.phys.Vec3 lastSafe;
    private static long lastVoidCatch = 0, lastVoidOut = 0;

    /** Never fall into the void under Hyrule: anything below its lowest ground goes back to where Steve last stood. */
    private static void catchVoidFall(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.level.dimension() != OotMc.HYRULE || frozen) return;
        com.ootmc.CollisionField.Mesh m = com.ootmc.CollisionField.mesh();
        if (m == null || m.scene != currentScene) return;
        if (p.onGround() && p.getY() >= m.lowestY - 44) {
            lastSafe = p.position();
        } else if (p.getY() < m.lowestY - 48) { // below bedrock: nothing down there
            long now = System.currentTimeMillis();
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            p.resetFallDistance();
            if (lastSafe != null && now - lastVoidCatch > 10000) {
                p.setPos(lastSafe.x, lastSafe.y + 0.1, lastSafe.z);
                OotMc.LOGGER.info("[OoTCraft] caught a fall into the void; back to {}", lastSafe);
            } else if (now - lastVoidOut > 6000) {
                // Twice in a row (where Steve stood is gone too): Zelda's own void-out, back to the entrance
                Bridge bridge = Bridge.get();
                if (bridge != null) bridge.pushEvent(Bridge.EV_VOID_OUT, 0, 0, 0);
                lastSafe = null;
                lastVoidOut = now;
                OotMc.LOGGER.info("[OoTCraft] fell out of the world: Zelda void-out");
            }
            lastVoidCatch = now;
        }
    }

    /** Holding attack on Zelda's ground: ask Zelda to turn that ground into blocks, which then mine normally. */
    public static void mineHyruleGround() {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.core.BlockPos target = terrainTarget;
        Bridge bridge = Bridge.get();
        if (target == null || bridge == null || mc.player == null || mc.gameMode == null
            || mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.ADVENTURE
            || mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SPECTATOR) return;
        long now = System.currentTimeMillis();
        if (target.equals(lastCarve) && now - lastCarveTime < 1500) return;
        if (now - lastCarveTime < 200) return;
        lastCarve = target;
        lastCarveTime = now;
        bridge.pushEvent(Bridge.EV_CARVE, target.getX(), target.getY(), target.getZ(), currentScene);
    }

    public static void onFrameComplete() {
        boolean active = overlayMode();
        if (active) SkinShare.update(Minecraft.getInstance());
        FrameShare.setActive(active);
        if (active && FrameShare.zeldaShowing()) {
            FrameShare.capture(Minecraft.getInstance().getMainRenderTarget());
        }
    }

    // ---- every rendered frame

    public static void onFrame() {
        Minecraft mc = Minecraft.getInstance();
        Bridge bridge = Bridge.get();
        if (bridge == null) return;
        LocalPlayer player = mc.player;
        long window = mc.getWindow().getWindow();

        Bridge.InputState in = bridge.readInput();
        double gx = 0, gy = 0;
        if (in != null) {
            if (!inputInit) {
                inputInit = true;
                lastDx = in.mouseDx();
                lastDy = in.mouseDy();
                lastWheel = in.wheel();
            }
            int dx = in.mouseDx() - lastDx, dy = in.mouseDy() - lastDy, dw = in.wheel() - lastWheel;
            lastDx = in.mouseDx();
            lastDy = in.mouseDy();
            lastWheel = in.wheel();
            boolean owns = (in.flags() & 1) != 0;
            gx = in.cursorX() * mc.getWindow().getGuiScaledWidth();
            gy = in.cursorY() * mc.getWindow().getGuiScaledHeight();
            // Menus are drawn at the hidden window's own cursor, which never moves: put it where Zelda's cursor is
            var mouse = (com.ootmc.mixin.client.MouseHandlerAccessor) mc.mouseHandler;
            mouse.ootmc$setXpos(in.cursorX() * mc.getWindow().getScreenWidth());
            mouse.ootmc$setYpos(in.cursorY() * mc.getWindow().getScreenHeight());
            if (owns && mc.screen == null && player != null && !frozen) {
                double s = mc.options.sensitivity().get() * 0.6 + 0.2;
                double f = s * s * s * 8.0;
                player.turn(dx * f, dy * f * (mc.options.invertYMouse().get() ? -1 : 1));
                if (dw != 0) player.getInventory().swapPaint(dw);
            }
            if (mc.screen != null) {
                mc.screen.mouseMoved(gx, gy);
                // Holding a button while moving drags: spreads a stack over slots, moves sliders and scroll bars
                if (lastGx >= 0 && (gx != lastGx || gy != lastGy)) {
                    for (int b : screenButtons) mc.screen.mouseDragged(gx, gy, b, gx - lastGx, gy - lastGy);
                }
                // The wheel scrolls lists: creative tabs, recipe book, world and server lists
                if (dw != 0) mc.screen.mouseScrolled(gx, gy, 0, dw);
            } else {
                screenButtons.clear();
            }
            lastGx = gx;
            lastGy = gy;
        }

        while (bridge.popEvent(ev)) {
            switch (ev.type) {
                case Bridge.EV_KEY -> {
                    if (ev.y == 0) heldKeys.remove(ev.x); else heldKeys.add(ev.x);
                    mc.keyboardHandler.keyPress(window, ev.x, 0, ev.y, ev.z);
                }
                case Bridge.EV_NUDGE -> {
                    // Something solid in Zelda (an NPC, a sign, a rock) pushed Link: Steve gets pushed the same way
                    if (player != null) {
                        player.setPos(player.getX() + ev.x / 100.0 / Bridge.UNITS_PER_BLOCK, player.getY(),
                            player.getZ() + ev.z / 100.0 / Bridge.UNITS_PER_BLOCK);
                    }
                }
                case Bridge.EV_CHAR -> ((KeyboardHandlerInvoker) mc.keyboardHandler).ootmc$charTyped(window, ev.x, 0);
                case Bridge.EV_RESET_WORLD -> mc.tell(() -> resetWorld(mc));
                case Bridge.EV_MOUSE_BUTTON -> {
                    boolean down = ev.y == 1;
                    if (mc.screen != null) {
                        if (down) {
                            screenButtons.add(ev.x);
                            mc.screen.mouseClicked(gx, gy, ev.x);
                        } else {
                            screenButtons.remove(ev.x);
                            mc.screen.mouseReleased(gx, gy, ev.x);
                        }
                    } else {
                        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(ev.x);
                        KeyMapping.set(key, down);
                        if (down) KeyMapping.click(key);
                        if (down) heldButtons.add(ev.x); else heldButtons.remove(ev.x);
                    }
                }
                default -> {
                    Bridge.Event copy = new Bridge.Event();
                    copy.type = ev.type; copy.blockId = ev.blockId; copy.x = ev.x; copy.y = ev.y; copy.z = ev.z;
                    copy.scene = ev.scene; copy.radius = ev.radius;
                    OotMc.SERVER_EVENTS.add(copy);
                }
            }
        }

        // First person only: Zelda's own camera is the third-person view (F5 hands control to Link)
        if (overlayMode() && !mc.options.getCameraType().isFirstPerson()) {
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        }

        // Keep held keys held (gameplay only; screens get discrete events)
        if (mc.screen == null) {
            for (int k : heldKeys) KeyMapping.set(InputConstants.Type.KEYSYM.getOrCreate(k), true);
            for (int b : heldButtons) KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(b), true);
        }

        // Publish the player and camera for Zelda
        bridge.beatMc();
        if (player == null || mc.level == null) return;
        boolean inHyrule = mc.level.dimension() == OotMc.HYRULE;
        DropShare.publish(mc, currentScene); // items on the ground, for Zelda to draw
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 feet = player.getPosition(partial);
        Vec3 eye = player.getEyePosition(partial);
        Camera cam = mc.gameRenderer.getMainCamera();
        double fov = ((GameRendererAccessor) mc.gameRenderer).ootmc$getFov(cam, partial, true);
        int flags = 0;
        if (inHyrule) flags |= Bridge.MC_IN_HYRULE;
        if (mc.screen != null) flags |= Bridge.MC_SCREEN_OPEN;
        if (player.onGround()) flags |= Bridge.MC_ON_GROUND;
        if (player.isInWater()) flags |= Bridge.MC_SWIMMING;
        if (player.isShiftKeyDown()) flags |= Bridge.MC_SNEAKING;
        if (player.isDeadOrDying()) flags |= Bridge.MC_DEAD;
        if (!mc.options.getCameraType().isFirstPerson()) flags |= Bridge.MC_THIRD_PERSON;
        if (player.isVisuallyCrawling()) flags |= Bridge.MC_CRAWLING;
        // Block being mined, so Zelda can draw Minecraft's cracks on it
        int stage = -1;
        net.minecraft.core.BlockPos dpos = net.minecraft.core.BlockPos.ZERO;
        if (mc.gameMode != null && mc.gameMode.isDestroying()) {
            stage = mc.gameMode.getDestroyStage();
            dpos = ((com.ootmc.mixin.client.GameModeAccessor) mc.gameMode).ootmc$destroyBlockPos();
        }
        bridge.writeMc(Bridge.ootX(currentScene, feet.x), Bridge.ootY(feet.y), Bridge.ootZ(feet.z),
            (float) ((eye.y - feet.y) * Bridge.UNITS_PER_BLOCK), (float) fov,
            Bridge.ootYaw(player.getViewYRot(partial)), Bridge.ootPitch(player.getViewXRot(partial)), flags,
            player.getHealth(), player.getMaxHealth(),
            // the mouse totals this view already includes, so Zelda can turn by the rest itself (no input lag)
            (lastDx & 0xFFFF) | (lastDy << 16), (short) currentScene, player.getInventory().selected,
            stage, dpos.getX(), dpos.getY(), dpos.getZ());
    }
}
