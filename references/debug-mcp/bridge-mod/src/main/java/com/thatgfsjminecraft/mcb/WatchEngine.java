package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCB v1 §7 — watch session engine: global registry, tick pump, lifecycle
 * reclamation and best-effort event fan-out.
 *
 * <p>Threading model:
 * <ul>
 *   <li>TCP threads only parse/validate params and register/lookup sessions
 *       (registry operations are bounded and never touch the world).</li>
 *   <li>a daemon <em>tick pump</em> polls {@code Minecraft.getSingleplayerServer()}
 *       {@code getTickCount()} and, when it changed, posts
 *       {@code server.execute(() -> onServerTick(...))} so every world read,
 *       rule evaluation and report build happens on the integrated-server
 *       thread (§7.10.1).</li>
 *   <li>{@code watch.await} blocks on the caller's IO-pool thread only (the
 *       session monitor), never the game thread (§7.4).</li>
 *   <li>event pushes are a single non-blocking {@code offer} per connection;
 *       a full queue drops the event and is counted in
 *       {@code status.droppedEvents} (§2.3 / §7.8).</li>
 * </ul>
 *
 * <p>Lifecycle (§7.9): active sessions ≤8, closed sessions keep up to 64
 * reports for a 60 s retention window, owner disconnect gives a 60 s grace
 * window, {@code interval}/{@code repeat} sessions idle for 300 s are
 * force-stopped, and world/dimension/server changes stop the affected sessions
 * with the §7.8 reason codes.
 */
final class WatchEngine {
    private WatchEngine() {}

    /** §7.1: concurrent active sessions. */
    static final int MAX_ACTIVE = 8;
    /** §7.9: report retention after a session closed. */
    static final long RETENTION_MS = 60_000L;
    /** §7.9: idle reclamation for interval/repeat sessions. */
    static final long IDLE_MS = 300_000L;
    /** §7.9: owner disconnect protection window. */
    static final long DISCONNECT_GRACE_MS = 60_000L;

    interface Ok { void ok(JsonObject data); }
    interface Err { void err(String code, String message); }

    /** Registry lock: mutations are tiny and never call into the world. */
    private static final Object LOCK = new Object();
    private static final Map<String, WatchSession> ACTIVE = new LinkedHashMap<>();
    /** Closed sessions within the 60 s retention window (reports still awaitable). */
    private static final Map<String, WatchSession> RETAINED = new LinkedHashMap<>();

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile long lastKnownTick = -1;
    private static ScheduledExecutorService scheduler;

    // =====================================================================
    // Bootstrap (McbMod.onInitializeClient, wrapped in try/catch by the caller)
    // =====================================================================
    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcb-watch-timer");
            t.setDaemon(true);
            return t;
        });
        Thread pump = new Thread(WatchEngine::pumpLoop, "mcb-watch-pump");
        pump.setDaemon(true);
        pump.start();
    }

    // =====================================================================
    // watch.start (§7.1) — params already parsed; capture happens on server thread
    // =====================================================================
    static void startWatch(JsonObject params, Object owner, Ok ok, Err err) {
        // Pure validation happens right here on the TCP thread: BAD_PARAMS /
        // TOO_LARGE / RULE_INVALID must be immediate (§7.1).
        WatchSession.Config cfg = WatchSession.parse(params);
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || server.isStopped() || !server.isRunning()) {
            throw new McbServer.OpException("WORLD_NOT_LOADED",
                    "watch sessions require an integrated server (server-authoritative sampling)");
        }
        synchronized (LOCK) {
            if (ACTIVE.size() >= MAX_ACTIVE) {
                throw new McbServer.OpException("TOO_LARGE",
                        "watch session limit reached (" + MAX_ACTIVE + " concurrent sessions)");
            }
            if (ACTIVE.containsKey(cfg.watchId) || RETAINED.containsKey(cfg.watchId)) {
                throw new McbServer.OpException("BAD_PARAMS",
                        "watch_id already in use: " + cfg.watchId);
            }
        }
        final long startLogId = McbLogCapture.highestId();
        final String watchId = cfg.watchId;
        try {
            server.execute(() -> {
                try {
                    synchronized (LOCK) { // re-check: another request may have won the race
                        if (ACTIVE.size() >= MAX_ACTIVE) {
                            err.err("TOO_LARGE", "watch session limit reached (" + MAX_ACTIVE + " concurrent sessions)");
                            return;
                        }
                        if (ACTIVE.containsKey(watchId) || RETAINED.containsKey(watchId)) {
                            err.err("BAD_PARAMS", "watch_id already in use: " + watchId);
                            return;
                        }
                    }
                    ResourceKey<Level> key = resolveDimension(server, cfg.dimensionId);
                    ServerLevel level = server.getLevel(key);
                    if (level == null) {
                        err.err("WORLD_NOT_LOADED", "dimension not loaded: " + key.identifier());
                        return;
                    }
                    long startTick = server.getTickCount();
                    // t0 baseline: blocks always (non_air_count is a mandatory
                    // response field), entities only when the track is enabled.
                    WatchSnapshot.Sample t0 = WatchSnapshot.capture(level, cfg.bounds, cfg.tracked,
                            cfg.origin, cfg.maxEntityCount, true, cfg.tracked.entitiesEnabled);
                    WatchSession session = new WatchSession(cfg, owner, key, startTick, startLogId, t0);
                    synchronized (LOCK) {
                        ACTIVE.put(watchId, session);
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("watch_id", watchId);
                    data.addProperty("side", "server");
                    data.addProperty("non_air_count", t0.nonAirCount);
                    data.addProperty("entity_count", t0.entities.size());
                    data.addProperty("entity_count_truncated", t0.entityCountTruncated);
                    JsonArray chunks = new JsonArray();
                    for (long packed : t0.truncatedChunks) {
                        JsonArray pair = new JsonArray();
                        pair.add((int) (packed >> 32));
                        pair.add((int) (packed & 0xffffffffL));
                        chunks.add(pair);
                    }
                    data.add("truncated_chunks", chunks);
                    data.addProperty("seq", 0);
                    ok.ok(data);
                } catch (McbServer.OpException e) {
                    err.err(e.code, e.getMessage());
                } catch (Throwable t) {
                    err.err("INTERNAL", t.getClass().getName() + ": " + t.getMessage());
                }
            });
        } catch (RuntimeException e) {
            // RejectedExecutionException when the server is shutting down.
            throw new McbServer.OpException("WORLD_NOT_LOADED",
                    "integrated server is stopping: " + e.getClass().getSimpleName());
        }
    }

    private static ResourceKey<Level> resolveDimension(MinecraftServer server, String dimensionId) {
        if (dimensionId != null) {
            return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId));
        }
        var players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            return players.get(0).level().dimension();
        }
        return Level.OVERWORLD;
    }

    // =====================================================================
    // watch.await (§7.4) — caller runs on the IO pool, blocks on the session monitor
    // =====================================================================
    static JsonObject awaitWatch(String watchId, long afterSeq, long waitMs) {
        WatchSession session;
        synchronized (LOCK) {
            session = ACTIVE.get(watchId);
            if (session == null) session = RETAINED.get(watchId);
        }
        if (session == null) {
            throw new McbServer.OpException("WATCH_NOT_FOUND",
                    "unknown or expired watch session: " + watchId);
        }
        return session.awaitData(afterSeq, waitMs);
    }

    // =====================================================================
    // watch.snapshot (§7.5)
    // =====================================================================
    static void snapshotWatch(String watchId, Ok ok, Err err) {
        WatchSession session;
        synchronized (LOCK) {
            session = ACTIVE.get(watchId);
        }
        if (session == null) {
            throw new McbServer.OpException("WATCH_NOT_FOUND",
                    "unknown watch session (or its baseline was already released): " + watchId);
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || server.isStopped() || !server.isRunning()) {
            throw new McbServer.OpException("WORLD_NOT_LOADED",
                    "watch sessions require an integrated server (server-authoritative sampling)");
        }
        final WatchSession s = session;
        try {
            server.execute(() -> {
                try {
                    if (s.isClosed()) {
                        err.err("WATCH_NOT_FOUND", "watch session stopped: " + watchId);
                        return;
                    }
                    ServerLevel level = server.getLevel(s.levelKey);
                    if (level == null) {
                        err.err("WORLD_NOT_LOADED", "dimension not loaded: " + s.dimensionName());
                        return;
                    }
                    ok.ok(s.buildSnapshotReport(level, server.getTickCount()));
                } catch (McbServer.OpException e) {
                    err.err(e.code, e.getMessage());
                } catch (Throwable t) {
                    err.err("INTERNAL", t.getClass().getName() + ": " + t.getMessage());
                }
            });
        } catch (RuntimeException e) {
            throw new McbServer.OpException("WORLD_NOT_LOADED",
                    "integrated server is stopping: " + e.getClass().getSimpleName());
        }
    }

    // =====================================================================
    // watch.list (§7.6) — metadata only, no world reads
    // =====================================================================
    static JsonObject listWatches() {
        JsonArray watches = new JsonArray();
        long tick = lastKnownTick;
        synchronized (LOCK) {
            for (WatchSession s : ACTIVE.values()) {
                if (!s.isClosed()) watches.add(s.metadata(tick));
            }
        }
        JsonObject data = new JsonObject();
        data.add("watches", watches);
        return data;
    }

    // =====================================================================
    // watch.stop (§7.7)
    // =====================================================================
    static JsonObject stopWatch(String watchId, Object owner) {
        WatchSession session;
        synchronized (LOCK) {
            session = ACTIVE.get(watchId);
            if (session == null) {
                throw new McbServer.OpException("WATCH_NOT_FOUND",
                        "unknown or already stopped watch session: " + watchId);
            }
        }
        if (!session.isOwnedBy(owner)) {
            throw new McbServer.OpException("BAD_PARAMS",
                    "not the owner of watch session: " + watchId);
        }
        JsonObject finalReport = session.finalReport();
        stopSession(session, "manual");
        JsonObject data = new JsonObject();
        data.addProperty("stopped", true);
        data.add("final_report", finalReport);
        return data;
    }

    // =====================================================================
    // Connection close (§7.9): 60 s protection window, then client_disconnected
    // =====================================================================
    static void onConnectionClosed(Object owner) {
        ScheduledExecutorService exec = scheduler;
        if (exec == null) return;
        try {
            exec.schedule(() -> {
                List<WatchSession> owned = new ArrayList<>();
                synchronized (LOCK) {
                    for (WatchSession s : ACTIVE.values()) {
                        if (s.isOwnedBy(owner)) owned.add(s);
                    }
                }
                for (WatchSession s : owned) {
                    stopSession(s, "client_disconnected");
                }
            }, DISCONNECT_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // Scheduler shutting down (game exit) — nothing to reclaim.
        }
    }

    // =====================================================================
    // Tick pump
    // =====================================================================
    private static void pumpLoop() {
        IntegratedServer lastServer = null;
        long lastTick = -1;
        long stoppedSince = -1;
        long lastHousekeeping = 0;
        int failures = 0;
        while (true) {
            try {
                IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
                boolean running = server != null && server.isRunning() && !server.isStopped();
                long now = System.currentTimeMillis();
                if (!running) {
                    if (lastServer != null) {
                        // Grace: a world switch stops the old server and starts a
                        // new one; distinguish "changed" from "stopped".
                        if (stoppedSince < 0) stoppedSince = now;
                        if (now - stoppedSince >= 1000) {
                            stopAll("server_stopped");
                            lastServer = null;
                            lastTick = -1;
                            stoppedSince = -1;
                        }
                    }
                } else {
                    if (lastServer != null && lastServer != server) stopAll("server_changed");
                    lastServer = server;
                    stoppedSince = -1;
                    int tick = server.getTickCount();
                    if (tick != lastTick) {
                        lastTick = tick;
                        lastKnownTick = tick;
                        final IntegratedServer s = server;
                        final int t = tick;
                        try {
                            // §7.10.1: all world access is posted to the server thread.
                            server.execute(() -> onServerTick(s, t));
                        } catch (RuntimeException ignored) {
                            // server shutting down between the check and the post
                        }
                    }
                }
                if (now - lastHousekeeping >= 500) {
                    lastHousekeeping = now;
                    housekeeping();
                }
                sleep(hasSessions() ? 5 : 25);
            } catch (Throwable t) {
                if (failures++ < 5) {
                    McbMod.LOGGER.warn("[MCB] watch tick pump error (ignored): {}", t.toString());
                }
                sleep(50);
            }
        }
    }

    private static boolean hasSessions() {
        synchronized (LOCK) {
            return !ACTIVE.isEmpty();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** SERVER THREAD. */
    private static void onServerTick(MinecraftServer server, int tick) {
        lastKnownTick = tick;
        List<WatchSession> sessions;
        synchronized (LOCK) {
            sessions = new ArrayList<>(ACTIVE.values());
        }
        for (WatchSession session : sessions) {
            if (session.isClosed()) continue;
            try {
                ServerLevel level = server.getLevel(session.levelKey);
                if (level == null) {
                    stopSession(session, "dimension_unloaded"); // §7.9
                    continue;
                }
                session.onServerTick(level, tick);
            } catch (Throwable t) {
                McbMod.LOGGER.error("[MCB] watch session {} failed (stopped): {}", session.watchId, t.toString());
                stopSession(session, "auto_stop");
            }
        }
    }

    private static void stopAll(String reason) {
        List<WatchSession> sessions;
        synchronized (LOCK) {
            sessions = new ArrayList<>(ACTIVE.values());
        }
        for (WatchSession s : sessions) {
            stopSession(s, reason);
        }
    }

    /**
     * Close one session and move it to the retention window. Idempotent: a
     * session already removed from ACTIVE (auto-stop / concurrent stop) is
     * ignored, so {@code watch.stopped} is pushed exactly once.
     */
    static void stopSession(WatchSession session, String reason) {
        String watchId = session.watchId;
        boolean stopped = false;
        synchronized (LOCK) {
            if (ACTIVE.get(watchId) != session) return;
            session.close(reason);
            ACTIVE.remove(watchId);
            RETAINED.put(watchId, session);
            stopped = true;
        }
        if (stopped) {
            JsonObject data = new JsonObject();
            data.addProperty("watch_id", watchId);
            data.addProperty("reason", reason);
            broadcast("watch.stopped", data);
            McbMod.LOGGER.debug("[MCB] watch session {} stopped: {}", watchId, reason);
        }
    }

    // =====================================================================
    // Housekeeping: retention + idle reclamation (§7.9)
    // =====================================================================
    private static void housekeeping() {
        long now = System.currentTimeMillis();
        List<WatchSession> expired = new ArrayList<>();
        List<WatchSession> idle = new ArrayList<>();
        synchronized (LOCK) {
            RETAINED.entrySet().removeIf(e -> {
                if (now - e.getValue().closedAtMs() > RETENTION_MS) {
                    expired.add(e.getValue());
                    return true;
                }
                return false;
            });
            for (WatchSession s : ACTIVE.values()) {
                if (s.isClosed()) continue;
                String type = s.rules.root.type;
                if (!type.equals("interval") && !type.equals("repeat")) continue;
                if (now - s.lastActivityMs > IDLE_MS && now - s.lastReportMs() > IDLE_MS) {
                    idle.add(s);
                }
            }
        }
        for (WatchSession s : idle) {
            stopSession(s, "idle_timeout");
        }
        if (!expired.isEmpty()) {
            McbMod.LOGGER.debug("[MCB] {} watch session report buffer(s) released after retention", expired.size());
        }
    }

    // =====================================================================
    // Best-effort events (§7.8 / §2.3)
    // =====================================================================
    static void broadcastTriggered(WatchSession session, JsonObject report) {
        JsonObject data = new JsonObject();
        data.addProperty("watch_id", session.watchId);
        data.addProperty("seq", report.get("seq").getAsLong());
        data.addProperty("trigger_reason", report.get("trigger_reason").getAsString());
        data.add("report", report);
        broadcast("watch.triggered", data);
    }

    private static void broadcast(String event, JsonObject data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("event", event);
        envelope.add("data", data);
        McbServer.broadcastEvent(envelope);
    }
}
