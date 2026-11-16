package com.thatgfsjminecraft.mcb;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCB (Minecraft Console Bridge) v1 — entrypoint.
 *
 * <p>Startup is now gated by developer mode (as of 0.4.0):
 * <ul>
 *   <li>The log capture always attaches early (protocol §3.7), because it is
 *       passive and useful even when the bridge is disabled.</li>
 *   <li>The debug bridge (TCP server + watch engine + focus keep-alive) is
 *       started by the idempotent {@link #activateDevMode()}. It is called
 *       immediately for a silent "enable" decision (JVM property / remembered
 *       config) and later exactly once when the player answers "yes" on the
 *       developer-mode confirm screen.</li>
 *   <li>Answering "no", or {@code -Dmcb.devMode=false}, leaves the game in
 *       vanilla behaviour: no listening port, no pauseOnLostFocus change.</li>
 * </ul>
 *
 * <p>Automation must pass {@code -Dmcb.devMode=true} (see bridge-mod README /
 * e2e-testing.md) so unattended runClient never waits on the prompt.
 */
public class McbMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("mcb");

    /** Default listen port. Override with -Dmcb.port=NNNN or env MCB_PORT. */
    public static final int DEFAULT_PORT = 25585;

    /** Mod id declared in fabric.mod.json. */
    public static final String MOD_ID = McbDevModeConfig.MOD_ID;

    private static final AtomicBoolean ACTIVATED = new AtomicBoolean();
    private static volatile McbDevModeConfig.Mode devModeMode = McbDevModeConfig.Mode.ASK_ON_TITLE;

    @Override
    public void onInitializeClient() {
        // Protocol §3.7: log capture starts at process start, NOT at first
        // client connection, so startup errors are retrievable even when the
        // developer decides not to enable the bridge.
        McbLogCapture.attach();

        devModeMode = McbDevModeConfig.mode();
        LOGGER.info("[MCB] developer mode: {} (source={} [{}])",
                devModeMode, McbDevModeConfig.sourceName(), McbDevModeConfig.sourceId());

        if (devModeMode == McbDevModeConfig.Mode.ENABLE_SILENT) {
            // JVM property or remembered config already answered; no prompt.
            activateDevMode();
        } else {
            // ASK_ON_TITLE: wait for the politely queued prompt; DISABLE_SILENT:
            // never start anything. In both cases no port is opened here.
            LOGGER.info("[MCB] debug bridge inactive (mode={}); no listening port opened", devModeMode);
        }
    }

    /** Resolved developer-mode decision for this JVM (set during client init). */
    public static McbDevModeConfig.Mode devModeMode() {
        return devModeMode;
    }

    /** True once the bridge has been activated (idempotent; safe to poll). */
    public static boolean isDevModeActive() {
        return ACTIVATED.get();
    }

    /**
     * Start the debug bridge (TCP server + watch engine + focus keep-alive).
     *
     * <p>Idempotent: repeated calls are no-ops. Safe to call from the client
     * thread (init or the confirm-screen callback) and from automation code.
     * Any failure is logged but never thrown — a debugging dev tool must not
     * take the game down.
     */
    public static void activateDevMode() {
        if (!ACTIVATED.compareAndSet(false, true)) {
            return;
        }
        int port = resolvePort();
        try {
            McbServer.start(port);
            LOGGER.info("[MCB] debug bridge listening on 127.0.0.1:{} (MCB v1)", port);
        } catch (Exception e) {
            // Port conflict must not crash the game.
            LOGGER.error("[MCB] failed to open debug bridge on port {}: {}", port, e.toString());
        }

        try {
            // Watch engine pumps the integrated-server thread; harmless without
            // a world and required only after the bridge is enabled.
            WatchEngine.start();
        } catch (Throwable t) {
            LOGGER.warn("[MCB] watch engine failed to start (ignored): {}", t.toString());
        }

        // §1.1 focus keep-alive: only touch pauseOnLostFocus after the player
        // opted in (or automation explicitly enabled dev mode). Deferred on
        // purpose: the client entrypoint runs before Minecraft.options exists;
        // McbClientOps retries on the first client tick.
        try {
            McbClientOps.scheduleFocusKeepAlive();
        } catch (Throwable t) {
            LOGGER.warn("[MCB] focus keep-alive setup failed (ignored): {}", t.toString());
        }

        LOGGER.info("[MCB] developer mode activated");
    }

    /** Listen port from -Dmcb.port > MCB_PORT > {@link #DEFAULT_PORT}. */
    public static int resolvePort() {
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
