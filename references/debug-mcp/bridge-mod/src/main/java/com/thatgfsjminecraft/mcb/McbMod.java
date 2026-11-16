package com.thatgfsjminecraft.mcb;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCB (Minecraft Console Bridge) v1 — entrypoint.
 *
 * Protocol:  references/debug-mcp/protocol.md
 * Lifecycle: game start -> attach log capture -> open TCP server (127.0.0.1:25585)
 *            game exit  -> JVM dies, sockets close (no graceful shutdown needed).
 *
 * Thread discipline (protocol.md §6): the TCP server owns its own IO threads;
 * everything touching the game world is hopped to the client main thread inside
 * McbServer/McbHandlers. This class does no game-state access at all.
 */
public class McbMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mcb");

    /** Default listen port. Override with -Dmcb.port=NNNN or env MCB_PORT. */
    public static final int DEFAULT_PORT = 25585;

    @Override
    public void onInitializeClient() {
        // Protocol §3.7: log capture starts at process start, NOT at first
        // client connection, so that startup errors are still retrievable.
        McbLogCapture.attach();

        int port = resolvePort();
        try {
            McbServer.start(port);
            LOGGER.info("[MCB] debug bridge listening on 127.0.0.1:{} (MCB v1)", port);
        } catch (Exception e) {
            // Port conflict must not crash the game — this is a dev tool.
            LOGGER.error("[MCB] failed to open debug bridge on port {}: {}", port, e.toString());
        }

        // §1.1 focus keep-alive: stop vanilla from pausing when the window loses
        // focus, so an automated run (terminal/MCP client holding OS focus) keeps
        // rendering and stays screenshot-able.
        //
        // Deferred on purpose: fabric-loader runs this entrypoint from inside
        // Minecraft.<init> BEFORE `this.options = new Options(...)` is assigned
        // (fabric-loader 0.19.5 EntrypointPatch hooks just before
        // Thread.currentThread() for >=1.19.4), so options may still be null here.
        // McbClientOps re-applies it on the first client tick when needed.
        try {
            McbClientOps.scheduleFocusKeepAlive();
        } catch (Throwable t) {
            // A dev tool must never take the client down during init.
            LOGGER.warn("[MCB] focus keep-alive setup failed (ignored): {}", t.toString());
        }
    }

    private static int resolvePort() {
        String prop = System.getProperty("mcb.port");
        if (prop != null && prop.matches("\\d+")) {
            return Integer.parseInt(prop);
        }
        String env = System.getenv("MCB_PORT");
        if (env != null && env.matches("\\d+")) {
            return Integer.parseInt(env);
        }
        return DEFAULT_PORT;
    }
}
