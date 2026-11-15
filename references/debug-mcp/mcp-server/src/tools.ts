/**
 * MCP tool registration — one tool per protocol op, naming unified as `mc_*`.
 *
 * The mapping rule (protocol.md §3): tools never introduce a second abstraction
 * over ops. If a tool needs a new capability, the protocol gets a new op first.
 */
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

function wrap(handler: (args: any) => Promise<any>) {
  return async (args: any) => {
    try {
      return text(await handler(args));
    } catch (e: any) {
      if (e instanceof BridgeError) {
        return text(`[MCB ${e.code}] ${e.message}`);
      }
      return text(`[MCB INTERNAL] ${e?.message ?? String(e)}`);
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
      description: "Read a single block: id, state properties, block entity type and NBT. Use for precise checks after mc_map / mc_scan.",
      inputSchema: {
        x: intVec.describe("[x, y, z] of the block"),
      },
    },
    wrap(async (args: any) => bridge.request("block.get", { x: args.x })),
  );

  server.registerTool(
    "mc_scan_blocks",
    {
      title: "Scan blocks in a box",
      description: "List all non-air blocks inside [min, max] (volume <= 4096), optionally filtered by block ids (\"minecraft:stone\") or tags (\"#minecraft:logs\").",
      inputSchema: {
        min: intVec,
        max: intVec,
        filter: z.array(z.string()).optional().describe("block ids or #tags; empty = everything"),
        includeProperties: z.boolean().optional().describe("include blockstate properties (default false)"),
      },
    },
    wrap(async (args: any) =>
      bridge.request("block.scan", {
        min: args.min, max: args.max,
        filter: args.filter ?? [], includeProperties: args.includeProperties ?? false,
      })),
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
        type: z.array(z.string()).optional().describe("entity type id filter, e.g. minecraft:item"),
      },
    },
    wrap(async (args: any) =>
      bridge.request("entity.list", { min: args.min, max: args.max, type: args.type ?? [] })),
  );

  server.registerTool(
    "mc_logs",
    {
      title: "Read game logs (incremental)",
      description: "Read game log lines after a cursor id. First call: omit afterId. Later calls: pass the nextId returned before. overflowed=true means some earlier lines were evicted (a gap you cannot recover).",
      inputSchema: {
        afterId: z.number().optional().describe("return entries with id > afterId (default 0 = from buffer start)"),
        limit: z.number().int().optional().describe("max entries (default 500)"),
        levelMin: z.enum(["DEBUG", "INFO", "WARN", "ERROR"]).optional(),
      },
    },
    wrap(async (args: any) =>
      bridge.request("log.history", {
        afterId: args.afterId ?? 0, limit: args.limit ?? 500, levelMin: args.levelMin ?? "INFO",
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
      },
    },
    wrap(async (args: any) =>
      bridge.request("log.wait", {
        pattern: args.pattern, levelMin: args.levelMin ?? "INFO", timeoutMs: args.timeoutMs ?? 10000,
      }, 90000)),
  );

  server.registerTool(
    "mc_command",
    {
      title: "Run a game command",
      description: "Execute a Minecraft command (no leading slash) as operator level 4 on the integrated server: setblock, tp, summon, time set, give, etc. Primary way for you to set up test scenes.",
      inputSchema: {
        command: z.string().describe("command without leading slash, e.g. \"setblock 0 64 0 minecraft:stone\""),
        timeoutMs: z.number().int().optional(),
      },
    },
    wrap(async (args: any) =>
      bridge.request("command.run", {
        command: args.command, timeoutMs: args.timeoutMs ?? 10000,
      }, (args.timeoutMs ?? 10000) + 10000)),
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
        return {
          content: [
            { type: "image", mimeType: "image/png", data: data.dataBase64 },
            { type: "text", text: `${data.width}x${data.height} png` },
          ],
        };
      } catch (e: any) {
        return text(e instanceof BridgeError ? `[MCB ${e.code}] ${e.message}` : String(e));
      }
    },
  );
}
