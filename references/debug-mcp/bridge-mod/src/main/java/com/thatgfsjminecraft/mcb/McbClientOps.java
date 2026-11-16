package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Client-only implementations of the v0.5.1 ops (protocol §3.15–§3.18):
 *
 *   screen.current               — what is on screen / is the window focused
 *   client.screenshot            — frame-end capture, PNG written to run/screenshots
 *   client.pause_on_lost_focus   — vanilla auto-pause switch (read/write)
 *   client.reload_resources      — L2 hot-reload loop (F3+T equivalent)
 *
 * It also owns the two focus-keep-alive pieces the mixins read: the
 * {@code autoPauseGuard} flag (§1.2 @Redirect guard) and the one-time
 * {@code options.pauseOnLostFocus = false} init (§1.1).
 *
 * WHY A SEPARATE CLASS: McbHandlers is already ~730 lines and is about
 * world/block/entity reads; these ops are pure client UI/render concerns with
 * their own threading rules, and keeping them apart makes the red lines
 * (main thread vs render thread vs IO thread) readable in one place.
 *
 * THREAD DISCIPLINE (protocol §6):
 * - {@link #screenJson}, {@link #pauseToggle}, {@link #reloadResources} run on
 *   the CLIENT MAIN THREAD (McbServer hops first).
 * - screenshot state is split on purpose: the request is armed on the main
 *   thread, drained from McbScreenshot.onFrameEnd() on the RENDER thread (the
 *   same thread as the client main thread on 1.21.11, so the queue needs no
 *   lock), and the PNG is encoded/written from Util.ioPool().
 *
 * Mixins live in the …mcb.mixin package, so the members they call are public;
 * everything else stays package-private.
 */
public final class McbClientOps {
    private McbClientOps() {}

    /** Bounded retries for the deferred pauseOnLostFocus write (≈ a few ticks). */
    private static final int FOCUS_RETRY_LIMIT = 20;

    // =====================================================================
    // §1.2 focus-pause guard (read by GameRendererMixin's @Redirect)
    // =====================================================================
    /**
     * true  = the GameRenderer focus-loss {@code Minecraft.pauseGame(false)} call
     *         is swallowed (default — the point of the v0.5.1 upgrade);
     * false = vanilla behaviour restored.
     *
     * volatile: the TCP thread flips it via client.pause_on_lost_focus while the
     * render thread reads it every frame.
     */
    private static volatile boolean autoPauseGuard = true;

    /** Mixin-facing read — must be public (mixins are in another package). */
    public static boolean autoPauseGuard() {
        return autoPauseGuard;
    }

    private static void setAutoPauseGuard(boolean enabled) {
        autoPauseGuard = enabled;
    }

    // =====================================================================
    // §1.1 focus keep-alive init
    // =====================================================================
    /**
     * Turn off vanilla's "pause the game when the window loses focus".
     *
     * WHY THIS IS DEFERRED instead of a direct options access in
     * McbMod.onInitializeClient: fabric-loader 0.19.5 injects the
     * ClientModInitializer entrypoint from inside {@code Minecraft.<init>} — on
     * >=1.19.4 the hook lands just before {@code Thread.currentThread()}
     * (EntrypointPatch), which is BEFORE {@code this.options = new Options(...)}
     * in the same constructor. {@code Minecraft.getInstance().options} is still
     * null at that point, so the setting is applied from the first client tick:
     * {@code Minecraft.execute()} only queues into the event loop, and the first
     * runTick drains it before any frame renders.
     *
     * Never throws: a dev tool must not take the client down during init.
     */
    static void scheduleFocusKeepAlive() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                McbMod.LOGGER.warn("[MCB] pauseOnLostFocus not disabled: Minecraft instance not available yet");
                return;
            }
            if (mc.options != null) {
                applyFocusKeepAlive(mc.options);
            } else {
                mc.execute(() -> retryFocusKeepAlive(1));
            }
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] pauseOnLostFocus setup failed (ignored): {}", t.toString());
        }
    }

    private static void retryFocusKeepAlive(int attempt) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            if (mc.options == null) {
                if (attempt <= FOCUS_RETRY_LIMIT) {
                    mc.execute(() -> retryFocusKeepAlive(attempt + 1));
                } else {
                    McbMod.LOGGER.warn("[MCB] pauseOnLostFocus left ON: options unavailable after {} ticks",
                            FOCUS_RETRY_LIMIT);
                }
                return;
            }
            applyFocusKeepAlive(mc.options);
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] pauseOnLostFocus setup failed (ignored): {}", t.toString());
        }
    }

    private static void applyFocusKeepAlive(Options options) {
        // Already off: neither rewrite options.txt nor log (spec §1.1).
        if (!options.pauseOnLostFocus) return;
        options.pauseOnLostFocus = false;
        // Persist so a later launch (and unattended e2e runs that skip this mod)
        // stays automation-friendly. Options.save() swallows its own IO errors.
        options.save();
        McbMod.LOGGER.info(
                "[MCB] pauseOnLostFocus disabled (lost focus no longer pauses the game; auto_pause_guard={})",
                autoPauseGuard);
    }

    // =====================================================================
    // §3.15 screen.current
    //
    // WHY: after the v0.5.1 keep-alive change the AI can no longer infer "the
    // game is paused because the window lost focus" from a frozen frame — the
    // bridge now keeps running. screen.current answers "what is on screen right
    // now" from the same predicates the engine itself uses (Minecraft.screen,
    // Minecraft.isWindowActive), so an automation script can tell
    // "still in world / PauseScreen stuck / no world" apart.
    // =====================================================================
    static JsonObject screenJson() {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o = new JsonObject();
        boolean worldLoaded = mc.level != null;
        o.addProperty("in_world", worldLoaded);

        Screen screen = mc.screen;
        if (screen == null) {
            o.add("screen_class", null);
            o.add("screen_title", null);
        } else {
            o.addProperty("screen_class", screen.getClass().getName());
            Component title = screen.getTitle();
            // getString() = the plain text of the title Component (1.21.11).
            o.addProperty("screen_title", title == null ? null : title.getString());
        }

        // Same vanilla predicate the focus-loss pause branch uses
        // (GameRenderer.render -> !isWindowActive()).
        o.addProperty("window_focused", mc.isWindowActive());
        o.addProperty("pause_on_lost_focus", mc.options.pauseOnLostFocus);

        LocalPlayer player = mc.player;
        if (player == null || !worldLoaded) {
            o.add("player", null);
        } else {
            JsonObject p = new JsonObject();
            p.addProperty("dimension", mc.level.dimension().identifier().toString());
            JsonArray pos = new JsonArray();
            // Feet position (Player.getX/Y/Z), doubles — matches player.info.
            pos.add(player.getX());
            pos.add(player.getY());
            pos.add(player.getZ());
            p.add("pos", pos);
            if (mc.gameMode != null) {
                p.addProperty("game_mode", mc.gameMode.getPlayerMode().getName());
            } else {
                p.add("game_mode", null);
            }
            o.add("player", p);
        }
        return o;
    }

    // =====================================================================
    // §3.17 client.pause_on_lost_focus
    // =====================================================================
    /**
     * Read the vanilla auto-pause switch, optionally write it.
     *
     * The guard is coupled to the option on purpose: {@code enabled=true} is the
     * "temporarily restore vanilla behaviour for a compatibility test" path, and
     * it would be defeated by our own @Redirect guard still swallowing the call,
     * so the guard is turned off together with re-enabling the option.
     */
    static JsonObject pauseToggle(Boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        Options options = mc.options;
        if (enabled != null) {
            options.pauseOnLostFocus = enabled;
            options.save();
            setAutoPauseGuard(!enabled);
        }
        JsonObject o = new JsonObject();
        o.addProperty("enabled", options.pauseOnLostFocus);
        o.addProperty("auto_pause_guard", autoPauseGuard);
        return o;
    }

    // =====================================================================
    // §3.16 client.screenshot — request state machine (timing in spec §3.1)
    //
    // 1. request (main thread): filename sanitized, request queued, hideGui
    //    taken over BEFORE the frame that will be captured renders;
    // 2. McbScreenshot.onFrameEnd() (render thread, RETURN of
    //    GameRenderer.render): pixels are read into a NativeImage — that frame
    //    already contains the GUI + any open Screen (verified: 1.21.11 renders
    //    gui/screen INSIDE GameRenderer.render);
    // 3. hideGui restored as soon as the pixels are read, then the image goes to
    //    Util.ioPool() for PNG encoding + write, and only after the file exists
    //    the response is sent (protocol §3.16: "文件写完后才响应").
    // =====================================================================
    private static final class ShotRequest {
        final String filename;
        final boolean hideHud;
        final String screenClass;
        final Consumer<JsonObject> onOk;
        final Consumer<String> onError;

        ShotRequest(String filename, boolean hideHud, String screenClass,
                    Consumer<JsonObject> onOk, Consumer<String> onError) {
            this.filename = filename;
            this.hideHud = hideHud;
            this.screenClass = screenClass;
            this.onOk = onOk;
            this.onError = onError;
        }
    }

    /**
     * Pending captures. Touched ONLY from the client main/render thread (one and
     * the same thread in 1.21.11), so a plain ArrayDeque is safe — do not call
     * the queue from the TCP thread.
     */
    private static final Deque<ShotRequest> SHOT_QUEUE = new ArrayDeque<>();

    /** Previous options.hideGui while a hide_hud shot owns it; null = not owned. */
    private static Boolean hideGuiSaved;

    static void requestScreenshot(String rawFilename, boolean hideHud,
                                  Consumer<JsonObject> onOk, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        // Hop to the main thread: arming the request and flipping hideGui must
        // happen BEFORE the frame that will be captured is rendered.
        mc.execute(() -> {
            try {
                String filename = sanitizeFilename(rawFilename);
                Screen screen = mc.screen;
                if (hideHud && hideGuiSaved == null) {
                    // The frame-end injection point runs AFTER the HUD/Screen draw
                    // (verified: GameRenderer.render draws gui + screen), so hiding
                    // has to be armed before rendering starts (§3.1.1).
                    hideGuiSaved = mc.options.hideGui;
                    mc.options.hideGui = true;
                }
                SHOT_QUEUE.add(new ShotRequest(filename, hideHud, screenClassName(screen), onOk, onError));
            } catch (Throwable t) {
                restoreHideGuiIfIdle();
                onError.accept("failed to arm screenshot: " + t);
            }
        });
    }

    private static String screenClassName(Screen screen) {
        return screen == null ? null : screen.getClass().getName();
    }

    /**
     * Called from GameRendererMixin at RETURN of
     * {@code render(Lnet/minecraft/client/DeltaTracker;Z)V} on the render thread.
     *
     * One request per frame: the captured pixels are exactly what was just drawn,
     * including the vanilla HUD and any open Screen.
     */
    public static void onFrameEnd() {
        if (SHOT_QUEUE.isEmpty()) {
            // Self-healing: if a capture failed after hideGui was taken over, the
            // HUD must not stay hidden forever.
            restoreHideGuiIfIdle();
            return;
        }
        final ShotRequest req = SHOT_QUEUE.poll();
        JsonObject meta = new JsonObject();
        meta.addProperty("hide_hud", req.hideHud);
        if (req.screenClass == null) {
            meta.add("screen_class", null);
        } else {
            meta.addProperty("screen_class", req.screenClass);
        }
        McbScreenshot.captureToFile(req.filename, meta,
                // pixels are in hand -> release hideGui before encoding/writing (§3.1.2)
                McbClientOps::restoreHideGuiIfIdle,
                req.onOk, req.onError);
    }

    /**
     * Restore options.hideGui once no hide_hud capture is in flight. Multiple
     * back-to-back hide_hud shots keep the HUD hidden until the last one has
     * read its pixels (otherwise shot #2 would capture the HUD again).
     */
    private static void restoreHideGuiIfIdle() {
        if (hideGuiSaved == null) return;
        for (ShotRequest pending : SHOT_QUEUE) {
            if (pending.hideHud) return; // last hide_hud shot still owns the flag
        }
        setHideGuiSafely(hideGuiSaved);
        hideGuiSaved = null;
    }

    private static void setHideGuiSafely(boolean value) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                mc.options.hideGui = value;
            }
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] failed to restore hideGui: {}", t.toString());
        }
    }

    /**
     * §3.16 filename rules: default {@code mcb-<epochMillis>.png}, basename only,
     * characters outside {@code [A-Za-z0-9._-]} stripped, ".png" appended.
     *
     * The sanitizing is a security boundary, not cosmetics: the value ends up in
     * a file path under run/screenshots, so ".." segments or separators must not
     * survive (a path traversal here would let a socket client write anywhere the
     * game can).
     */
    static String sanitizeFilename(String raw) {
        String fallback = "mcb-" + System.currentTimeMillis() + ".png";
        if (raw == null) return fallback;
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[^A-Za-z0-9._-]", "");
        // "." / ".." survive the character filter but are path segments.
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return fallback;
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) name = name + ".png";
        return name;
    }

    // =====================================================================
    // §3.18 client.reload_resources (L2 hot reload)
    // =====================================================================
    /**
     * Equals pressing F3+T. Must be called on the client main thread: it rebuilds
     * the resource manager, uploads textures and may install a LoadingOverlay.
     *
     * The response is sent from the CompletableFuture callback, i.e. possibly on
     * a background thread — McbServer.sendOk/sendError only touch the outbound
     * queue (never the game), which is exactly the discipline protocol §6 wants.
     */
    static void reloadResources(Consumer<JsonObject> onOk, Consumer<String> onError) {
        Minecraft mc = Minecraft.getInstance();
        long startedAt = System.currentTimeMillis();
        mc.execute(() -> {
            try {
                mc.reloadResourcePacks().whenComplete((ignored, error) -> {
                    if (error != null) {
                        onError.accept("resource reload failed: " + error);
                        return;
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("reloaded", true);
                    data.addProperty("duration_ms", System.currentTimeMillis() - startedAt);
                    onOk.accept(data);
                });
            } catch (Throwable t) {
                onError.accept(t.getClass().getName() + ": " + t.getMessage());
            }
        });
    }
}
