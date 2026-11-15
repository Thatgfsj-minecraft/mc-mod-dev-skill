package com.thatgfsjminecraft.mcb;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TCP + NDJSON transport layer (protocol.md §1–§2).
 *
 * Threading model (protocol §6 — the part implementations get wrong):
 * - accept loop + per-connection read loop run on their own threads;
 * - every op that touches the game hops to the client main thread via
 *   Minecraft.execute() and the response is written back when the hop completes;
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
        // line is pushed to all live connections.
        McbLogCapture.subscribe(entry -> {
            JsonObject event = new JsonObject();
            event.addProperty("event", "log");
            event.add("data", entry.toJson());
            String line = GSON.toJson(event) + "\n";
            for (Connection c : connections) {
                c.send(line);
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
                break; // server socket closed (game exiting)
            }
        }
    }

    // =====================================================================
    // Per-connection logic
    // =====================================================================
    private static final class Connection {
        private final Socket socket;
        private final Writer out;
        private volatile boolean closed;
        /** Protocol §2.2: exactly one response per request. First responder wins. */
        private final java.util.Set<Long> responded = ConcurrentHashMap.newKeySet();

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        void run() {
            try {
                sendHello();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 1 << 20);
                String line;
                while (!closed && (line = in.readLine()) != null) {
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
                closed = true;
                connections.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private void sendHello() {
            JsonObject hello = new JsonObject();
            hello.addProperty("protocol", 1);
            hello.addProperty("game", Minecraft.getInstance().getLaunchedVersion());
            hello.addProperty("loader", "fabric");
            hello.addProperty("side", "client");
            hello.addProperty("worldLoaded", Minecraft.getInstance().level != null);
            JsonObject event = new JsonObject();
            event.addProperty("event", "hello");
            event.add("data", hello);
            send(GSON.toJson(event) + "\n");
        }

        /** Send one already-terminated line. Synchronized: responses + events interleave. */
        synchronized void send(String line) {
            if (closed) return;
            try {
                out.write(line);
                out.flush();
            } catch (IOException e) {
                closed = true;
            }
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
            long id = req.has("id") && req.get("id").isJsonPrimitive() ? req.get("id").getAsLong() : 0;
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
                case "status" -> {
                    // Reads game state -> must run on the main thread.
                    Minecraft.getInstance().execute(() -> sendOk(id, McbHandlers.buildStatus()));
                }
                case "player.info" -> {
                    Minecraft.getInstance().execute(() -> sendOk(id, McbHandlers.playerInfo()));
                }
                case "block.get" -> {
                    int[] pos = McbHandlers.parseVec3(params.get("x") != null
                            ? params.get("x") : params.get("pos"), "x/pos");
                    // block.get allows either "x": [x,y,z] or flat x/y/z params.
                    Minecraft.getInstance().execute(() -> {
                        try {
                            sendOk(id, McbHandlers.blockGet(pos[0], pos[1], pos[2], McbHandlers.requireLevel()));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        }
                    });
                }
                case "block.scan" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    java.util.List<String> filter = McbHandlers.parseStringList(params.get("filter"), "filter");
                    boolean includeProps = params.has("includeProperties") && params.get("includeProperties").isJsonPrimitive()
                            && params.get("includeProperties").getAsBoolean();
                    Minecraft.getInstance().execute(() -> {
                        try {
                            sendOk(id, McbHandlers.blockScan(min, max, filter, includeProps, McbHandlers.requireLevel()));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (IllegalArgumentException e) {
                            sendError(id, "TOO_LARGE", e.getMessage());
                        }
                    });
                }
                case "map.ascii" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    String mode = params.has("mode") && params.get("mode").isJsonPrimitive()
                            ? params.get("mode").getAsString() : "top";
                    Integer sliceY = params.has("y") && params.get("y").isJsonPrimitive()
                            ? params.get("y").getAsInt() : null;
                    Minecraft.getInstance().execute(() -> {
                        try {
                            sendOk(id, McbHandlers.mapAscii(min, max, mode, sliceY, McbHandlers.requireLevel()));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (IllegalArgumentException e) {
                            sendError(id, "TOO_LARGE", e.getMessage());
                        }
                    });
                }
                case "entity.list" -> {
                    int[] min = McbHandlers.parseVec3(params.get("min"), "min");
                    int[] max = McbHandlers.parseVec3(params.get("max"), "max");
                    java.util.List<String> types = McbHandlers.parseStringList(params.get("type"), "type");
                    Minecraft.getInstance().execute(() -> {
                        try {
                            sendOk(id, McbHandlers.entityList(min, max, types, McbHandlers.requireLevel()));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        }
                    });
                }
                case "command.run" -> {
                    String command = params.has("command") && params.get("command").isJsonPrimitive()
                            ? params.get("command").getAsString() : null;
                    if (command == null || command.isBlank()) {
                        sendError(id, "BAD_PARAMS", "command must be a non-empty string");
                        return;
                    }
                    int timeoutMs = intParam(params, "timeoutMs", 10000, 60000);
                    var server = Minecraft.getInstance().getSingleplayerServer();
                    if (server == null) {
                        // Protocol §3.9 fallback 3: no integrated server.
                        sendError(id, "WORLD_NOT_LOADED",
                                "no integrated server; multiplayer command passthrough is not implemented in this bridge");
                        return;
                    }
                    long deadline = System.currentTimeMillis() + timeoutMs;
                    // Commands run on the SERVER thread, not the client thread.
                    server.execute(() -> {
                        try {
                            sendOk(id, McbHandlers.commandRun(server, command));
                        } catch (OpException e) {
                            sendError(id, e.code, e.getMessage());
                        } catch (Exception e) {
                            sendError(id, "INTERNAL", e.getClass().getName() + ": " + e.getMessage());
                        }
                    });
                    // Keep the deadline check honest without holding any thread:
                    // a watchdog just reports TIMEOUT if the response never came.
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
                    String levelMin = params.has("levelMin") && params.get("levelMin").isJsonPrimitive()
                            ? params.get("levelMin").getAsString() : "INFO";
                    var snapshot = McbLogCapture.readAfter(afterId, limit);
                    JsonArray entries = new JsonArray();
                    org.apache.logging.log4j.Level min = org.apache.logging.log4j.Level.getLevel(levelMin);
                    for (var e : snapshot.entries) {
                        if (McbLogCapture.levelAtLeast(e.level, min)) {
                            entries.add(e.toJson());
                        }
                    }
                    JsonObject data = new JsonObject();
                    data.add("entries", entries);
                    data.addProperty("nextId", snapshot.lastId);
                    data.addProperty("overflowed", snapshot.overflowed);
                    sendOk(id, data);
                }
                case "log.wait" -> {
                    // Long poll (protocol §3.8): never block the main thread.
                    String pattern = params.has("pattern") && params.get("pattern").isJsonPrimitive()
                            ? params.get("pattern").getAsString() : null;
                    if (pattern == null) {
                        sendError(id, "BAD_PARAMS", "pattern is required");
                        return;
                    }
                    int timeoutMs = intParam(params, "timeoutMs", 10000, 60000);
                    long afterId = longParam(params, "afterId", 0);
                    String levelMin = params.has("levelMin") && params.get("levelMin").isJsonPrimitive()
                            ? params.get("levelMin").getAsString() : "INFO";
                    LOG_WAIT_POOL.submit(() -> {
                        long start = System.currentTimeMillis();
                        while (System.currentTimeMillis() - start < timeoutMs && !closed) {
                            var found = McbLogCapture.findMatch(pattern,
                                    org.apache.logging.log4j.Level.getLevel(levelMin), afterId);
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
                }
                default -> sendError(id, "BAD_OP", "unknown op: " + op);
            }
        }

        private void sendOk(long id, JsonObject data) {
            if (!responded.add(id)) return;
            JsonObject resp = new JsonObject();
            resp.addProperty("id", id);
            resp.addProperty("ok", true);
            resp.add("data", data);
            send(GSON.toJson(resp) + "\n");
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
            send(GSON.toJson(resp) + "\n");
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
    }
}
