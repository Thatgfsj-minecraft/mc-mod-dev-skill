package com.thatgfsjminecraft.mcb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Client-side implementation of protocol §8 (v0.5.4): world.create, world.delete
 * and the best-effort world.progress event.
 *
 * THREADING (§8.7): TCP thread validates params only; the client tick runs the
 * state machine (disconnect + createFreshLevel are blocking vanilla calls and
 * must never run on the TCP thread); the integrated-server thread applies
 * gamerules/time/weather/spawn (§6.1); a background IO pool does the recursive
 * delete and the marker write (§8.7.2). Response uniqueness: McbServer's
 * responded set plus Active#done, so TIMEOUT is never followed by a second
 * response. Single-flight: one Active at a time (§8.7.4).
 */
final class McbWorldOps {
    private McbWorldOps() {}

    // =====================================================================
    // Constants (§8.1 / §8.4)
    // =====================================================================
    /** §8.4 safety red line: only these directories may be deleted. */
    static final String MARKER_FILE = ".mcb-world.json";
    /** §8.1: save-directory name whitelist (also blocks ".." / path separators). */
    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SAFE_PREFIX = "mcb_";

    private static final int CREATE_TIMEOUT_DEFAULT = 120_000;
    private static final int CREATE_TIMEOUT_MIN = 30_000;
    private static final int CREATE_TIMEOUT_MAX = 600_000;
    private static final int DELETE_TIMEOUT_DEFAULT = 60_000;
    private static final int DELETE_TIMEOUT_MIN = 10_000;
    private static final int DELETE_TIMEOUT_MAX = 300_000;

    /** §8.2: void_platform platform = 9x9 bedrock at Y=64, spawn feet at Y=65. */
    private static final int VOID_PLATFORM_Y = 64;
    private static final int VOID_PLATFORM_HALF = 4;
    private static final int VOID_SPAWN_Y = 65;
    /** §8.2: teleport the player back when it is already falling below this Y. */
    private static final double VOID_RESCUE_BELOW_Y = 60.0;

    private static final Gson GSON = new Gson();

    /** Long IO (recursive delete, marker write) — never the game thread. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcb-world-io");
        t.setDaemon(true);
        return t;
    });

    /** Single-flight guard (§8.7.4). Guarded by LOCK for cross-thread visibility. */
    private static final Object LOCK = new Object();
    private static volatile Active active;

    /** Kept in sync with gradle.properties mod_version / fabric.mod.json. */
    static final String MOD_VERSION = "0.5.0";

    // =====================================================================
    // Protocol enums / request records
    // =====================================================================
    /** §8.2 four presets. */
    enum Preset {
        BEDROCK_LAB("bedrock_lab"),
        FLAT_CLEAN("flat_clean"),
        VANILLA_QUIET("vanilla_quiet"),
        VOID_PLATFORM("void_platform");

        final String protocolName;

        Preset(String protocolName) {
            this.protocolName = protocolName;
        }

        static Preset fromProtocol(String s) {
            for (Preset p : values()) {
                if (p.protocolName.equals(s)) return p;
            }
            return null;
        }
    }

    /** §8.1 lock_weather. */
    enum Weather {
        CLEAR, RAIN, THUNDER;

        static Weather fromProtocol(String s) {
            return switch (s) {
                case "clear" -> CLEAR;
                case "rain" -> RAIN;
                case "thunder" -> THUNDER;
                default -> null;
            };
        }
    }

    /** Validated world.create request (§8.1 defaults applied by parseCreate). */
    record CreateRequest(String name, Preset preset, boolean cleanIfExists, GameType gameMode,
                         boolean generateStructures, boolean doMobSpawning, Difficulty difficulty,
                         Integer lockTime, Weather lockWeather, boolean fireTick, boolean keepInventory,
                         boolean allowCommands, long seed, int timeoutMs) {}

    /** Validated world.delete request (§8.4 defaults). */
    record DeleteRequest(String name, boolean force, int timeoutMs) {}

    // =====================================================================
    // Parameter validation (TCP thread only — §8.7.1)
    // =====================================================================

    static CreateRequest parseCreate(JsonObject p) {
        String name = parseName(p);
        JsonElement presetEl = p.get("preset");
        if (presetEl == null || presetEl.isJsonNull() || !presetEl.isJsonPrimitive()
                || !presetEl.getAsJsonPrimitive().isString()) {
            throw new McbServer.OpException("BAD_PARAMS", "preset is required and must be a string");
        }
        Preset preset = Preset.fromProtocol(presetEl.getAsString());
        if (preset == null) {
            throw new McbServer.OpException("PRESET_INVALID",
                    "preset must be one of bedrock_lab, flat_clean, vanilla_quiet, void_platform");
        }
        String gameModeStr = stringParam(p, "game_mode", "creative");
        GameType gameMode = switch (gameModeStr) {
            case "creative" -> GameType.CREATIVE;
            case "survival" -> GameType.SURVIVAL;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> throw new McbServer.OpException("BAD_PARAMS",
                    "game_mode must be one of creative, survival, adventure, spectator");
        };
        String difficultyStr = stringParam(p, "difficulty", "normal");
        Difficulty difficulty = switch (difficultyStr) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard" -> Difficulty.HARD;
            default -> throw new McbServer.OpException("BAD_PARAMS",
                    "difficulty must be one of peaceful, easy, normal, hard");
        };
        Integer lockTime = nullableIntRangeParam(p, "lock_time", 6000, 0, 23_999);
        Weather lockWeather = parseLockWeather(p);
        return new CreateRequest(
                name, preset,
                boolParam(p, "clean_if_exists", true),
                gameMode,
                boolParam(p, "generate_structures", false),
                boolParam(p, "do_mob_spawning", false),
                difficulty,
                lockTime,
                lockWeather,
                boolParam(p, "fire_tick", false),
                boolParam(p, "keep_inventory", true),
                boolParam(p, "allow_commands", true),
                longParam(p, "seed", 12345L),
                intRangeParam(p, "timeoutMs", CREATE_TIMEOUT_DEFAULT, CREATE_TIMEOUT_MIN, CREATE_TIMEOUT_MAX));
    }

    static DeleteRequest parseDelete(JsonObject p) {
        String name = parseName(p);
        return new DeleteRequest(
                name,
                boolParam(p, "force", false),
                intRangeParam(p, "timeoutMs", DELETE_TIMEOUT_DEFAULT, DELETE_TIMEOUT_MIN, DELETE_TIMEOUT_MAX));
    }

    private static String parseName(JsonObject p) {
        JsonElement el = p.get("name");
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new McbServer.OpException("BAD_PARAMS", "name is required and must be a string");
        }
        String name = el.getAsString();
        if (!NAME_RE.matcher(name).matches()) {
            throw new McbServer.OpException("BAD_PARAMS", "name must match [A-Za-z0-9_-]{1,64}");
        }
        return name;
    }

    private static Weather parseLockWeather(JsonObject p) {
        JsonElement el = p.get("lock_weather");
        if (el == null || el.isJsonNull()) {
            // absent -> default "clear"; explicit null -> not locked
            return p.has("lock_weather") ? null : Weather.CLEAR;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new McbServer.OpException("BAD_PARAMS", "lock_weather must be a string or null");
        }
        Weather w = Weather.fromProtocol(el.getAsString());
        if (w == null) {
            throw new McbServer.OpException("BAD_PARAMS",
                    "lock_weather must be one of clear, rain, thunder, or null");
        }
        return w;
    }

    private static String stringParam(JsonObject p, String key, String dflt) {
        JsonElement el = p.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be a string");
        }
        return el.getAsString();
    }

    private static boolean boolParam(JsonObject p, String key, boolean dflt) {
        JsonElement el = p.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be a boolean");
        }
        return el.getAsBoolean();
    }

    private static long longParam(JsonObject p, String key, long dflt) {
        JsonElement el = p.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        if (!el.isJsonPrimitive()) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be an integer");
        }
        var prim = el.getAsJsonPrimitive();
        if (prim.isNumber()) {
            double d = prim.getAsDouble();
            if (d != Math.rint(d) || Double.isNaN(d) || Double.isInfinite(d)) {
                throw new McbServer.OpException("BAD_PARAMS", key + " must be an integer");
            }
            return (long) d;
        }
        if (prim.isString()) {
            try {
                return Long.parseLong(prim.getAsString().trim());
            } catch (NumberFormatException e) {
                throw new McbServer.OpException("BAD_PARAMS", key + " must be an integer");
            }
        }
        throw new McbServer.OpException("BAD_PARAMS", key + " must be an integer");
    }

    private static int intRangeParam(JsonObject p, String key, int dflt, int min, int max) {
        JsonElement el = p.get(key);
        if (el == null || el.isJsonNull()) return dflt;
        long v = longParam(p, key, dflt);
        if (v < min || v > max) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be within " + min + ".." + max);
        }
        return (int) v;
    }

    /** lock_time: absent -> default, explicit null -> "do not lock". */
    private static Integer nullableIntRangeParam(JsonObject p, String key, int dflt, int min, int max) {
        if (p.has(key) && p.get(key).isJsonNull()) return null;
        return intRangeParam(p, key, dflt, min, max);
    }

    // =====================================================================
    // Entry points (TCP thread -> client tick pump)
    // =====================================================================

    static void startCreate(long id, CreateRequest req, Consumer<JsonObject> onOk,
                            BiConsumer<String, String> onError) {
        Active a = new Active(id, req, null, onOk, onError);
        begin(a);
        progress(a, "preparing");
    }

    static void startDelete(long id, DeleteRequest req, Consumer<JsonObject> onOk,
                            BiConsumer<String, String> onError) {
        Active a = new Active(id, null, req, onOk, onError);
        begin(a);
        progress(a, "preparing");
    }

    /** §8.7.4: at most one world.* op in flight. Runs on the TCP thread. */
    private static void begin(Active a) {
        synchronized (LOCK) {
            if (active != null) {
                throw new McbServer.OpException("WORLD_IN_USE",
                        "another world.create/world.delete is already in progress (world_name="
                                + active.name() + ")");
            }
            active = a;
        }
        McbMod.LOGGER.info("[MCB] world.{} started (id={}, world={})",
                a.create != null ? "create" : "delete", a.id, a.name());
    }

    /**
     * §8.7.5 watchdog hook: McbServer already sent the TIMEOUT response; here we
     * only mark the flow so its tick driver stops and cleans up without ever
     * producing a second response.
     */
    static void onTimeout(long id) {
        Active a = active;
        if (a == null || a.id != id) return;
        a.timedOut = true;
        McbMod.LOGGER.warn("[MCB] world.{} id={} exceeded its watchdog; aborting state machine",
                a.create != null ? "create" : "delete", id);
    }

    // =====================================================================
    // Client tick pump
    // =====================================================================

    /** Called from McbClientOps.onClientTick() (client main thread). */
    static void onClientTick() {
        Active a = active;
        if (a == null) return;
        if (a.tickBusy) return; // re-entrancy guard: vanilla pumps runTick inside disconnect*
        a.tickBusy = true;
        try {
            if (a.timedOut) {
                abortTimedOut(a);
                return;
            }
            step(a);
        } catch (McbServer.OpException e) {
            fail(a, e.code, e.getMessage());
        } catch (Throwable t) {
            fail(a, "INTERNAL", t.getClass().getName() + ": " + t.getMessage());
        } finally {
            a.tickBusy = false;
        }
    }

    private enum Phase {
        PREPARING, DISCONNECTING, DELETING, CREATING, AWAITING_JOIN, APPLYING, DONE, FAILED
    }

    /** Mutable state of the one in-flight op. Net-most fields live on the client thread. */
    private static final class Active {
        final long id;
        final CreateRequest create;
        final DeleteRequest delete;
        final Consumer<JsonObject> onOk;
        final BiConsumer<String, String> onError;
        final long startedAt = System.currentTimeMillis();

        Phase phase = Phase.PREPARING;
        boolean tickBusy;
        /** Set once a terminal response was produced or abandoned. */
        boolean done;
        /** Watchdog flag (written from the watchdog thread). */
        volatile boolean timedOut;

        // -- create flow
        boolean preApplyDispatched;
        volatile boolean preApplyDone;
        boolean applyDispatched;
        volatile boolean applyDone;
        volatile JsonObject applyData;
        volatile String applyError;
        boolean markerDispatched;
        volatile boolean markerDone;
        /** True once this op actually left/created a world (guards failure cleanup). */
        boolean worldTouched;

        // -- delete flow
        Future<?> deleteFuture;
        volatile boolean deleteDone;
        volatile String deleteError;

        Active(long id, CreateRequest create, DeleteRequest delete,
               Consumer<JsonObject> onOk, BiConsumer<String, String> onError) {
            this.id = id;
            this.create = create;
            this.delete = delete;
            this.onOk = onOk;
            this.onError = onError;
        }

        String name() {
            return create != null ? create.name() : delete.name();
        }
    }

    // =====================================================================
    // State machine — one phase per tick
    // =====================================================================

    private static void step(Active a) {
        switch (a.phase) {
            case PREPARING -> stepPreparing(a);
            case DISCONNECTING -> stepDisconnecting(a);
            case DELETING -> stepDeleting(a);
            case CREATING -> stepCreating(a);
            case AWAITING_JOIN -> stepAwaitingJoin(a);
            case APPLYING -> stepApplying(a);
            default -> { /* DONE / FAILED: release is done by the terminals */ }
        }
    }

    /** §8.1: create must leave the current world first; delete only if the target is running. */
    private static void stepPreparing(Active a) {
        Minecraft mc = Minecraft.getInstance();
        if (a.create != null) {
            // Fail fast BEFORE tearing down the current world if we would refuse anyway.
            if (!a.create.cleanIfExists() && worldDirExists(mc, a.name())) {
                fail(a, "WORLD_ALREADY_EXISTS",
                        "world '" + a.name() + "' already exists and clean_if_exists=false");
                return;
            }
            if (isInWorld(mc)) {
                a.phase = Phase.DISCONNECTING;
                progress(a, "disconnecting");
                return;
            }
        }
        a.phase = Phase.DELETING; // next tick: existence check (and old-dir purge for create)
    }

    private static boolean worldDirExists(Minecraft mc, String name) {
        try {
            return Files.isDirectory(mc.getLevelSource().getLevelPath(name));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void stepDisconnecting(Active a) {
        Minecraft mc = Minecraft.getInstance();
        try {
            a.worldTouched = true;
            disconnectToTitleScreen(mc);
        } catch (Throwable t) {
            fail(a, "WORLD_IN_USE", "failed to leave the current world: " + t);
            return;
        }
        if (a.timedOut) { abortTimedOut(a); return; }
        // Minecraft.disconnect() waits for IntegratedServer#isShutdown() before
        // returning, so the LevelStorageAccess lock is released at this point
        // (MinecraftServer.stopServer -> storageSource.close()). Only then is a
        // recursive directory delete safe (§8.7.2).
        if (mc.getSingleplayerServer() != null || mc.level != null) {
            fail(a, "WORLD_IN_USE", "world is still running after disconnect");
            return;
        }
        a.phase = Phase.DELETING;
    }

    private static void stepDeleting(Active a) {
        Minecraft mc = Minecraft.getInstance();
        if (a.deleteFuture == null) {
            Path dir;
            try {
                dir = mc.getLevelSource().getLevelPath(a.name());
            } catch (Throwable t) {
                fail(a, "INTERNAL", "bad world path: " + t);
                return;
            }
            boolean exists = Files.isDirectory(dir);
            if (a.create != null) {
                if (!exists) {
                    a.phase = Phase.CREATING;
                    progress(a, "creating");
                    return;
                }
                if (!a.create.cleanIfExists()) {
                    fail(a, "WORLD_ALREADY_EXISTS",
                            "world '" + a.name() + "' already exists and clean_if_exists=false");
                    return;
                }
                progress(a, "deleting");
            } else {
                if (!exists) {
                    fail(a, "WORLD_NOT_FOUND", "no such world: " + a.name());
                    return;
                }
                if (!isDeletable(dir, a.name())) {
                    fail(a, "WORLD_DELETE_FORBIDDEN",
                            "refusing to delete '" + a.name()
                                    + "': name does not start with mcb_ and no " + MARKER_FILE
                                    + " marker found (player worlds are never deletable via this op)");
                    return;
                }
                if (isCurrentWorld(mc, dir)) {
                    if (!a.delete.force()) {
                        fail(a, "WORLD_IN_USE",
                                "world '" + a.name() + "' is currently running; pass force=true to quit it first");
                        return;
                    }
                    a.phase = Phase.DISCONNECTING;
                    progress(a, "disconnecting");
                    return;
                }
                progress(a, "deleting");
            }
            final Path target = dir;
            a.deleteFuture = IO.submit(() -> {
                try {
                    deleteDirectoryRecursively(target);
                    a.deleteDone = true;
                } catch (Throwable t) {
                    a.deleteError = t.getClass().getName() + ": " + t.getMessage();
                }
            });
            return;
        }

        // Background deletion in flight.
        if (!a.deleteDone && a.deleteError == null) return;
        if (a.deleteError != null) {
            fail(a, "INTERNAL", "failed to delete world directory: " + a.deleteError);
            return;
        }
        if (a.timedOut) { abortTimedOut(a); return; }
        if (a.create != null) {
            a.phase = Phase.CREATING;
            progress(a, "creating");
        } else {
            succeedDelete(a);
        }
    }

    /**
     * §8.7.3: createFreshLevel is a BLOCKING vanilla call (pack load, dimension
     * bake, doWorldLoad pumps runTick until the integrated server is ready).
     * Multi-second is expected, never an error — this runs on the client thread
     * and the re-entrancy guard keeps our own tick driver out.
     */
    private static void stepCreating(Active a) {
        Minecraft mc = Minecraft.getInstance();
        try {
            CreateRequest req = a.create;
            a.worldTouched = true;
            Screen parent = new TitleScreen();
            mc.createWorldOpenFlows().createFreshLevel(
                    req.name(),
                    McbWorldPresets.levelSettings(req),
                    new WorldOptions(req.seed(), req.generateStructures(), false),
                    provider -> McbWorldPresets.dimensions(req, provider),
                    parent);
        } catch (Throwable t) {
            fail(a, "INTERNAL", "createFreshLevel failed: " + t.getClass().getName() + ": " + t.getMessage());
            return;
        }
        if (a.timedOut) { abortTimedOut(a); return; }
        if (mc.getSingleplayerServer() == null) {
            // createFreshLevel swallows pack-load failures (it only logs + resets
            // the screen); no integrated server means the world was not created.
            fail(a, "INTERNAL", "world creation did not start an integrated server (pack load failed?)");
            return;
        }
        a.phase = Phase.AWAITING_JOIN;
        progress(a, "awaiting_join");
    }

    /** §8.2/§8.3: apply server rules as soon as the server is ready (usually before
     *  the player joins), then a second pass fixes the player position + spawn. */
    private static void stepAwaitingJoin(Active a) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail(a, "INTERNAL", "integrated server disappeared during world load");
            return;
        }
        if (a.applyError != null) {
            fail(a, "INTERNAL", "applying world settings failed: " + a.applyError);
            return;
        }
        if (!a.preApplyDispatched && server.isReady() && server.getLevel(Level.OVERWORLD) != null) {
            a.preApplyDispatched = true;
            server.execute(() -> {
                try {
                    preApply(server, a.create);
                } catch (Throwable t) {
                    a.applyError = t.getClass().getName() + ": " + t.getMessage();
                } finally {
                    a.preApplyDone = true;
                }
            });
            return;
        }
        if (!a.preApplyDone) return;
        if (!a.applyDispatched && mc.player != null && mc.level != null) {
            a.applyDispatched = true;
            a.phase = Phase.APPLYING;
            progress(a, "applying");
            server.execute(() -> {
                try {
                    a.applyData = finalApply(server, a.create);
                } catch (Throwable t) {
                    a.applyError = t.getClass().getName() + ": " + t.getMessage();
                } finally {
                    a.applyDone = true;
                }
            });
        }
    }

    private static void stepApplying(Active a) {
        if (a.applyError != null) {
            fail(a, "INTERNAL", "applying world settings failed: " + a.applyError);
            return;
        }
        if (!a.applyDone) return;
        // §8.4: the marker must exist before we answer, otherwise an immediate
        // world.delete of a non-mcb_ name could race it and be FORBIDDEN.
        if (!a.markerDispatched) {
            a.markerDispatched = true;
            writeMarkerAsync(a);
            return;
        }
        if (!a.markerDone) return;
        succeedCreate(a);
    }

    // =====================================================================
    // Server-authoritative apply (§8.3)
    // =====================================================================

    /** Runs on the integrated-server thread. Idempotent; safe before the player joins. */
    private static void preApply(MinecraftServer server, CreateRequest req) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) throw new IllegalStateException("overworld level is not loaded");

        GameRules rules = level.getGameRules();
        rules.set(GameRules.SPAWN_MOBS, req.doMobSpawning(), server);
        rules.set(GameRules.KEEP_INVENTORY, req.keepInventory(), server);
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, req.fireTick() ? -1 : 0, server);
        if (req.lockTime() != null) {
            rules.set(GameRules.ADVANCE_TIME, false, server);
            level.setDayTime(req.lockTime());
        }
        if (req.lockWeather() != null) {
            rules.set(GameRules.ADVANCE_WEATHER, false, server);
            switch (req.lockWeather()) {
                case CLEAR -> level.setWeatherParameters(0, 0, false, false);
                case RAIN -> level.setWeatherParameters(0, 12_000, true, false);
                case THUNDER -> level.setWeatherParameters(0, 12_000, true, true);
            }
        }
        // normal difficulty is a hard default (§8.0): summoned hostile mobs must not despawn.
        server.setDifficulty(req.difficulty(), false);

        if (req.preset() == Preset.VOID_PLATFORM) {
            buildVoidPlatform(level);
            // §8.2: world spawn (0,65,0) = on top of the Y=64 platform.
            server.setRespawnData(LevelData.RespawnData.of(
                    Level.OVERWORLD, new BlockPos(0, VOID_SPAWN_Y, 0), 0.0F, 0.0F));
        }
    }

    /** Second server pass: rescue a falling player and read the real spawn (§8.1 spawn). */
    private static JsonObject finalApply(MinecraftServer server, CreateRequest req) {
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) throw new IllegalStateException("overworld level is not loaded");

        if (req.preset() == Preset.VOID_PLATFORM) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!player.level().dimension().equals(Level.OVERWORLD)) continue;
                if (player.getY() >= VOID_RESCUE_BELOW_Y) continue;
                player.teleportTo(level, 0.5, VOID_SPAWN_Y, 0.5, Set.of(),
                        player.getYRot(), player.getXRot(), false);
                player.resetFallDistance();
                McbMod.LOGGER.info("[MCB] world.create: rescued player {} from y={} onto the void platform",
                        player.getName().getString(), player.getY());
            }
        }

        BlockPos spawn = server.getRespawnData().pos();
        JsonArray spawnArr = new JsonArray();
        spawnArr.add(spawn.getX());
        spawnArr.add(spawn.getY());
        spawnArr.add(spawn.getZ());

        JsonArray gamerules = new JsonArray();
        gamerules.add("spawn_mobs");
        if (req.lockTime() != null) gamerules.add("advance_time");
        if (req.lockWeather() != null) gamerules.add("advance_weather");
        gamerules.add("fire_spread_radius_around_player");
        gamerules.add("keep_inventory");

        JsonObject data = new JsonObject();
        data.addProperty("world_name", req.name());
        data.addProperty("preset", req.preset().protocolName);
        data.addProperty("game_mode", req.gameMode().getName());
        data.addProperty("difficulty", req.difficulty().getSerializedName());
        data.add("spawn", spawnArr);
        data.addProperty("seed", req.seed());
        data.add("gamerules_applied", gamerules);
        // Gson's JsonObject.add(key, null) REMOVES the key; the protocol requires an
        // explicit null for "time not locked" (§8.1), so use JsonNull.INSTANCE.
        if (req.lockTime() == null) data.add("time_locked", JsonNull.INSTANCE);
        else data.addProperty("time_locked", req.lockTime());
        return data;
    }

    /** §8.2: 9x9 bedrock platform at Y=64 centered on (0,0). */
    private static void buildVoidPlatform(ServerLevel level) {
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = -VOID_PLATFORM_HALF; x <= VOID_PLATFORM_HALF; x++) {
            for (int z = -VOID_PLATFORM_HALF; z <= VOID_PLATFORM_HALF; z++) {
                level.setBlock(pos.set(x, VOID_PLATFORM_Y, z), bedrock, 3);
            }
        }
    }

    // =====================================================================
    // Disconnect helper (§8.1 disconnecting phase)
    // =====================================================================

    private static boolean isInWorld(Minecraft mc) {
        return mc.level != null || mc.getSingleplayerServer() != null || mc.getConnection() != null;
    }

    private static boolean isCurrentWorld(Minecraft mc, Path dir) {
        if (mc.getSingleplayerServer() == null) return false;
        try {
            Path current = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            return current.equals(dir.toAbsolutePath().normalize());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Save-and-quit (BLOCKING: Minecraft.disconnect() pumps ticks until the integrated server is shut down). */
    private static void disconnectToTitleScreen(Minecraft mc) {
        if (!isInWorld(mc)) return;
        mc.disconnectFromWorld(Component.literal("mcb world operation"));
        if (!(mc.screen instanceof TitleScreen)) {
            mc.setScreen(new TitleScreen());
        }
    }

    // =====================================================================
    // Terminals
    // =====================================================================

    private static void succeedCreate(Active a) {
        if (a.done) { release(a); return; }
        JsonObject data = a.applyData != null ? a.applyData : new JsonObject();
        data.addProperty("elapsed_ms", System.currentTimeMillis() - a.startedAt);
        a.done = true;
        release(a); // single-flight release before performing any further work
        a.onOk.accept(data);
        progress(a, "done");
        McbMod.LOGGER.info("[MCB] world.create done: {} preset={} in {}ms",
                a.name(), a.create.preset().protocolName, System.currentTimeMillis() - a.startedAt);
    }

    private static void succeedDelete(Active a) {
        long elapsed = System.currentTimeMillis() - a.startedAt;
        JsonObject data = new JsonObject();
        data.addProperty("world_name", a.name());
        data.addProperty("deleted", true);
        data.addProperty("elapsed_ms", elapsed);
        a.done = true;
        release(a);
        a.onOk.accept(data);
        progress(a, "done");
        McbMod.LOGGER.info("[MCB] world.delete done: {} in {}ms", a.name(), elapsed);
    }

    private static void fail(Active a, String code, String message) {
        if (a.done) { release(a); return; }
        a.done = true;
        a.phase = Phase.FAILED;
        McbMod.LOGGER.warn("[MCB] world.{} failed ({}): {}",
                a.create != null ? "create" : "delete", code, message);
        progress(a, "failed");
        // §8.7.4 single-flight: release BEFORE answering, otherwise an immediate
        // follow-up request would race the still-registered Active and get a
        // spurious WORLD_IN_USE. Cleanup (only if we actually touched a world)
        // must happen before the lock is released so the next op sees a settled
        // client state. Early validation failures never tear down the current
        // world — cleanupAfterFailure() is a no-op when !worldTouched.
        cleanupAfterFailure(a);
        release(a);
        a.onError.accept(code, message);
    }

    private static void abortTimedOut(Active a) {
        if (a.done) { release(a); return; }
        // The TIMEOUT response was already sent by McbServer's watchdog (§8.7.5);
        // this path must NOT answer again.
        a.done = true;
        a.phase = Phase.FAILED;
        progress(a, "failed");
        cleanupAfterFailure(a);
        release(a);
    }

    /** §8.7.6: best-effort return to the title screen so the next create does not hit a save lock. */
    private static void cleanupAfterFailure(Active a) {
        // Only a flow that already left/created a world may tear one down: an
        // early failure (WORLD_ALREADY_EXISTS / PRESET_INVALID / FORBIDDEN) must
        // not disconnect the player from the world they were already in.
        if (!a.worldTouched) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (isInWorld(mc)) {
                disconnectToTitleScreen(mc);
                McbMod.LOGGER.info("[MCB] world.{} cleanup: returned to the title screen",
                        a.create != null ? "create" : "delete");
            }
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] cleanup after world.* failure failed (ignored): {}", t.toString());
        }
    }

    private static void release(Active a) {
        synchronized (LOCK) {
            if (active == a) active = null;
        }
    }

    // =====================================================================
    // world.progress event (§8.5, best-effort — never blocks the game thread)
    // =====================================================================

    private static void progress(Active a, String phase) {
        JsonObject data = new JsonObject();
        data.addProperty("phase", phase);
        data.addProperty("world_name", a.name());
        if (a.create != null) data.addProperty("preset", a.create.preset().protocolName);
        data.addProperty("elapsed_ms", System.currentTimeMillis() - a.startedAt);
        JsonObject event = new JsonObject();
        event.addProperty("event", "world.progress");
        event.add("data", data);
        try {
            McbServer.broadcastEvent(event);
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] world.progress push failed (ignored): {}", t.toString());
        }
    }

    // =====================================================================
    // Save-directory safety & IO helpers
    // =====================================================================

    /** §8.4 red line: only mcb_ dirs or dirs with a world.create marker; force never widens it. */
    private static boolean isDeletable(Path dir, String name) {
        if (name.startsWith(SAFE_PREFIX)) return true;
        return Files.isRegularFile(dir.resolve(MARKER_FILE));
    }

    private static void deleteDirectoryRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Marker written after a successful create: enables world.delete for non-mcb_ names (§8.4). */
    private static void writeMarkerAsync(Active a) {
        final Path dir;
        try {
            dir = Minecraft.getInstance().getLevelSource().getLevelPath(a.name());
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] world.create: cannot resolve save path for marker: {}", t.toString());
            a.markerDone = true;
            return;
        }
        JsonObject marker = new JsonObject();
        marker.addProperty("preset", a.create.preset().protocolName);
        marker.addProperty("created_at", Instant.now().toString());
        marker.addProperty("created_by", "mcb/" + MOD_VERSION);
        final String json = GSON.toJson(marker);
        IO.submit(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(MARKER_FILE), json, StandardCharsets.UTF_8);
            } catch (Throwable t) {
                McbMod.LOGGER.warn("[MCB] failed to write {} marker: {}", MARKER_FILE, t.toString());
            } finally {
                a.markerDone = true;
            }
        });
    }
}
