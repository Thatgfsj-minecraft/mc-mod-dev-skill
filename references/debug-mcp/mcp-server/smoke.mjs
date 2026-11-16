#!/usr/bin/env node
/**
 * MCB MCP stdio smoke test.
 *
 * Two modes:
 *  - default (offline): no game required. The MCP server is pointed at a port
 *    that was just proven closed. We must NOT let it fall back to the default
 *    25585: a live game may be listening there (a developer's runClient), and
 *    connecting to it would both touch the user's game and make the dead-bridge
 *    contract assertion flaky. Asserts: initialize, 26 tools, the exact
 *    BRIDGE_UNREACHABLE error contract, and zod argument validation (including
 *    the protocol §8.0 world defaults owned by the tool schema).
 *  - MCB_LIVE=1: runtime acceptance against a real bridge (bridge-mod 0.2.1+).
 *    Raw TCP ping first (there is deliberately no mc_ping tool: ops map 1:1 to
 *    tools and ping is infrastructure, not a capability), then screen.current /
 *    command.run / client.screenshot through the MCP tools, and finally the
 *    protocol §8 world lifecycle: mc_world_create -> mc_status -> mc_world_delete.
 *    Point MCB_PORT / MCB_HOST elsewhere when the game listens on a non-default
 *    port.
 */
import net from "node:net";
import fs from "node:fs";
import { spawn } from "node:child_process";
// Imported straight from the build so the offline suite can assert the §8.0
// defaults exactly as the MCP SDK parses tool arguments. No bridge involved:
// these defaults are consumer-side and must not depend on the game being up.
import { worldCreateSchema, worldDeleteSchema } from "./dist/tools.js";

const LIVE = process.env.MCB_LIVE === "1";
const MCB_HOST = process.env.MCB_HOST ?? "127.0.0.1";
const MCB_PORT = process.env.MCB_PORT ? Number(process.env.MCB_PORT) : 25585;

/** Reserve-then-release an ephemeral port: nothing is listening on it afterwards. */
function findDeadPort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
  });
}

/**
 * Live pre-flight: wait for the hello event and complete one ping op over a raw
 * socket. Proves the bridge process is actually up before we spend time on MCP
 * calls (a dead bridge would otherwise surface as a slow connect timeout).
 */
function livePing() {
  return new Promise((resolve, reject) => {
    const socket = net.connect(MCB_PORT, MCB_HOST);
    socket.setEncoding("utf8");
    let buf = "";
    let sawHello = false;
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`no hello/pong from MCB bridge on ${MCB_HOST}:${MCB_PORT} within 5000ms`));
    }, 5000);
    const fail = (err) => { clearTimeout(timer); socket.destroy(); reject(err); };
    socket.on("data", (chunk) => {
      buf += chunk;
      let idx;
      while ((idx = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line) continue;
        let msg;
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.event === "hello") {
          if (msg.data?.protocol !== 1) {
            return fail(new Error(`bridge speaks protocol ${msg.data?.protocol}, consumer expects 1`));
          }
          sawHello = true;
          socket.write(JSON.stringify({ id: 1, op: "ping", params: {} }) + "\n");
          continue;
        }
        if (msg.id === 1) {
          clearTimeout(timer);
          socket.destroy();
          if (!msg.ok || msg.data?.pong !== true) return reject(new Error(`ping failed: ${line.slice(0, 200)}`));
          resolve(msg.data);
        }
      }
    });
    socket.on("error", (e) => { clearTimeout(timer); reject(e); });
    socket.on("close", () => {
      if (!sawHello) { clearTimeout(timer); reject(new Error("bridge closed the connection before sending hello")); }
    });
  });
}

// Offline: dead explicit port. Live: inherit the environment (MCB_PORT/MCB_HOST).
const env = LIVE ? { ...process.env } : { ...process.env, MCB_PORT: String(await findDeadPort()) };
const proc = spawn(process.execPath, ["dist/index.js"], { stdio: ["pipe", "pipe", "pipe"], env });
let outBuf = "";
const responses = new Map();
proc.stdout.on("data", (chunk) => {
  outBuf += chunk;
  let idx;
  while ((idx = outBuf.indexOf("\n")) >= 0) {
    const line = outBuf.slice(0, idx).trim();
    outBuf = outBuf.slice(idx + 1);
    if (!line) continue;
    try {
      const msg = JSON.parse(line);
      if (msg.id !== undefined) responses.set(msg.id, msg);
    } catch { /* ignore */ }
  }
});
proc.stderr.on("data", (d) => process.stderr.write(d));
// Fast-fail: if the server process dies (bad build, crash), don't wait out the
// full response timeout — report the exit code immediately.
proc.on("exit", (code, signal) => {
  console.error(`server exited early (code=${code} signal=${signal})`);
  process.exit(1);
});

function send(obj) {
  proc.stdin.write(JSON.stringify(obj) + "\n");
  return obj.id;
}
function wait(id, ms = 15000) {
  const start = Date.now();
  return new Promise((resolve, reject) => {
    const tick = () => {
      if (responses.has(id)) return resolve(responses.get(id));
      if (Date.now() - start > ms) return reject(new Error(`timeout waiting for response ${id}`));
      setTimeout(tick, 50);
    };
    tick();
  });
}
async function callTool(id, name, args, ms = 30000) {
  send({ jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: args } });
  const r = await wait(id, ms);
  const result = r.result ?? {};
  const text = result.content?.find((c) => c.type === "text")?.text ?? "";
  const image = result.content?.find((c) => c.type === "image");
  return { result, text, image };
}

send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {
  protocolVersion: "2024-11-05", capabilities: {},
  clientInfo: { name: "smoke", version: "0.0.1" } } });
const init = await wait(1, 10000);
console.log("initialize ok:", init.result.serverInfo);

send({ jsonrpc: "2.0", method: "notifications/initialized" });

send({ jsonrpc: "2.0", id: 2, method: "tools/list" });
const tools = await wait(2);
const names = tools.result.tools.map((t) => t.name);
console.log("tools:", names.join(", "));
// v0.5.1: 15 -> 19 (screen.current / client.screenshot /
// client.pause_on_lost_focus / client.reload_resources).
// v0.5.2: 19 -> 24 (watch.start / watch.await / watch.snapshot / watch.list /
// watch.stop — protocol §7).
// v0.5.4: 24 -> 26 (world.create / world.delete — protocol §8).
if (names.length !== 26) throw new Error(`expected 26 tools, got ${names.length}`);
for (const expected of [
  "mc_screen_current", "mc_client_screenshot", "mc_pause_on_lost_focus", "mc_reload_resources",
  "mc_watch_start", "mc_watch_await", "mc_watch_snapshot", "mc_watch_list", "mc_watch_stop",
  "mc_world_create", "mc_world_delete",
]) {
  if (!names.includes(expected)) throw new Error(`missing tool ${expected}`);
}

if (LIVE) {
  // -------------------------------------------------------------------------
  // Live path (MCB_LIVE=1): requires a real game + bridge-mod 0.2.1+.
  // -------------------------------------------------------------------------
  const pong = await livePing();
  console.log("live ping ok:", JSON.stringify(pong));

  // The bridge must have disabled vanilla pause-on-lost-focus at startup
  // (v0.5.1 §1.1); false here is the core keep-alive acceptance criterion.
  const screen = await callTool(20, "mc_screen_current", {});
  console.log("mc_screen_current ->", screen.text.slice(0, 200));
  // Check isError BEFORE JSON.parse: on an old bridge this is [MCB BAD_OP] text,
  // and a raw SyntaxError would hide the actionable "upgrade bridge-mod" hint.
  if (screen.result.isError === true) throw new Error(`mc_screen_current failed: ${screen.text.slice(0, 200)}`);
  const screenData = JSON.parse(screen.text);
  if (screenData.pause_on_lost_focus !== false) {
    throw new Error(`expected pause_on_lost_focus=false (v0.5.1 keep-alive), got ${screenData.pause_on_lost_focus}`);
  }

  const cmd = await callTool(21, "mc_command", { command: "time set noon" });
  console.log("mc_command(time set noon) ->", cmd.text.slice(0, 200));
  if (cmd.result.isError === true) throw new Error(`mc_command failed: ${cmd.text.slice(0, 200)}`);
  const cmdData = JSON.parse(cmd.text);
  if (cmdData.success !== true) {
    throw new Error(`mc_command reported success=false: ${cmd.text.slice(0, 200)}`);
  }

  // The file write completes before the bridge responds, so a non-empty file
  // here also proves the render-thread -> IO-thread ordering (§3.1).
  const shot = await callTool(22, "mc_client_screenshot", { filename: "mcb_smoke.png" }, 30000);
  const shotPath = shot.text.split(" | ")[0];
  console.log("mc_client_screenshot ->", shot.text.slice(0, 200));
  if (shot.result.isError === true) throw new Error(`mc_client_screenshot failed: ${shot.text.slice(0, 200)}`);
  if (!shot.image || shot.image.mimeType !== "image/png" || !shot.image.data) {
    throw new Error(`mc_client_screenshot returned no image content: ${shot.text.slice(0, 200)}`);
  }
  if (!shotPath || !fs.existsSync(shotPath)) {
    throw new Error(`mc_client_screenshot did not produce a readable file (reported path: ${shotPath})`);
  }
  const size = fs.statSync(shotPath).size;
  if (!(size > 0)) throw new Error(`screenshot file is empty: ${shotPath}`);
  console.log(`screenshot file ok: ${shotPath} (${size} bytes)`);

  // Argument validation is transport-independent; exercise it live too.
  const badArgs = await callTool(23, "mc_block_get", { x: "not-a-number", y: 0, z: 0 });
  if (badArgs.result.isError !== true) throw new Error("expected isError:true for invalid args (live)");
  console.log("argument validation ok (live)");

  // -------------------------------------------------------------------------
  // World lifecycle acceptance (protocol §8, bridge-mod 0.5.0+):
  // mc_world_create -> mc_status -> mc_world_delete, in that order. BAD_OP means
  // the installed bridge predates v0.5.4, so skip (do not fail the live run).
  // -------------------------------------------------------------------------
  const WORLD_NAME = "mcb_smoke_world";
  const created = await callTool(24, "mc_world_create", { name: WORLD_NAME, preset: "flat_clean" }, 180000);
  console.log("mc_world_create ->", created.text.slice(0, 220));
  if (created.result.isError === true && created.text.startsWith("[MCB BAD_OP]")) {
    console.log("bridge predates protocol §8 (BAD_OP): world lifecycle acceptance skipped");
  } else if (created.result.isError === true) {
    throw new Error(`mc_world_create failed: ${created.text.slice(0, 300)}`);
  } else {
    let createFailure = null;
    try {
      const wc = JSON.parse(created.text);
      // §8.0/§8.1: the defaults must have really been applied, and spawn is the
      // REAL spawn point of the created world (not an echo of the request).
      if (wc.world_name !== WORLD_NAME || wc.preset !== "flat_clean"
          || wc.game_mode !== "creative" || wc.difficulty !== "normal"
          || wc.time_locked !== 6000 || !Array.isArray(wc.gamerules_applied)
          || !Array.isArray(wc.spawn) || wc.spawn.length !== 3) {
        throw new Error(`mc_world_create response violates §8.0/§8.1: ${created.text.slice(0, 300)}`);
      }
      const liveStatus = await callTool(25, "mc_status", {});
      const wst = JSON.parse(liveStatus.text);
      if (liveStatus.result.isError === true || wst.worldLoaded !== true) {
        throw new Error(`mc_status after world.create must report worldLoaded=true: ${liveStatus.text.slice(0, 200)}`);
      }
      console.log(`mc_world_create ok: preset=${wc.preset} spawn=${JSON.stringify(wc.spawn)} time_locked=${wc.time_locked}; status worldLoaded=true`);
    } catch (e) {
      createFailure = e; // delete first, then surface the failed assertion
    }
    // Always clean up: force=true saves-and-quits the running world first (§8.4).
    const removed = await callTool(26, "mc_world_delete", { name: WORLD_NAME, force: true }, 120000);
    console.log("mc_world_delete ->", removed.text.slice(0, 220));
    if (removed.result.isError === true) {
      throw createFailure ?? new Error(`mc_world_delete failed: ${removed.text.slice(0, 300)}`);
    }
    const rm = JSON.parse(removed.text);
    if (rm.deleted !== true) {
      throw createFailure ?? new Error(`mc_world_delete response violates §8.4: ${removed.text.slice(0, 200)}`);
    }
    const afterStatus = await callTool(27, "mc_status", {});
    const ast = JSON.parse(afterStatus.text);
    if (ast.worldLoaded !== false) {
      throw createFailure ?? new Error(`mc_status after world.delete must report worldLoaded=false: ${afterStatus.text.slice(0, 200)}`);
    }
    if (createFailure) throw createFailure;
    console.log("world lifecycle acceptance ok: create -> status -> delete");
  }
} else {
  // -------------------------------------------------------------------------
  // Offline path: dead bridge error contract + zod validation.
  // -------------------------------------------------------------------------
  // mc_status against a dead bridge: exact error contract — isError flagged and
  // the precise BRIDGE_UNREACHABLE code (a generic "MCB" match once masked a
  // crash bug, see review P0-1).
  const status = await callTool(3, "mc_status", {});
  console.log("mc_status ->", status.text.slice(0, 140));
  if (status.result.isError !== true) throw new Error("expected isError:true on failure path");
  if (!status.text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
    throw new Error(`expected [MCB BRIDGE_UNREACHABLE] contract error, got: ${status.text}`);
  }

  // mc_block_get argument shape must be validated by zod, not passed through:
  // sending a garbage coordinate must fail cleanly (BAD_PARAMS path, not crash).
  const badArgs = await callTool(4, "mc_block_get", { x: "not-a-number", y: 0, z: 0 });
  console.log("mc_block_get(bad args) ->", badArgs.text.slice(0, 140));
  if (badArgs.result.isError !== true) throw new Error("expected isError:true for invalid args");

  // New tools must also surface the dead-bridge contract (they are reachable
  // through the same bridge request path, not registered as stubs).
  const screenDead = await callTool(5, "mc_screen_current", {});
  if (screenDead.result.isError !== true || !screenDead.text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
    throw new Error(`mc_screen_current dead-bridge contract broken: ${screenDead.text.slice(0, 200)}`);
  }
  console.log("new tools dead-bridge contract ok");

  // Watch tools (protocol §7) are real bridge requests too — not registered
  // stubs. mc_watch_list is the paramless one, so it hits the bridge directly.
  const watchDead = await callTool(6, "mc_watch_list", {});
  console.log("mc_watch_list ->", watchDead.text.slice(0, 140));
  if (watchDead.result.isError !== true || !watchDead.text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
    throw new Error(`mc_watch_list dead-bridge contract broken: ${watchDead.text.slice(0, 200)}`);
  }
  console.log("watch tool dead-bridge contract ok");

  // zod must reject a fractional wait_ms (§7.4: integer 1..60000) consumer-side:
  // the bad value must never reach the bridge.
  const badWait = await callTool(7, "mc_watch_await", { watch_id: "smoke_watch", wait_ms: 2500.5 });
  console.log("mc_watch_await(bad wait_ms) ->", badWait.text.slice(0, 140));
  if (badWait.result.isError !== true) {
    throw new Error("expected isError:true for non-integer wait_ms (§7.4)");
  }

  // ---------------------------------------------------------------------------
  // World lifecycle (protocol §8, v0.5.4). The §8.0 hard defaults live in the
  // tool schema, so the WHOLE safe contract must be visible without a bridge:
  // a caller that only passes name + preset must still get a creative,
  // structure-free, no-natural-spawning, noon/clear, cheats-enabled world.
  // ---------------------------------------------------------------------------
  const wcDefaults = worldCreateSchema.parse({ name: "mcb_smoke", preset: "bedrock_lab" });
  const EXPECTED_WORLD_DEFAULTS = {
    clean_if_exists: true, game_mode: "creative", generate_structures: false,
    do_mob_spawning: false, difficulty: "normal", lock_time: 6000,
    lock_weather: "clear", fire_tick: false, keep_inventory: true,
    allow_commands: true, timeout_ms: 120000,
  };
  for (const [key, want] of Object.entries(EXPECTED_WORLD_DEFAULTS)) {
    if (wcDefaults[key] !== want) {
      throw new Error(`mc_world_create default ${key}: expected ${JSON.stringify(want)}, got ${JSON.stringify(wcDefaults[key])}`);
    }
  }
  // seed is optional on purpose: the bridge owns its default (12345, §8.0).
  if ("seed" in wcDefaults) throw new Error("mc_world_create must not invent a seed default (§8.0)");
  const wdDefaults = worldDeleteSchema.parse({ name: "mcb_smoke" });
  if (wdDefaults.force !== false || wdDefaults.timeout_ms !== 60000) {
    throw new Error(`mc_world_delete defaults wrong: ${JSON.stringify(wdDefaults)}`);
  }
  // lock_time / lock_weather must accept an explicit null (§8.1 "do not lock").
  const wcUnlocked = worldCreateSchema.parse({
    name: "mcb_smoke", preset: "void_platform", lock_time: null, lock_weather: null,
  });
  if (wcUnlocked.lock_time !== null || wcUnlocked.lock_weather !== null) {
    throw new Error("lock_time/lock_weather must accept explicit null (§8.1)");
  }
  console.log("world tool schema defaults ok:", JSON.stringify(wcDefaults));

  // Both tools are real bridge requests, not stubs (dead-bridge contract).
  const worldCreateDead = await callTool(8, "mc_world_create", { name: "mcb_smoke", preset: "bedrock_lab" });
  console.log("mc_world_create ->", worldCreateDead.text.slice(0, 140));
  if (worldCreateDead.result.isError !== true || !worldCreateDead.text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
    throw new Error(`mc_world_create dead-bridge contract broken: ${worldCreateDead.text.slice(0, 200)}`);
  }
  const worldDeleteDead = await callTool(9, "mc_world_delete", { name: "mcb_smoke" });
  console.log("mc_world_delete ->", worldDeleteDead.text.slice(0, 140));
  if (worldDeleteDead.result.isError !== true || !worldDeleteDead.text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
    throw new Error(`mc_world_delete dead-bridge contract broken: ${worldDeleteDead.text.slice(0, 200)}`);
  }
  console.log("world tool dead-bridge contract ok");

  // zod must reject a preset outside the §8.2 4-preset enum and a game_mode
  // outside the §8.1 enum consumer-side: the bogus value never reaches the wire.
  const badPreset = await callTool(10, "mc_world_create", { name: "mcb_smoke", preset: "not_a_preset" });
  console.log("mc_world_create(bad preset) ->", badPreset.text.slice(0, 140));
  if (badPreset.result.isError !== true || !/preset/i.test(badPreset.text)) {
    throw new Error(`expected a preset enum validation error, got: ${badPreset.text.slice(0, 200)}`);
  }
  const badMode = await callTool(11, "mc_world_create", {
    name: "mcb_smoke", preset: "flat_clean", game_mode: "hardcore",
  });
  console.log("mc_world_create(bad game_mode) ->", badMode.text.slice(0, 140));
  if (badMode.result.isError !== true || !/game_mode/i.test(badMode.text)) {
    throw new Error(`expected a game_mode enum validation error, got: ${badMode.text.slice(0, 200)}`);
  }
  console.log("world tool enum validation ok");
}

proc.removeAllListeners("exit"); // don't fast-fail on our own intentional kill
proc.kill();
console.log("SMOKE TEST PASS");
process.exit(0);
