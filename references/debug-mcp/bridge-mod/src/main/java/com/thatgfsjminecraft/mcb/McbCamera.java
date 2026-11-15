package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * "Sky-eye" free camera (protocol §3.12 camera.eye / camera.set / camera.clear).
 *
 * WHY THIS SHAPE (the three constraints that drove the design):
 *
 * 1. Everything here is consumed by CameraMixin / GameRendererMixin ON the
 *    render thread inside the vanilla frame pipeline:
 *      updateCamera -> Camera.setup (TAIL: we overwrite pos/yaw/pitch)
 *      -> renderLevel -> pick  (crosshair raycast uses the SAME camera, so
 *         Jade/WTHIT tooltips follow the virtual camera for free)
 *      -> getFov (TAIL: we override the projection fov when asked)
 *    A one-shot therefore costs exactly one extra frame (~16ms) — no teleport,
 *    no falling, no player-state mutation.
 *
 * 2. State is a tiny set of volatile primitives. The TCP thread writes them
 *    only via startOneShot/setPersistent/clear, which are ALWAYS called on the
 *    main thread (dispatch hops first) — the render thread only reads them.
 *
 * 3. The one-shot capture is scheduled from inside CameraMixin AFTER the camera
 *    has been overwritten, via Minecraft.execute(). That task runs between
 *    frames (runTick drains the queue before rendering), so by the time it
 *    fires the framebuffer still holds the frame that was rendered with the
 *    virtual camera — and the next frame has not overwritten it yet. The read
 *    path itself is the already-verified McbScreenshot.take.
 */
public final class McbCamera {
    private McbCamera() {}

    /** Override currently applied by the mixins every frame. */
    private static volatile boolean active = false;
    /** true for camera.set (survives capture-less frames), false for one-shot. */
    private static volatile boolean persistent = false;
    private static volatile double x, y, z;
    private static volatile float yaw, pitch;
    /** Projection fov override; <= 0 means "keep vanilla fov behaviour". */
    private static volatile float fov = -1f;
    /**
     * Sky-eye UI suppression (user requirement): while a camera op owns the
     * view, hide the VANILLA HUD clutter (hotbar, hearts, chat, crosshair...)
     * so screenshots are clean, but keep information overlays from mods
     * (Jade tooltip, minihud...) — they render through their own hooks and
     * are NOT affected by GuiMixin. Restored when the camera op ends.
     *
     * DERIVED from `active` on purpose: a separate flag can drift out of sync
     * with the camera override (that drift was a real bug — the one-shot
     * cleared the HUD flag before the pixels were read, so camera.eye shipped
     * a frame with the HUD still visible). One source of truth, one state.
     */

    /** One-shot pipeline: set at request time, cleared at capture time. */
    private static final AtomicBoolean captureQueued = new AtomicBoolean(false);
    private static volatile Consumer<JsonObject> pendingOk;
    private static volatile Consumer<String> pendingErr;

    // ------------------------------------------------------------- mixin reads
    /** Mixin-facing reads — must be public (mixins live in the .mcb.mixin subpackage). */
    public static boolean overrideActive() { return active; }
    public static double camX() { return x; }
    public static double camY() { return y; }
    public static double camZ() { return z; }
    public static float camYaw() { return yaw; }
    public static float camPitch() { return pitch; }
    public static boolean hasFovOverride() { return fov > 0f; }
    public static float fovOverride() { return fov; }

    /**
     * UI suppression is ON exactly while a camera override is active — derived,
     * never stored separately. `active` is cleared only AFTER the pixels have
     * been read (see capture()), so the captured frame really is HUD-free.
     */
    public static boolean uiSuppressed() { return active; }

    /**
     * Called from CameraMixin on the render thread after the camera pose has
     * been overwritten. For a one-shot this is the moment the virtual-camera
     * frame is being built — schedule the capture between frames.
     */
    public static void onSetupApplied() {
        // Note: a one-shot pending while camera.set switches to persistent still
        // completes (capture() keeps persistent state alive); the response pose
        // is read at capture time, so it always matches the frame actually shot.
        if (pendingOk == null) return;
        if (captureQueued.compareAndSet(false, true)) {
            Minecraft.getInstance().execute(McbCamera::capture);
        }
    }

    // ------------------------------------------------------------ control (main thread only)
    static void startOneShot(double px, double py, double pz, float pYaw, float pPitch,
                             Float pFov, Consumer<JsonObject> ok, Consumer<String> err) {
        x = px; y = py; z = pz;
        yaw = pYaw; pitch = pPitch;
        fov = pFov == null ? -1f : Math.min(Math.max(pFov, 1f), 360f);
        pendingOk = ok;
        pendingErr = err;
        captureQueued.set(false);
        active = true;   // persistent stays whatever it was; one-shot wins per-frame
        persistent = false;
    }

    static void setPersistent(double px, double py, double pz, float pYaw, float pPitch, Float pFov) {
        x = px; y = py; z = pz;
        yaw = pYaw; pitch = pPitch;
        fov = pFov == null ? -1f : Math.min(Math.max(pFov, 1f), 360f);
        active = true;
        persistent = true;
    }

    /**
     * Exit free camera. Cancels an un-captured one-shot by calling onErr.
     */
    static void clear(Consumer<String> onOneShotCancelled) {
        if (pendingOk != null && !captureQueued.get()) {
            // Capture not yet queued: the one-shot can no longer complete.
            pendingOk = null;
            pendingErr = null;
            if (onOneShotCancelled != null) {
                onOneShotCancelled.accept("camera request cancelled by camera.clear");
            }
        }
        active = false;
        persistent = false;
        fov = -1f;
    }

    /** status.cameraMode — additive field, never null. */
    static String statusMode() {
        if (persistent) return "persistent";
        if (active || pendingOk != null) return "one-shot";
        return "none";
    }

    /** camera.set / camera.clear response body. */
    static JsonObject describePersistent() {
        JsonObject o = new JsonObject();
        o.addProperty("active", active);
        o.addProperty("mode", statusMode());
        o.add("pose", poseJson());
        return o;
    }

    // ------------------------------------------------------------ capture (main thread, between frames)
    private static void capture() {
        Consumer<JsonObject> ok = pendingOk;
        Consumer<String> err = pendingErr;
        pendingOk = null;
        pendingErr = null;
        JsonObject extra = new JsonObject();
        extra.add("pose", poseJson());
        extra.add("chunks", chunksJson());
        // Reuse the verified GL read path. It hops once more via execute(),
        // which still runs BEFORE the next frame overwrites the framebuffer.
        //
        // IMPORTANT (ordering bug fixed): the camera override must stay active
        // until the pixels have actually been read, otherwise the captured frame
        // is rendered with the HUD back and the "sky-eye" shot ships clutter.
        // So `active` is cleared inside the ok callback (after the read), not
        // here. If camera.set was issued while this one-shot was pending,
        // persistent mode owns the camera now — leave active/fov alone.
        McbScreenshot.take(
                img -> {
                    for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : extra.entrySet()) {
                        img.add(e.getKey(), e.getValue());
                    }
                    if (!persistent) {
                        // Pixels are in hand — restore vanilla camera behaviour so
                        // the next frame is already the player's view.
                        active = false;
                        fov = -1f;
                    }
                    ok.accept(img);
                },
                err);
    }

    private static JsonObject poseJson() {
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        p.addProperty("yaw", yaw);
        p.addProperty("pitch", pitch);
        if (hasFovOverride()) {
            p.addProperty("fov", fov);
        } else {
            p.add("fov", null);
        }
        return p;
    }

    /**
     * The one real pitfall of remote cameras: chunks outside the player's view
     * distance are not loaded client-side and the shot would be void. Report
     * what the client actually has around the camera so the caller can react
     * (e.g. command.run "forceload add ..." first, then shoot again).
     */
    private static JsonObject chunksJson() {
        JsonObject o = new JsonObject();
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            o.add("centerLoaded", null);
            o.add("loadedNearby", null);
            return o;
        }
        int cx = (int) Math.floor(x) >> 4;
        int cz = (int) Math.floor(z) >> 4;
        ChunkSource chunks = level.getChunkSource();
        o.addProperty("centerLoaded", chunks.hasChunk(cx, cz));
        int loaded = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (chunks.hasChunk(cx + dx, cz + dz)) loaded++;
            }
        }
        o.addProperty("loadedNearby", loaded);
        o.addProperty("nearbyWindow", 25);
        return o;
    }
}
