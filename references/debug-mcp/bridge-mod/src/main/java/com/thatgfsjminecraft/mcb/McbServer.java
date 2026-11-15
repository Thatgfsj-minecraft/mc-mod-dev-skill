package com.thatgfsjminecraft.mcb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The TCP + NDJSON transport layer (protocol.md §1–§2).
 *
 * Threading model (protocol §6 — the part implementations get wrong):
 * - accept loop + per-connection read loop run on their own threads;
 * - a per-connection WRITER thread drains an outbound queue, so neither the
 *   game main thread nor the log4j appender thread ever blocks on socket I/O
 *   (§2.3 backpressure red line);
 * - every op that touches the game hops to the client main thread via
 *   Minecraft.execute() and the response is enqueued when the hop completes;
 * - log.wait (§3.8) is a long poll: it does NOT hold the main thread — it waits
 *   on McbLogCapture from a dedicated executor and completes independently;
 * - responses may arrive out of order; clients match by id (protocol §2.1).
 */
final class McbServer {
    private McbServer() {}

    static final class OpException extends RuntimeException {
        final String code;
        OpException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final Gson GSON = new Gson();
    /** §1: a request line above this cap is a protocol violation — hang up. */
    private static final int MAX_LINE_CHARS = 1 << 20;
    /** §3.4 volume limit, enforced on the TCP thread before any main-thread hop. */
    private static final int VOLUME_LIMIT = 4096;

    private static final ExecutorService LOG_WAIT_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcb-logwait");
        t.setDaemon(true);
        return t;
    });

    /** Active connections, used to broadcast `log` events. */
    private static final java.util.Set<Connection> connections =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final long START_MS = System.currentTimeMillis();

    static void start(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        // Protocol §3: loopback only. Binding 0.0.0.0 would expose operator
        // commands to every local process — never do that.
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port), 4);

        Thread acceptThread = new Thread(() -> acceptLoop(serverSocket), "mcb-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // Subscribe for event broadcast (protocol §2.3): every captured log
        // line is pushed to all live connections (best-effort, see sendEvent).
        McbLogCapture.subscribe(entry -> {
            JsonObject event = new JsonObject();
            event.addProperty("event", "log");
            event.add("data", entry.toJson());
            String line = GSON.toJson(event) + "\n";
            for (Connection c : connections) {
                c.sendEvent(line);
            }
        });
    }

    private static void acceptLoop(ServerSocket serverSocket) {
        while (true) {
            try {
                Socket socket = serverSocket.accept();
                Connection conn = new Connection(socket);
                connections.add(conn);
                Thread t = new Thread(conn::run, "mcb-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (serverSocket.isClosed()) break; // game exiting
                // Transient accept failure: log and keep serving, a single
                // bad accept must not take the whole bridge down.
                McbMod.LOGGER.warn("[MCB] accept failed, retrying", e);
                try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
            }
        }
    }

    // =====================================================================
    // Per-connection logic
    // =====================================================================
    private static final class Connection {
        /** §2.3: when a slow client lets this fill up, events are dropped. */
        private static final int OUTBOUND_LIMIT = 1024;

        private final Socket socket;
        private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(OUTBOUND_LIMIT);
        private volatile boolean closed;
        /** Protocol §2.2: exactly one response per request. First responder wins. */
        private final java.util.Set<Long> responded = ConcurrentHashMap.newKeySet();

        Connection(Socket socket) throws IOException {
            this.socket = socket;
        }

        void run() {
            Thread writer = new Thread(this::writerLoop, "mcb-writer");
            writer.setDaemon(true);
            writer.start();
            try {
                sendHello();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    if (line.length() > MAX_LINE_CHARS) {
                        // §1: framing violation — reply then hang up.
                        sendError(0, "BAD_PARAMS", "request line exceeds the 1 MiB single-line cap; closing connection");
                        break;
                    }
                    if (line.isBlank()) continue;
                    // One request per line; a bad line must not kill the connection.
                    try {
                        handleLine(line.trim());
                    } catch (Exception e) {
                        McbMod.LOGGER.error("[MCB] failed to handle line: {}", line, e);
                    }
                }
            } catch (IOException e) {
                // client went away — normal
            } finally {
                closeQuietly();
            }
        }

        /**
         * Dedicated writer thread: all socket writes happen here, nothing
         * else ever touches the output stream. Blocks on the queue, exits
         * within a second of the connection closing.
         */
        private void writerLoop() {
            try {
                var w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), 1 << 16);
                while (!closed) {
                    String line = outbound.poll(1, TimeUnit.SECONDS);
                    if (line == null) continue;
                    w.write(line);
                    w.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // reader side will notice too; close is idempotent
            } finally {
                closeQuietly();
            }
        }

        private void sendHello() {
            JsonObject hello = new JsonObject();
            hello.addProperty("protocol", 1);
            hello.addProperty("game", Minecraft.getInstance().getLaunchedVersion());
            hello.addProperty("loader", "fabric");
            hello.addProperty("side", "client");
            // Benign cross-thread read (worldLoaded is a snapshot flag, not a
            // world walk); the first `status` answer is the authoritative view.
            hello.addProperty("worldLoaded", Minecraft.getInstance().level != null);
            JsonObject event = new JsonObject();
            event.addProperty("event", "hello");
            event.add("data", hello);
            sendResponse(GSON.toJson(event) + "\n");
        }

        /**
         * Response path: MUST be delivered (§2.2 — every request gets exactly
         * one response). A client too slow to drain responses loses the
         * connection rather than silently missing replies.
         */
        void sendResponse(String line) {
            if (closed) return;
            if (!outbound.offer(line)) {
                McbMod.LOGGER.warn("[MCB] outbound queue full; closing slow client");
                closeQuietly();
            }
        }

        /**
         * Event path (§2.3): best-effort. Drop the event instead of blocking
         * the logger thread — a dropped push is counted in status.droppedEvents
         * and the reliable log channel is log.history / log.wait anyway.
         */
        void sendEvent(String line) {
            if (closed) return;
            if (!outbound.offer(line)) McbLogCapture.onEventDropped();
        }

        void closeQuietly() {
            closed = true;
            connections.remove(this);
            try { socket.close(); } catch (IOException ignored) {}
        }

        private void handleLine(String line) {
            JsonObject req;
            try {
                req = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                // No id recoverable — reply with id 0 so the client sees it.
                sendError(0, "BAD_PARAMS", "line is not a JSON object: " + e.getMessage());
                return;
            }
            long id;
            if (req.has("id") && req.get("id").isJsonPrimitive() && req.get("id").getAsJsonPrimitive().isNumber()) {
                id = req.get("id").getAsLong();
            } else {
                sendError(0, "BAD_PARAMS", "request must carry a numeric id");
                return;
            }
            String op = req.has("op") && req.get("op").isJsonPrimitive()
                    ? req.get("op").getAsString() : null;
            JsonObject params = req.has("params") && req.get("params").isJsonObject()
                    ? req.getAsJsonObject("params") : new JsonObject();
            if (op == null) {
                sendError(id, "BAD_PARAMS", "missing op");
                return;
            }

            try {
                dispatch(id, op, params);
            } catch (OpException e) {
                sendError(id, e.code, e.getMessage());
            } catch (Exception e) {
                // Protocol §5 INTERNAL: include the Java class name for triage.
                sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
            }
        }

        private void dispatch(long id, String op, JsonObject params) {
            switch (op) {
                case "ping" -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("pong", true);
                    data.addProperty("uptimeMs", System.currentTimeMillis() - START_MS);
                    sendOk(id, data);
                }
                case "status" -> hop(id, McbHandlers::buildStatus);
                case "player.info" -> hop(id, McbHandlers::playerInfo);
                case "block.get" -> {
                    // §3.3: flat x/y/z integers are the canonical form. An
                    // array under "x"/"pos" is tolerated for robustness.
                    int[] pos;
                    if (params.has("x") && params.get("x").isJsonArray()) {
                        pos = McbHandlers.parseVec3(params.get("x"), "x");
                    } else if (params.has("pos") && params.get("pos").isJsonArray()) {
                        pos = McbHandlers.parseVec3(params.get("pos"), "pos");
                    } else if (params.has("x") && params.has("y") && params.has("z")) {
                        pos = new int[] { intOf(params.get("x"), "x"), intOf(params.get("y"), "y"), intOf(params.get("z"), "z") };
                    } else {
                        sendError(id, "BAD_PARAMS", "block.get needs flat x/y/z integers");
                        return;
                    }
                    hop(id, () -> McbHandlers.blockGet(pos[0], pos[1], pos[2], McbHandlers.requireLevel()));
                }
                case "block.scan" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    normalize(min, max); // §3.4: bridge must normalize per axis
                    requireVolume(min, max);
                    java.util.List<String> filter = McbHandlers.parseStringList(params.get("filter"), "filter");
                    boolean includeProps = params.has("includeProperties") && params.get("includeProperties").isJsonPrimitive()
                            && params.get("includeProperties").getAsBoolean();
                    hop(id, () -> McbHandlers.blockScan(min, max, filter, includeProps, McbHandlers.requireLevel()));
                }
                case "map.ascii" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    normalize(min, max);
                    requireVolume(min, max);
                    String mode = params.has("mode") && params.get("mode").isJsonPrimitive()
                            ? params.get("mode").getAsString() : "top";
                    if (!mode.equals("top") && !mode.equals("slice")) {
                        sendError(id, "BAD_PARAMS", "mode must be \"top\" or \"slice\"");
                        return;
                    }
                    Integer sliceY = params.has("y") && params.get("y").isJsonPrimitive()
                            ? params.get("y").getAsInt() : null;
                    if (mode.equals("slice") && sliceY == null) {
                        sendError(id, "BAD_PARAMS", "mode=slice requires y");
                        return;
                    }
                    hop(id, () -> McbHandlers.mapAscii(min, max, mode, sliceY, McbHandlers.requireLevel()));
                }
                case "entity.list" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    normalize(min, max);
                    requireVolume(min, max);
                    java.util.List<String> types = McbHandlers.parseStringList(params.get("type"), "type");
                    hop(id, () -> McbHandlers.entityList(min, max, types, McbHandlers.requireLevel()));
                }
                case "command.run" -> {
                    String command = params.has("command") && params.get("command").isJsonPrimitive()
                            ? params.get("command").getAsString() : null;
                    if (command == null || command.isBlank()) {
                        sendError(id, "BAD_PARAMS", "command must be a non-empty string");
                        return;
                    }
                    if (command.startsWith("/")) {
                        command = command.substring(1); // protocol: no leading slash; be tolerant
                    }
                    final String commandFinal = command; // lambdas below capture it
                    int timeoutMs = intParam(params, "timeoutMs", 10000, 60000);
                    var server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        // §3.9 fallback 2: client in a multiplayer world →
                        // send as a command message. Feedback is not capturable,
                        // resultText is empty per protocol.
                        var conn = Minecraft.getInstance().getConnection();
                        if (conn != null) {
                            // sendCommand takes the command WITHOUT a leading
                            // slash (verified 1.21.11 javap); hop to the client
                            // main thread since it enqueues a signed packet.
                            Minecraft.getInstance().execute(() -> conn.sendCommand(commandFinal));
                            JsonObject data = new JsonObject();
                            data.addProperty("success", true);
                            data.addProperty("resultText", "");
                            data.add("feedback", new JsonArray());
                            sendOk(id, data);
                        } else {
                            // §3.9 fallback 3: no world at all.
                            sendError(id, "WORLD_NOT_LOADED", "no integrated server and not connected to any server world");
                        }
                        return;
                    }
                    // Commands run on the SERVER thread, not the client thread.
                    server.execute(() -> {
                        try {
                            sendOk(id, McbHandlers.commandRun(server, commandFinal));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (Exception e) {
                            sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
                        }
                    });
                    // Watchdog: if the response never came (a hung command),
                    // report TIMEOUT — responded-set guarantees only one reply.
                    LOG_WAIT_POOL.submit(() -> {
                        try {
                            Thread.sleep(timeoutMs);
                            if (!closed) sendError(id, "TIMEOUT", "command.run exceeded " + timeoutMs + "ms");
                        } catch (InterruptedException ignored) {}
                    });
                }
                case "log.history" -> {
                    long afterId = longParam(params, "afterId", 0);
                    int limit = intParam(params, "limit", 500, 1000);
                    var min = parseLevel(params);
                    // §3.7 semantics live in McbLogCapture.readAfter — see its
                    // javadoc before touching the cursor math.
                    var snapshot = McbLogCapture.readAfter(afterId, min, limit);
                    JsonArray entries = new JsonArray();
                    for (var e : snapshot.entries) entries.add(e.toJson());
                    JsonObject data = new JsonObject();
                    data.add("entries", entries);
                    data.addProperty("nextId", snapshot.nextId);
                    data.addProperty("overflowed", snapshot.overflowed);
                    sendOk(id, data);
                }
                case "log.wait" -> {
                    // Long poll (protocol §3.8): never block the main thread.
                    String patternStr = params.has("pattern") && params.get("pattern").isJsonPrimitive()
                            ? params.get("pattern").getAsString() : null;
                    if (patternStr == null || patternStr.isEmpty()) {
                        sendError(id, "BAD_PARAMS", "pattern is required");
                        return;
                    }
                    Pattern pattern;
                    try {
                        // Compile ONCE on the TCP thread: recompiling per poll
                        // tick would also compile attacker-controlled regexes
                        // repeatedly and BAD_PARAMS must be immediate, not after
                        // the first poll.
                        pattern = Pattern.compile(patternStr);
                    } catch (PatternSyntaxException e) {
                        sendError(id, "BAD_PARAMS", "invalid regex: " + e.getMessage());
                        return;
                    }
                    int timeoutMs = intParam(params, "timeoutMs", 10000, 60000);
                    long afterId = longParam(params, "afterId", 0);
                    var min = parseLevel(params);
                    LOG_WAIT_POOL.submit(() -> {
                        long start = System.currentTimeMillis();
                        while (System.currentTimeMillis() - start < timeoutMs && !closed) {
                            var found = McbLogCapture.findMatch(pattern, min, afterId);
                            if (found != null) {
                                JsonObject data = new JsonObject();
                                data.addProperty("matched", true);
                                data.add("entry", found.toJson());
                                sendOk(id, data);
                                return;
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException ignored) {
                                return;
                            }
                        }
                        JsonObject data = new JsonObject();
                        data.addProperty("matched", false);
                        data.addProperty("nextId", McbLogCapture.highestId());
                        sendOk(id, data);
                    });
                }
                case "screenshot.take" -> {
                    // GL context access -> render thread (see McbScreenshot).
                    McbScreenshot.take(image -> sendOk(id, image), err -> sendError(id, "INTERNAL", err));
                    // Watchdog: if the render callback never fires (window
                    // minimized mid-teardown etc.), report TIMEOUT instead of
                    // leaving the client waiting forever.
                    LOG_WAIT_POOL.submit(() -> {
                        try {
                            Thread.sleep(10000);
                            if (!closed) sendError(id, "TIMEOUT", "screenshot.take exceeded 10000ms");
                        } catch (InterruptedException ignored) {}
                    });
                }
                case "camera.eye" -> {
                    // §3.12 one-shot sky-eye: set pose -> next frame renders with
                    // the virtual camera -> capture -> restore (see McbCamera).
                    double[] pos = vec3Double(params);
                    float camYaw = floatOf(params.get("yaw"), "yaw");
                    float camPitch = floatOf(params.get("pitch"), "pitch");
                    Float camFov = params.has("fov") && params.get("fov").isJsonPrimitive()
                            ? floatOf(params.get("fov"), "fov") : null;
                    Minecraft.getInstance().execute(() -> {
                        try {
                            McbHandlers.requireLevel(); // fail fast before arming the capture
                            McbCamera.startOneShot(pos[0], pos[1], pos[2], camYaw, camPitch, camFov,
                                    img -> sendOk(id, img), msg -> sendError(id, "INTERNAL", msg));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (Exception e) {
                            sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
                        }
                    });
                    // Same watchdog rationale as screenshot.take: the capture
                    // fires between frames; if rendering stops we must not
                    // leave the request unanswered.
                    LOG_WAIT_POOL.submit(() -> {
                        try {
                            Thread.sleep(10000);
                            if (!closed) sendError(id, "TIMEOUT", "camera.eye exceeded 10000ms");
                        } catch (InterruptedException ignored) {}
                    });
                }
                case "camera.set" -> hop(id, () -> {
                    McbHandlers.requireLevel();
                    double[] pos = vec3Double(params);
                    float camYaw = floatOf(params.get("yaw"), "yaw");
                    float camPitch = floatOf(params.get("pitch"), "pitch");
                    Float camFov = params.has("fov") && params.get("fov").isJsonPrimitive()
                            ? floatOf(params.get("fov"), "fov") : null;
                    McbCamera.setPersistent(pos[0], pos[1], pos[2], camYaw, camPitch, camFov);
                    return McbCamera.describePersistent();
                });
                case "camera.clear" -> {
                    // Volatile-flag writes only — safe without a main-thread hop.
                    // An un-captured one-shot is cancelled with an error response
                    // so protocol §2.2 (exactly one response per request) holds.
                    McbCamera.clear(msg -> sendError(id, "INTERNAL", msg));
                    JsonObject data = new JsonObject();
                    data.addProperty("active", false);
                    data.addProperty("mode", "none");
                    sendOk(id, data);
                }
                case "player.move" -> {
                    // §3.13 teleport with hardcoded ground safety: probe the
                    // ground under the target, place glass into the void if
                    // needed (server authority), THEN move the player. Params
                    // parsed here for immediate BAD_PARAMS. The glass path is
                    // async (server thread -> client thread), so the response
                    // is sent from the callbacks; sendOk's responded-set keeps
                    // the "exactly one response" guarantee.
                    double mx = doubleOf(params.get("x"), "x");
                    double my = doubleOf(params.get("y"), "y");
                    double mz = doubleOf(params.get("z"), "z");
                    boolean hasYaw = params.has("yaw") && params.get("yaw").isJsonPrimitive();
                    boolean hasPitch = params.has("pitch") && params.get("pitch").isJsonPrimitive();
                    float myaw = hasYaw ? floatOf(params.get("yaw"), "yaw") : 0f;
                    float mpitch = hasPitch ? floatOf(params.get("pitch"), "pitch") : 0f;
                    Minecraft.getInstance().execute(() -> {
                        try {
                            McbHandlers.playerMove(mx, my, mz, myaw, mpitch, hasYaw, hasPitch,
                                    data -> sendOk(id, data),
                                    (code, msg) -> sendError(id, code, msg));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (Exception e) {
                            sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
                        }
                    });
                }
                default -> sendError(id, "BAD_OP", "unknown op: " + op);
            }
        }

        /**
         * Hop to the client main thread. Every world-touching op goes through
         * here (protocol §6.1). Any exception on the main thread becomes a
         * protocol error response — a request must never go unanswered (§2.2).
         */
        private void hop(long id, java.util.function.Supplier<JsonObject> body) {
            Minecraft.getInstance().execute(() -> {
                try {
                    sendOk(id, body.get());
                } catch (OpException e) {
                    sendError(id, e.code, e.getMessage());
                } catch (Exception e) {
                    sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
                }
            });
        }

        private void sendOk(long id, JsonObject data) {
            if (!responded.add(id)) return;
            JsonObject resp = new JsonObject();
            resp.addProperty("id", id);
            resp.addProperty("ok", true);
            resp.add("data", data);
            sendResponse(GSON.toJson(resp) + "\n");
        }

        private void sendError(long id, String code, String message) {
            if (!responded.add(id)) return;
            JsonObject err = new JsonObject();
            err.addProperty("code", code);
            err.addProperty("message", message == null ? "" : message);
            JsonObject resp = new JsonObject();
            resp.addProperty("id", id);
            resp.addProperty("ok", false);
            resp.add("error", err);
            sendResponse(GSON.toJson(resp) + "\n");
        }

        // ------------------------------------------------------------- param helpers

        /**
         * levelMin validation happens here (§3.7/§3.8): an unknown level name
         * must fail fast with BAD_PARAMS, not silently pass every entry later.
         * Throws OpException — handleLine's catch answers the request.
         */
        private static org.apache.logging.log4j.Level parseLevel(JsonObject params) {
            String levelMin = params.has("levelMin") && params.get("levelMin").isJsonPrimitive()
                    ? params.get("levelMin").getAsString() : "INFO";
            var min = org.apache.logging.log4j.Level.getLevel(levelMin); // null when unknown
            if (min == null) {
                throw new OpException("BAD_PARAMS", "levelMin must be DEBUG/INFO/WARN/ERROR");
            }
            return min;
        }

        /** Per-axis normalization (§3.4): endpoints may come in any order. */
        private static void normalize(int[] min, int[] max) {
            for (int i = 0; i < 3; i++) {
                int lo = Math.min(min[i], max[i]);
                int hi = Math.max(min[i], max[i]);
                min[i] = lo;
                max[i] = hi;
            }
        }

        /** §3.4/§3.5/§3.6 volume cap, checked BEFORE the main-thread hop. */
        private static void requireVolume(int[] min, int[] max) {
            long volume = (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
            if (volume > VOLUME_LIMIT) {
                throw new OpException("TOO_LARGE", "region volume " + volume + " exceeds limit " + VOLUME_LIMIT);
            }
        }

        private static int intOf(JsonElement el, String name) {
            if (el == null || !el.isJsonPrimitive()) {
                throw new OpException("BAD_PARAMS", name + " must be an integer");
            }
            var p = el.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsInt();
            if (p.isString()) {
                try {
                    return (int) Math.floor(Double.parseDouble(p.getAsString()));
                } catch (NumberFormatException e) {
                    throw new OpException("BAD_PARAMS", name + " is not numeric");
                }
            }
            throw new OpException("BAD_PARAMS", name + " must be an integer");
        }

        private static int intParam(JsonObject params, String name, int dflt, int max) {
            if (!params.has(name) || !params.get(name).isJsonPrimitive()) return dflt;
            int v = params.get(name).getAsInt();
            return Math.min(Math.max(1, v), max);
        }

        private static long longParam(JsonObject params, String name, long dflt) {
            if (!params.has(name) || !params.get(name).isJsonPrimitive()) return dflt;
            return params.get(name).getAsLong();
        }

        /**
         * §3.12 camera params: x/y/z are camera EYE positions — doubles, not
         * block coordinates (McbHandlers.parseVec3 is the integer variant for
         * block ops). Accepts numbers or numeric strings like other params.
         */
        private static double[] vec3Double(JsonObject params) {
            double x = doubleOf(params.get("x"), "x");
            double y = doubleOf(params.get("y"), "y");
            double z = doubleOf(params.get("z"), "z");
            return new double[] { x, y, z };
        }

        private static double doubleOf(JsonElement el, String name) {
            if (el == null || !el.isJsonPrimitive()) {
                throw new OpException("BAD_PARAMS", name + " must be a number");
            }
            var p = el.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isString()) {
                try {
                    return Double.parseDouble(p.getAsString());
                } catch (NumberFormatException e) {
                    throw new OpException("BAD_PARAMS", name + " is not numeric");
                }
            }
            throw new OpException("BAD_PARAMS", name + " must be a number");
        }

        private static float floatOf(JsonElement el, String name) {
            return (float) doubleOf(el, name);
        }
    }
}
