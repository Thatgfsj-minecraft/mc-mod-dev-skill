package com.thatgfsjminecraft.mcb;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Developer-mode preference with a three-layer priority (highest first):
 *
 * <ol>
 *   <li>JVM property {@code -Dmcb.devMode=true|false} — silent, never prompts.
 *       This is the automation / CI bypass (required for unattended runClient).</li>
 *   <li>{@code config/mcb-bridge.properties} with {@code remember_choice=true} —
 *       silent, uses the remembered {@code dev_mode} value.</li>
 *   <li>otherwise {@link Mode#ASK_ON_TITLE}: ask once on the title screen.</li>
 * </ol>
 *
 * Everything here is defensive: a missing/corrupt config or an unavailable
 * FabricLoader must degrade to {@link Mode#ASK_ON_TITLE}, never crash the game.
 * The class is intentionally loader-only (no Minecraft classes) so the tests
 * can reason about it without a client.
 */
public final class McbDevModeConfig {
    public enum Mode {
        /** Start the bridge immediately, no prompt. */
        ENABLE_SILENT,
        /** Keep the bridge completely off, no prompt. */
        DISABLE_SILENT,
        /** Ask on the title screen (default). */
        ASK_ON_TITLE
    }

    /** JVM property that overrides everything. */
    public static final String SYS_PROP = "mcb.devMode";
    /** File name under the Fabric config directory (normally {@code config/}). */
    public static final String FILE_NAME = "mcb-bridge.properties";

    public static final String KEY_REMEMBER = "remember_choice";
    public static final String KEY_DEV_MODE = "dev_mode";
    public static final String KEY_SOURCE = "source_mod_name";

    /** This mod's id in fabric.mod.json. */
    public static final String MOD_ID = "mcb";
    private static final String FALLBACK_SOURCE_NAME = "MCB Debug Bridge";

    private static volatile Mode cachedMode;
    private static volatile String cachedSourceName;
    private static volatile String cachedSourceId;

    private McbDevModeConfig() {}

    // =====================================================================
    // Three-layer resolution
    // =====================================================================

    /** Resolve and cache the developer-mode decision (JVM > config > ask). */
    public static Mode mode() {
        Mode mode = cachedMode;
        if (mode != null) return mode;
        synchronized (McbDevModeConfig.class) {
            if (cachedMode == null) cachedMode = resolve();
            return cachedMode;
        }
    }

    private static Mode resolve() {
        // Priority 1: JVM property. An invalid value is treated as not passed.
        String raw = System.getProperty(SYS_PROP);
        if (raw != null) {
            Boolean value = parseBoolean(raw);
            if (value != null) {
                McbMod.LOGGER.info("[MCB] dev mode from JVM property -D{}={}", SYS_PROP, value);
                return value ? Mode.ENABLE_SILENT : Mode.DISABLE_SILENT;
            }
            McbMod.LOGGER.warn("[MCB] ignoring invalid -D{}=\"{}\" (expected true/false)", SYS_PROP, raw);
        }

        // Priority 2: remembered config choice.
        Properties props = loadProperties();
        Boolean remember = parseBoolean(props.getProperty(KEY_REMEMBER));
        if (Boolean.TRUE.equals(remember)) {
            Boolean devMode = parseBoolean(props.getProperty(KEY_DEV_MODE));
            if (devMode != null) {
                McbMod.LOGGER.info("[MCB] dev mode from remembered config: dev_mode={}", devMode);
                return devMode ? Mode.ENABLE_SILENT : Mode.DISABLE_SILENT;
            }
            McbMod.LOGGER.warn("[MCB] config has remember_choice=true but no valid dev_mode; asking instead");
        }

        // Priority 3: ask on the title screen.
        return Mode.ASK_ON_TITLE;
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (value.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    // =====================================================================
    // Source label shown in the confirm screen
    // =====================================================================

    /** Display name of the mod that owns this bridge (fabric.mod.json name, overridable). */
    public static String sourceName() {
        ensureSource();
        return cachedSourceName;
    }

    /** Id of the mod that owns this bridge (fabric.mod.json id, overridable). */
    public static String sourceId() {
        ensureSource();
        return cachedSourceId;
    }

    private static synchronized void ensureSource() {
        if (cachedSourceName != null && cachedSourceId != null) return;

        String name = null;
        String id = null;
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer(MOD_ID).orElse(null);
            if (container != null) {
                ModMetadata metadata = container.getMetadata();
                name = metadata.getName();
                id = metadata.getId();
            }
        } catch (Throwable ignored) {
            // Loader metadata is best-effort; the fallback keeps the prompt usable.
        }

        Properties props = loadProperties();
        String override = props.getProperty(KEY_SOURCE, "").trim();
        if (!override.isEmpty()) name = override;

        cachedSourceName = (name == null || name.isBlank()) ? FALLBACK_SOURCE_NAME : name;
        cachedSourceId = (id == null || id.isBlank()) ? MOD_ID : id;
    }

    // =====================================================================
    // Write-back ("remember this choice")
    // =====================================================================

    /** Persist the player's choice; never throws (the prompt must still close). */
    public static void writeChoice(boolean devMode) {
        try {
            Properties props = loadProperties();
            props.setProperty(KEY_REMEMBER, "true");
            props.setProperty(KEY_DEV_MODE, Boolean.toString(devMode));
            if (!props.containsKey(KEY_SOURCE)) props.setProperty(KEY_SOURCE, "");

            Path path = configPath();
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "mcb-bridge developer-mode preference (written by the confirm screen; edit freely)");
            }
            McbMod.LOGGER.info("[MCB] saved dev-mode preference: remember_choice=true dev_mode={}", devMode);
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] could not save dev-mode preference (ignored): {}", t.toString());
        }
    }

    // =====================================================================
    // Config IO
    // =====================================================================

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path path = configPath();
        if (path == null) return props;
        if (!Files.isRegularFile(path)) return props;
        try (InputStream in = Files.newInputStream(path)) {
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            McbMod.LOGGER.warn("[MCB] could not read config {} (using ASK default): {}", path, t.toString());
            return new Properties();
        }
        return props;
    }

    private static Path configPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
