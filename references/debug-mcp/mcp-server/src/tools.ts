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
      description: "Read or write the vanilla pauseOnLostFocus option. Omit enabled for a read-only probe. enabled=true restores vanilla behaviour (losing window focus pauses the game) and switches the bridge's auto-pause guard off; enabled=false (the bridge's startup default) keeps the game running while the window is unfocused. Returns {enabled, auto_pause_guard}; auto_pause_guard reports the bridge's Mixin guard, which can suppress the focus-loss pause even while enabled=true.",
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
}
