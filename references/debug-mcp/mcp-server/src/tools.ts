/**
 * MCP tool registration — one tool per protocol op, naming unified as `mc_*`.
 *
 * The mapping rule (protocol.md §3): tools never introduce a second abstraction
 * over ops. If a tool needs a new capability, the protocol gets a new op first.
 *
 * Error contract: every failure path returns a tool result with
 * `isError: true` so MCP clients can distinguish failures from data
 * (protocol.md §5 codes are echoed in the text for AI readability).
 */
import { readFile } from "node:fs/promises";
import { z } from "zod";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { bridge, BridgeError } from "./bridge.js";

const intVec = z.tuple([z.number().int(), z.number().int(), z.number().int()])
  .describe("[x, y, z] integer coordinates");

// ---------------------------------------------------------------------------
// World lifecycle schemas (protocol §8, v0.5.4). The SCHEMA owns the §8.0 safe
// defaults: a caller that only sends name + preset still gets a creative,
// structure-free, no-natural-spawning, noon/clear, cheats-enabled test world.
//
// HARD RULE (§8.0): unless THIS test explicitly needs survival mechanics or
// structure generation, keep the defaults — creative mode with structures off;
// difficulty normal + natural mob spawning off (NOT peaceful: peaceful
// despawns /summon'ed hostile test mobs instantly, which breaks combat tests).
// ---------------------------------------------------------------------------
const worldName = z.string().min(1).max(64)
  .regex(/^[A-Za-z0-9_-]+$/, "name may only contain A-Za-z0-9_- (no path separators, spaces or dots)")
  .describe('save name: [A-Za-z0-9_-]{1,64}, no path separators or ".."; prefer a "mcb_" prefix (world.delete safety rule)');

const worldCreateShape = {
  name: worldName,
  preset: z.enum(["bedrock_lab", "flat_clean", "vanilla_quiet", "void_platform"])
    .describe("world preset: bedrock_lab (flat bedrock lab) / flat_clean (vanilla superflat) / vanilla_quiet (low-noise vanilla overworld, seed 12345) / void_platform (void + 9x9 bedrock platform at y=64)"),
  clean_if_exists: z.boolean().default(true)
    .describe("delete an existing save of the same name and rebuild it (default true); false + existing save -> WORLD_ALREADY_EXISTS"),
  game_mode: z.enum(["creative", "survival", "adventure", "spectator"]).default("creative")
    .describe("player game mode (default creative — switch only when testing survival mechanics)"),
  generate_structures: z.boolean().default(false)
    .describe("world structure generation: villages/fortresses/dungeons (default false — enable only when the structure itself is under test)"),
  do_mob_spawning: z.boolean().default(false)
    .describe("natural mob spawning (default false). This — not difficulty — is what keeps wild mobs out; difficulty stays normal"),
  difficulty: z.enum(["peaceful", "easy", "normal", "hard"]).default("normal")
    .describe('difficulty (default "normal"). Do NOT use "peaceful" for combat tests: it despawns /summon-ed hostiles'),
  lock_time: z.number().int().min(0).max(23999).nullable().default(6000)
    .describe("lock the world time to this tick, 0-23999 (default 6000 = noon); null = do not lock time"),
  lock_weather: z.enum(["clear", "rain", "thunder"]).nullable().default("clear")
    .describe('lock the weather (default "clear"); null = do not lock weather'),
  fire_tick: z.boolean().default(false)
    .describe("fire spread around the player (default false — keeps fire/explosion tests from spreading out of control)"),
  keep_inventory: z.boolean().default(true)
    .describe("keep inventory on death (default true — an accidental death must not destroy the test setup)"),
  allow_commands: z.boolean().default(true)
    .describe("allow cheats so mc_command works at permission level 4 (default true)"),
  seed: z.number().int().optional()
    .describe("world seed (optional — omit to let the bridge apply its default, 12345 for reproducible tests)"),
  timeout_ms: z.number().int().min(30000).max(600000).default(120000)
    .describe("op watchdog in ms (default 120000, range 30000-600000)"),
};

const worldDeleteShape = {
  name: worldName,
  force: z.boolean().default(false)
    .describe("safely save-and-quit the world first if it is currently running (default false -> WORLD_IN_USE). Never kills the process or bypasses the save lock"),
  timeout_ms: z.number().int().min(10000).max(300000).default(60000)
    .describe("op watchdog in ms (default 60000, range 10000-300000)"),
};

// Exported for the offline smoke test: it asserts the §8.0 defaults by parsing
// the very same zod schema the MCP SDK validates tool arguments with.
export const worldCreateSchema = z.object(worldCreateShape);
export const worldDeleteSchema = z.object(worldDeleteShape);

/** Tool args -> protocol §8.1 params (snake_case input, protocol field names). */
function worldCreateParams(args: any): Record<string, any> {
  const params: Record<string, any> = {
    name: args.name,
    preset: args.preset,
    clean_if_exists: args.clean_if_exists,
    game_mode: args.game_mode,
    generate_structures: args.generate_structures,
    do_mob_spawning: args.do_mob_spawning,
    difficulty: args.difficulty,
    lock_time: args.lock_time,
    lock_weather: args.lock_weather,
    fire_tick: args.fire_tick,
    keep_inventory: args.keep_inventory,
    allow_commands: args.allow_commands,
    timeoutMs: args.timeout_ms,
  };
  if (args.seed !== undefined) params.seed = args.seed;
  return params;
}

/** Tool args -> protocol §8.4 params. */
function worldDeleteParams(args: any): Record<string, any> {
  return { name: args.name, force: args.force, timeoutMs: args.timeout_ms };
}

function text(content: unknown): { content: [{ type: "text"; text: string }] } {
  return {
    content: [{ type: "text", text: typeof content === "string" ? content : JSON.stringify(content, null, 2) }],
  };
}

function errorText(e: unknown): { isError: true; content: [{ type: "text"; text: string }] } {
  const body = e instanceof BridgeError
    ? `[MCB ${e.code}] ${e.message}`
    : `[MCB INTERNAL] ${(e as Error)?.message ?? String(e)}`;
  return { isError: true, content: [{ type: "text", text: body }] };
}

function wrap(handler: (args: any) => Promise<any>) {
  return async (args: any) => {
    try {
      return text(await handler(args));
    } catch (e) {
      return errorText(e);
    }
  };
}

export function registerTools(server: McpServer): void {
  server.registerTool(
    "mc_status",
    {
      title: "Game status",
      description: "Read overall game status: version, loader, whether a world is loaded, dimension, time, weather, player position/health. Always safe to call.",
      inputSchema: {},
    },
    wrap(() => bridge.request("status")),
  );

  server.registerTool(
    "mc_screen_current",
    {
      title: "Current screen / focus state",
      description: "Read the client's current Screen and window focus: in_world, screen_class/screen_title (null when no Screen is open), window_focused (vanilla Minecraft.isWindowActive), pause_on_lost_focus (the upstream option that decides whether losing window focus opens the pause menu), and player dimension/pos/gamemode. Use this to diagnose 'the game looks stuck / a screenshot is covered by the pause menu' and to prove no Screen is covering the HUD after a command.",
      inputSchema: {},
    },
    wrap(() => bridge.request("screen.current")),
  );

  server.registerTool(
    "mc_player",
    {
      title: "Player info",
      description: "Read the player: position, rotation, gamemode, health, food, dimension and hotbar contents.",
      inputSchema: {},
    },
    wrap(() => bridge.request("player.info")),
  );

  server.registerTool(
    "mc_block_get",
    {
      title: "Get one block",
      description: "Read a single block: id, state properties, block entity type and NBT. Use for precise checks after mc_map / mc_scan_blocks.",
      inputSchema: {
        x: z.number().int().describe("block X coordinate"),
        y: z.number().int().describe("block Y coordinate"),
        z: z.number().int().describe("block Z coordinate"),
        dimension: z.string().optional().describe('dimension id like "minecraft:overworld" (default: player dimension)'),
      },
    },
    wrap(async (args: any) => {
      // Protocol §3.3: flat x/y/z params, NOT a tuple under a single key.
      const params: Record<string, any> = { x: args.x, y: args.y, z: args.z };
      if (args.dimension !== undefined) params.dimension = args.dimension;
      return bridge.request("block.get", params);
    }),
  );

  server.registerTool(
    "mc_scan_blocks",
    {
      title: "Scan blocks in a box",
      description: 'List all non-air blocks inside [min, max] (volume <= 4096), optionally filtered by block ids ("minecraft:stone") or tags ("#minecraft:logs").',
      inputSchema: {
        min: intVec,
        max: intVec,
        filter: z.array(z.string()).optional().describe("block ids or #tags; omit for everything"),
        includeProperties: z.boolean().optional().describe("include blockstate properties (default false)"),
      },
    },
    wrap(async (args: any) => {
      // Protocol §3.4: filter is optional — omit the key entirely rather than
      // sending [] (an empty array has no protocol-defined meaning).
      const params: Record<string, any> = { min: args.min, max: args.max };
      if (args.filter !== undefined) params.filter = args.filter;
      if (args.includeProperties !== undefined) params.includeProperties = args.includeProperties;
      return bridge.request("block.scan", params);
    }),
  );

  server.registerTool(
    "mc_map",
    {
      title: "ASCII spatial map",
      description: "Render a region as an ASCII map so you can SEE the layout: mode=top gives a top-down view (first non-air block per column), mode=slice shows one Y layer. Read rows top-to-bottom as min.z -> max.z (north -> south), columns left-to-right as min.x -> max.x (west -> east). The legend maps chars to block ids.",
      inputSchema: {
        min: intVec,
        max: intVec,
        mode: z.enum(["top", "slice"]).optional(),
        y: z.number().int().optional().describe("required when mode=slice"),
      },
    },
    wrap(async (args: any) =>
      bridge.request("map.ascii", {
        min: args.min, max: args.max, mode: args.mode ?? "top", y: args.y,
      })),
  );

  server.registerTool(
    "mc_entities",
    {
      title: "List entities in a box",
      description: "List entities (mobs, items, projectiles...) inside [min, max] with type, uuid, name, position and health (living entities only).",
      inputSchema: {
        min: intVec,
        max: intVec,
        type: z.array(z.string()).optional().describe("entity type id or #tag filter; omit for all"),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = { min: args.min, max: args.max };
      if (args.type !== undefined) params.type = args.type;
      return bridge.request("entity.list", params);
    }),
  );

  server.registerTool(
    "mc_logs",
    {
      title: "Read game logs (incremental)",
      description: "Read game log lines after a cursor id. First call: omit afterId. Later calls: pass the nextId returned before. overflowed=true means some earlier lines were evicted (a gap you cannot recover). NOTE: log ids are per-game-session — after the game restarts (reconnect), start over with afterId=0.",
      inputSchema: {
        afterId: z.number().int().optional().describe("return entries with id > afterId; omit on the first call (default 0 = from buffer start)"),
        limit: z.number().int().optional().describe("max entries (default 500, max 1000)"),
        levelMin: z.enum(["DEBUG", "INFO", "WARN", "ERROR"]).optional(),
      },
    },
    wrap(async (args: any) =>
      bridge.request("log.history", {
        afterId: args.afterId, limit: args.limit ?? 500, levelMin: args.levelMin ?? "INFO",
      })),
  );

  server.registerTool(
    "mc_wait_log",
    {
      title: "Wait for a log line",
      description: "Long-poll until a log line matching a regex appears (or timeout). Prefer this over re-polling mc_logs after running a command.",
      inputSchema: {
        pattern: z.string().describe("regex matched against the whole log message"),
        levelMin: z.enum(["DEBUG", "INFO", "WARN", "ERROR"]).optional(),
        timeoutMs: z.number().int().optional().describe("default 10000, max 60000"),
        afterId: z.number().int().optional().describe("only consider entries with id > afterId (default 0)"),
      },
    },
    wrap(async (args: any) => {
      // Protocol §3.8: afterId is optional — omit when unset.
      const params: Record<string, any> = {
        pattern: args.pattern, levelMin: args.levelMin ?? "INFO", timeoutMs: args.timeoutMs ?? 10000,
      };
      if (args.afterId !== undefined) params.afterId = args.afterId;
      return bridge.request("log.wait", params, 90000);
    }),
  );

  server.registerTool(
    "mc_command",
    {
      title: "Run a game command",
      description: "Execute a Minecraft command (no leading slash): setblock, tp, summon, time set, give, etc. Primary way for you to set up test scenes. side picks the execution channel: auto (default) uses the integrated server when a world is loaded, otherwise the client command channel; server runs on the integrated server at permission level 4 (needs a singleplayer world); client uses the client command channel (also works in multiplayer, but command echo may not be capturable). The response echoes side plus returnValue/messages so you can tell which path ran.",
      inputSchema: {
        command: z.string().describe('command without leading slash, e.g. "setblock 0 64 0 minecraft:stone"'),
        side: z.enum(["auto", "server", "client"]).optional().describe("execution side: auto (default) | server | client"),
        timeoutMs: z.number().int().optional(),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = {
        command: args.command, timeoutMs: args.timeoutMs ?? 10000,
      };
      // Protocol §3.9: side is optional — omit it so the bridge applies its own
      // default (auto). Omitting (instead of sending "auto") stays byte-compatible
      // with 0.1.x bridges that predate the field.
      if (args.side !== undefined) params.side = args.side;
      return bridge.request("command.run", params, (args.timeoutMs ?? 10000) + 10000);
    }),
  );

  server.registerTool(
    "mc_screenshot",
    {
      title: "Take a screenshot",
      description: "Capture the game window as a PNG image so you can visually inspect the game (multimodal).",
      inputSchema: {},
    },
    async () => {
      try {
        const data: any = await bridge.request("screenshot.take", {}, 15000);
        // Protocol §3.11: the bridge declares the format; v1 reference emits PNG.
        const mime = data.format === "png" ? "image/png" : "image/jpeg";
        return {
          content: [
            { type: "image", mimeType: mime, data: data.dataBase64 },
            { type: "text", text: `${data.width}x${data.height} ${data.format}` },
          ],
        };
      } catch (e) {
        return errorText(e);
      }
    },
  );

  server.registerTool(
    "mc_client_screenshot",
    {
      title: "Screenshot to a file",
      description: "Capture the game window to a PNG file under <gameDir>/screenshots/ and return BOTH the image (multimodal) and the absolute file path + size, so you can reference a stable artifact across steps (before/after comparison, post-mortem review). filename must be a bare file name (directories are stripped by the bridge; a .png suffix is added when missing); default mcb-<epochMillis>.png. hide_hud=true hides the vanilla HUD for that one frame only (an already-open Screen stays visible — check mc_screen_current). The bridge waits until the file is fully written.",
      inputSchema: {
        filename: z.string().optional().describe('bare file name, e.g. "after.png" (default mcb-<epochMillis>.png)'),
        hide_hud: z.boolean().optional().describe("hide the vanilla HUD for this one frame (default false)"),
      },
    },
    async (args: any) => {
      try {
        const params: Record<string, any> = {};
        if (args.filename !== undefined) params.filename = args.filename;
        if (args.hide_hud !== undefined) params.hide_hud = args.hide_hud;
        // Bridge-side watchdog is 10s (protocol §3.16) and includes the file
        // write; give the round trip headroom above that so we see its TIMEOUT
        // contract instead of racing it with our own.
        const data: any = await bridge.request("client.screenshot", params, 20000);
        const head = `${data.path} | ${data.width}x${data.height} | screen_class=${data.screen_class ?? "none"} | hide_hud=${data.hide_hud ?? false} | bytes=${data.bytes ?? "?"}`;
        try {
          // §4: the MCP process runs on the same machine as the game, so read the
          // file back and ship the pixels inline — no protocol change needed.
          const png = await readFile(data.path);
          return {
            content: [
              { type: "image", mimeType: "image/png", data: png.toString("base64") },
              { type: "text", text: `${head} | file_read_back=${png.length}` },
            ],
          };
        } catch (e) {
          // Degrade to text only: the game may run on another machine or the path
          // may be unreadable, but the model must still learn where the shot went.
          return {
            content: [{
              type: "text",
              text: `${head} | WARNING: screenshot file unreadable from the MCP process (${(e as Error).message})`,
            }],
          };
        }
      } catch (e) {
        return errorText(e);
      }
    },
  );

  server.registerTool(
    "mc_pause_on_lost_focus",
    {
      title: "Get/set pause-on-lost-focus",
      description: "Read or write the vanilla pauseOnLostFocus option. Omit enabled for a read-only probe. enabled=true restores vanilla behaviour (losing window focus pauses the game) and switches the bridge's auto-pause guard off; enabled=false (the bridge's startup default) keeps the game running while the window is unfocused. Returns {enabled, auto_pause_guard}; auto_pause_guard reports the bridge-side guard (it re-asserts pauseOnLostFocus=false every rendered frame while armed), so the focus-loss pause can stay suppressed even if the option gets toggled back on.",
      inputSchema: {
        enabled: z.boolean().optional().describe("omit for read-only; true/false also persists to options.txt"),
      },
    },
    wrap(async (args: any) => {
      // Protocol §3.17: absent enabled = read-only; never send a null default.
      const params: Record<string, any> = {};
      if (args.enabled !== undefined) params.enabled = args.enabled;
      return bridge.request("client.pause_on_lost_focus", params);
    }),
  );

  server.registerTool(
    "mc_reload_resources",
    {
      title: "Reload client resources (F3+T equivalent)",
      description: "Reload resource packs on the client, the programmatic F3+T, so changed textures/models/blockstates/lang take effect without restarting the game. Returns {reloaded, duration_ms}. Use mc_logs afterwards and search for missing model/texture errors — resource failures usually fail SILENTLY on screen.",
      inputSchema: {},
    },
    // Bridge-side watchdog is 30s (protocol §3.18); allow more so its TIMEOUT
    // contract surfaces rather than our client-side timeout.
    wrap(() => bridge.request("client.reload_resources", {}, 40000)),
  );

  server.registerTool(
    "mc_move_player",
    {
      title: "Move the player (safe teleport)",
      description: "Teleport the player to (x,y,z) with GROUND SAFETY HARDCODED (the player cannot fly): the bridge probes the blocks below the target first; if there is nothing to stand on it places a glass block into the void with server authority BEFORE moving, and zeroes motion + fall distance on arrival. Response reports groundWasMissing / placedGlass so you know what it did. feet Y in the response may be one lower than requested when the actual ground was lower. Use this for interaction; use mc_camera_eye just to LOOK at things.",
      inputSchema: {
        x: z.number().describe("target X (feet position)"),
        y: z.number().describe("target Y (feet position; auto-adjusted down if ground is one block lower, glass auto-placed if void)"),
        z: z.number().describe("target Z (feet position)"),
        yaw: z.number().optional().describe("yaw after tp in degrees, 0=south(+Z); omit to keep current"),
        pitch: z.number().optional().describe("pitch after tp in degrees, 90=down; omit to keep current"),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = { x: args.x, y: args.y, z: args.z };
      if (args.yaw !== undefined) params.yaw = args.yaw;
      if (args.pitch !== undefined) params.pitch = args.pitch;
      return bridge.request("player.move", params, 15000);
    }),
  );

  server.registerTool(
    "mc_camera_eye",
    {
      title: "Sky-eye one-shot screenshot",
      description: "Render ONE frame from an arbitrary virtual camera (position + yaw/pitch + optional fov) WITHOUT teleporting the player, capture it as PNG and restore the player view. Vanilla HUD (hotbar/hearts/chat/crosshair/F3 debug text/first-person hand...) is auto-hidden during the shot for a clean image. Mod info overlays (minihud, Xaero waypoints, Jade block highlight) stay visible. NOTE: with Jade displayMode=LITE and perspectiveMode=CAMERA the Jade block highlight DOES follow this camera; use mc_camera_look when you need the block's id/NBT as structured data. Returns pose + chunks info: chunks.centerLoaded=false or low chunks.loadedNearby means the camera is outside loaded chunks — run mc_command 'forceload add <cx> <cz>' around that spot first, then shoot again.",
      inputSchema: {
        x: z.number().describe("camera eye X (double, e.g. 24.5)"),
        y: z.number().describe("camera eye Y"),
        z: z.number().describe("camera eye Z"),
        yaw: z.number().describe("yaw in degrees, 0=south(+Z), 90=west(-X), 180=north(-Z), 270=east(+X)"),
        pitch: z.number().describe("pitch in degrees, -90=straight up, 0=horizon, 90=straight down"),
        fov: z.number().optional().describe("override projection fov in degrees (1-360); omit to keep the player's fov"),
      },
    },
    async (args: any) => {
      try {
        const params: Record<string, any> = {
          x: args.x, y: args.y, z: args.z, yaw: args.yaw, pitch: args.pitch,
        };
        if (args.fov !== undefined) params.fov = args.fov;
        const data: any = await bridge.request("camera.eye", params, 15000);
        const mime = data.format === "png" ? "image/png" : "image/jpeg";
        const pose = data.pose ?? {};
        const chunks = data.chunks ?? {};
        const chunkNote = chunks.centerLoaded === false
          ? " | WARNING: camera chunk NOT loaded - image is likely void; forceload first"
          : ` | chunks near camera: ${chunks.loadedNearby ?? "?"}/${chunks.nearbyWindow ?? 25} loaded`;
        return {
          content: [
            { type: "image", mimeType: mime, data: data.dataBase64 },
            { type: "text", text: `${data.width}x${data.height} | pose: x=${pose.x} y=${pose.y} z=${pose.z} yaw=${pose.yaw} pitch=${pose.pitch} fov=${pose.fov ?? "player"}${chunkNote}` },
          ],
        };
      } catch (e) {
        return errorText(e);
      }
    },
  );

  server.registerTool(
    "mc_camera_set",
    {
      title: "Enter persistent free camera",
      description: "Lock the render camera to a virtual pose PERSISTENTLY (every frame until mc_camera_clear). The player stays where they are. Vanilla HUD clutter is auto-hidden while the free camera is active; mod info overlays (minihud, Xaero waypoints, Jade block highlight) stay visible. Take further shots with mc_screenshot. NOTE: with Jade displayMode=LITE and perspectiveMode=CAMERA the Jade block highlight DOES follow this camera; use mc_camera_look when you need the block's id/NBT as structured data. Restore the player view with mc_camera_clear when done.",
      inputSchema: {
        x: z.number().describe("camera eye X"),
        y: z.number().describe("camera eye Y"),
        z: z.number().describe("camera eye Z"),
        yaw: z.number().describe("yaw in degrees, 0=south(+Z)"),
        pitch: z.number().describe("pitch in degrees, -90=up, 90=down"),
        fov: z.number().optional().describe("override projection fov in degrees (1-360); omit to keep the player's fov"),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = {
        x: args.x, y: args.y, z: args.z, yaw: args.yaw, pitch: args.pitch,
      };
      if (args.fov !== undefined) params.fov = args.fov;
      return bridge.request("camera.set", params);
    }),
  );

  server.registerTool(
    "mc_camera_look",
    {
      title: "What does the sky-eye camera see?",
      description: "Ray-cast from a virtual camera pose and report the block it hits (id, properties, block-entity NBT, distance). Use this together with mc_camera_eye / mc_camera_set. It answers 'what am I looking at' authoritatively, works at any distance, and gives structured data (no screenshot needed) — cheaper than reading a picture. Give yaw+pitch, or give lookAt:[x,y,z] to aim at a target point.",
      inputSchema: {
        x: z.number().describe("camera eye X (double)"),
        y: z.number().describe("camera eye Y"),
        z: z.number().describe("camera eye Z"),
        yaw: z.number().optional().describe("yaw in degrees, 0=south(+Z), 90=west(-X); omit if lookAt is given"),
        pitch: z.number().optional().describe("pitch in degrees, -90=up, 90=down; omit if lookAt is given"),
        lookAt: z.array(z.number()).optional().describe("[x,y,z] target point to aim at (replaces yaw/pitch)"),
        reach: z.number().optional().describe("max ray length in blocks (default 64)"),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = { x: args.x, y: args.y, z: args.z };
      if (args.lookAt !== undefined) params.lookAt = args.lookAt;
      else {
        params.yaw = args.yaw ?? 0;
        params.pitch = args.pitch ?? 0;
      }
      if (args.reach !== undefined) params.reach = args.reach;
      return bridge.request("camera.look", params);
    }),
  );

  server.registerTool(
    "mc_camera_clear",
    {
      title: "Exit free camera",
      description: "Restore the normal player-attached camera (exits mc_camera_set / cancels a pending mc_camera_eye).",
      inputSchema: {},
    },
    wrap(() => bridge.request("camera.clear")),
  );

  // ---------------------------------------------------------------------------
  // Watch session engine (protocol §7). Rule-driven observation: the bridge
  // samples at t0, watches on the server thread and uploads one high-value
  // report when the rule fires (t1); this consumer only carries the JSON.
  // ---------------------------------------------------------------------------

  server.registerTool(
    "mc_watch_start",
    {
      title: "Start a watch session",
      description: "Start a RULE-DRIVEN watch session (protocol §7.1): the bridge snapshots the box at t0 (baseline) and keeps comparing on the server thread until the upload_rule fires, then returns one report (t1) with trigger_reason / trigger_trace. Pass upload_rule and tracks through verbatim — the rule tree (after_seconds / after_ticks / settle_quiet / on_condition / interval / event / any / all / sequence / repeat, max depth 4, <=16 nodes) is interpreted ENTIRELY by the bridge. Root-level auto_stop (default depends on rule type) closes the session right after the report; buffered reports stay awaitable for 60s. watch_id is caller-chosen, unique per bridge while any session/report is alive, and must not contain '.'. min/max are box corners (order-insensitive, volume <= 32768). Reports carry trigger_reason + trigger_trace; poll them with mc_watch_await using the returned seq as the cursor. NOTE: the reference client bridge only implements this on the integrated (single-player) server — multiplayer / dedicated servers without their own implementation return WORLD_NOT_LOADED. At most 8 active sessions per bridge.",
      inputSchema: {
        watch_id: z.string().min(1)
          .describe('unique session id, ^[A-Za-z0-9_-]{1,64}$ (no "." — reserved for protocol namespaces)'),
        min: intVec.describe("one box corner [x, y, z] (order-insensitive: the bridge normalises per axis)"),
        max: intVec.describe("opposite box corner [x, y, z]"),
        upload_rule: z.record(z.any()).describe("rule tree (protocol §7.2), passed through verbatim; e.g. {\"type\":\"after_seconds\",\"seconds\":3.0,\"auto_stop\":true}"),
        origin: intVec.optional().describe("optional reference centre for report max_radius_from_origin; omit and the report field is null"),
        dimension: z.string().optional().describe('dimension id like "minecraft:overworld" (default: player dimension / overworld)'),
        tracks: z.record(z.any()).optional().describe("permissive object: tracks.blocks / tracks.entities config (protocol §7.1 table), passed through verbatim"),
        report_mode: z.enum(["summary_diff", "timeline_events", "snapshot"]).optional().describe("report shape (default: summary_diff)"),
        detail_limit: z.number().int().min(1).max(200).optional().describe("cap on sample_changes / impacts / events lists (default 20)"),
        max_entity_count: z.number().int().min(1).max(512).optional().describe("max tracked entities (default 256); excess sets entity_count_truncated"),
      },
    },
    wrap(async (args: any) => {
      // Only the fields the caller actually set go on the wire, except the four
      // required ones — the bridge owns every protocol default (§7.1 table).
      const params: Record<string, any> = {
        watch_id: args.watch_id, min: args.min, max: args.max, upload_rule: args.upload_rule,
      };
      if (args.origin !== undefined) params.origin = args.origin;
      if (args.dimension !== undefined) params.dimension = args.dimension;
      if (args.tracks !== undefined) params.tracks = args.tracks;
      if (args.report_mode !== undefined) params.report_mode = args.report_mode;
      if (args.detail_limit !== undefined) params.detail_limit = args.detail_limit;
      if (args.max_entity_count !== undefined) params.max_entity_count = args.max_entity_count;
      return bridge.request("watch.start", params, 15000);
    }),
  );

  server.registerTool(
    "mc_watch_await",
    {
      title: "Wait for the next watch report",
      description: "Long-poll a watch session for the next report (protocol §7.4). Cursor discipline: pass after_seq = the next_seq returned last time (omit on the first call, meaning 0); only reports with seq > after_seq are returned. Response: {matched, next_seq, overflowed, report?}. matched=false is a NORMAL timeout (no rule fired within wait_ms), not an error — keep the returned next_seq and wait again. overflowed=true means the bridge evicted reports you never saw (gap you cannot recover; buffer holds at most 64 unconsumed reports). next_seq is useless across game restarts — reset the cursor after every hello. Sessions closed by auto_stop stay awaitable for 60s; after that WATCH_NOT_FOUND. Multiple connections may await the same session; the cursor lives in YOU, not on the server.",
      inputSchema: {
        watch_id: z.string().min(1).describe("session id returned by mc_watch_start"),
        after_seq: z.number().int().min(0).optional().describe("cursor: only return reports with seq > after_seq (default 0)"),
        wait_ms: z.number().int().min(1).max(60000).optional().describe("long-poll window in ms (default 10000, max 60000)"),
      },
    },
    wrap(async (args: any) => {
      const params: Record<string, any> = { watch_id: args.watch_id };
      // Optional fields are omitted when unset so the bridge applies the §7.4
      // defaults (after_seq=0, wait_ms=10000) — never invent a local default.
      if (args.after_seq !== undefined) params.after_seq = args.after_seq;
      if (args.wait_ms !== undefined) params.wait_ms = args.wait_ms;
      // The bridge sleeps up to wait_ms (its own watchdog is wait_ms; a no-fire
      // answer is matched:false, not TIMEOUT). Add 15s headroom on our side so a
      // real bridge TIMEOUT surfaces as [MCB TIMEOUT] instead of our own cut-off.
      return bridge.request("watch.await", params, (args.wait_ms ?? 10000) + 15000);
    }),
  );

  server.registerTool(
    "mc_watch_snapshot",
    {
      title: "Watch session: immediate diff report",
      description: "Take an immediate t0->now diff report for a watch session WITHOUT touching its rule engine state (protocol §7.5): rules keep being evaluated on their own schedule, nothing is buffered and seq is not incremented. trigger_reason is \"snapshot\". Response data is the report object itself (same shape as a rule report). Read-only, any connection may call it — useful to peek at progress of long interval / repeat sessions. Unknown or fully expired session -> WATCH_NOT_FOUND.",
      inputSchema: {
        watch_id: z.string().min(1).describe("session id returned by mc_watch_start"),
      },
    },
    wrap((args: any) => bridge.request("watch.snapshot", { watch_id: args.watch_id }, 15000)),
  );

  server.registerTool(
    "mc_watch_list",
    {
      title: "List active watch sessions",
      description: "List metadata of ACTIVE watch sessions on the bridge (protocol §7.6): watch_id, bounds, dimension, elapsed_ticks, root rule_type, report_mode, auto_stop, buffered_reports, seq, phase. Sessions closed but still inside the 60s report-retention window are NOT listed (their reports are still awaitable via mc_watch_await). Takes no parameters.",
      inputSchema: {},
    },
    wrap(() => bridge.request("watch.list")),
  );

  server.registerTool(
    "mc_watch_stop",
    {
      title: "Stop a watch session",
      description: "Manually stop a watch session and release its baseline snapshot (protocol §7.7). ONLY the connection that created the session may stop it: any other connection gets BAD_PARAMS with a message containing \"not the owner\" and must drain reports with mc_watch_await instead. Response: {stopped: true, final_report} where final_report is the highest-seq buffered report (already consumed or not) or null when none exists. Reports remain awaitable for 60s after the stop; then WATCH_NOT_FOUND.",
      inputSchema: {
        watch_id: z.string().min(1).describe("session id returned by mc_watch_start"),
      },
    },
    wrap((args: any) => bridge.request("watch.stop", { watch_id: args.watch_id }, 15000)),
  );

  // ---------------------------------------------------------------------------
  // World lifecycle (protocol §8, v0.5.4). The bridge does the actual world
  // selection / save-dir work; these tools keep the §8.0 safe defaults in the
  // schema (see worldCreateShape) so "create a test world" is one call.
  // ---------------------------------------------------------------------------

  server.registerTool(
    "mc_world_create",
    {
      title: "Create a controlled test world",
      description: "Create (or re-create) a CONTROLLED test world from a preset and wait until it is loaded and fully configured (protocol §8). HARD RULE: 除非本次明确测试生存机制或结构生成，否则保持默认创造模式与关闭结构；默认 normal 难度 + 关自然刷怪（不要用和平模式，否则 /summon 的敌对测试怪会消失）. Concretely, the defaults are creative mode, structures OFF, natural mob spawning OFF, difficulty normal, time locked at noon (6000), clear weather, fire spread off, keep-inventory on, cheats on. Every default is OVERRIDABLE (game_mode:\"survival\", generate_structures:true, lock_time:null, ...) but only when THIS test genuinely needs it — and never use difficulty=peaceful for combat/damage tests (/summon-ed hostiles despawn instantly). Presets: bedrock_lab (flat bedrock lab: blocks/redstone/machines/explosions), flat_clean (vanilla superflat: mob AI/pathfinding/crops), vanilla_quiet (low-noise vanilla overworld, seed 12345: real biomes/ores/vanilla ecosystem — use only when needed), void_platform (void + 9x9 bedrock platform at y=64: flying entities, particles, clean screenshots). This op is ASYNC and SLOW: the bridge responds only after the world is loaded and the gamerules/time/weather are applied, which can take 1-2 minutes (timeout_ms 30000-600000, default 120000). Response: world_name/preset/game_mode/difficulty/spawn/seed/gamerules_applied/time_locked/elapsed_ms. Errors: PRESET_INVALID, WORLD_ALREADY_EXISTS (clean_if_exists=false and the save exists), WORLD_IN_USE (another world op is already in flight — single-flight), TIMEOUT, INTERNAL, CLIENT_ONLY (dedicated server). Always clean up with mc_world_delete when the test is over.",
      inputSchema: worldCreateShape,
    },
    // The bridge watchdog is timeout_ms (§8.1/§8.4); keep our own request
    // timeout just above it so the bridge's TIMEOUT contract surfaces first
    // (same headroom discipline as mc_watch_await / mc_client_screenshot).
    wrap((args: any) => bridge.request("world.create", worldCreateParams(args), args.timeout_ms + 15000)),
  );

  server.registerTool(
    "mc_world_delete",
    {
      title: "Delete a test world",
      description: "Delete a test world save from disk (protocol §8.4) — the cleanup step after mc_world_create. SAFETY RED LINE enforced by the bridge: only saves whose name starts with \"mcb_\" OR that were created by world.create (marker file) may be deleted; a player's hand-made world can NEVER be deleted this way and returns WORLD_DELETE_FORBIDDEN — force does NOT bypass this. force=false (default): if the target world is currently loaded/running the op fails with WORLD_IN_USE; force=true: save and quit it safely back to the title screen first, then delete (no hard process kill, no bypassing the save lock). The recursive delete runs on a background thread. Errors: WORLD_NOT_FOUND, WORLD_IN_USE, WORLD_DELETE_FORBIDDEN, BAD_PARAMS, TIMEOUT, INTERNAL, CLIENT_ONLY. Response: world_name/deleted/elapsed_ms.",
      inputSchema: worldDeleteShape,
    },
    wrap((args: any) => bridge.request("world.delete", worldDeleteParams(args), args.timeout_ms + 10000)),
  );
}
