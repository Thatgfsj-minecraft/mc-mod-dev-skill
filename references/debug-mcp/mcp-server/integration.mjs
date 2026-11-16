#!/usr/bin/env node
/**
 * Integration test against a FAKE bridge (no game needed).
 *
 * Exists because smoke.mjs only exercises the dead-bridge path — which is
 * exactly how the P0-1 bug (this.socket never assigned) shipped undetected:
 * a write-to-null never happens when the connection never opens.
 *
 * The fake bridge speaks MCB v1 well enough to answer status / block.get /
 * command.run / screen.current / client.* / ping, and STRICTLY validates every
 * params shape it receives (flat x/y/z, basename-only screenshot filename,
 * boolean hide_hud, the command.run side enum, ...) so a consumer regression
 * like the P0-2 tuple-as-x bug fails here immediately.
 */
import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";

const PORT = 25586 + Math.floor(Math.random() * 2000);

// client.screenshot is a file-op: the fake bridge returns a path and the MCP
// tool is expected to read that file back and inline the pixels. Keep a real
// (tiny but valid) PNG on disk so the read-back path is genuinely exercised.
const SHOT_PATH = path.join(os.tmpdir(), `mcb-integration-${process.pid}.png`);
const PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
fs.writeFileSync(SHOT_PATH, Buffer.from(PNG_1X1, "base64"));
const SHOT_BYTES = fs.statSync(SHOT_PATH).size;

// Captured request params, asserted at the bottom so we test the WIRE shape
// rather than only the tool's surface behavior.
let blockGetParams = null;
let screenParams = null;
let shotParams = null;
let pauseParams = null;
let reloadParams = null;
let commandParams = null;

const server = net.createServer((socket) => {
  socket.setEncoding("utf8");
  socket.write(JSON.stringify({
    event: "hello",
    data: { protocol: 1, game: "1.21.11-fake", loader: "fabric", side: "client", worldLoaded: true },
  }) + "\n");
  let buf = "";
  socket.on("data", (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      const req = JSON.parse(line);
      const reply = (data) => socket.write(JSON.stringify({ id: req.id, ok: true, data }) + "\n");
      const fail = (code, message) => socket.write(JSON.stringify({ id: req.id, ok: false, error: { code, message } }) + "\n");
      switch (req.op) {
        case "ping": reply({ pong: true, uptimeMs: 1 }); break;
        case "status": reply({
          game: "1.21.11-fake", loader: "fabric", side: "client", worldLoaded: true,
          dimension: "minecraft:overworld", timeOfDay: 1000, raining: false, thundering: false,
          droppedEvents: 0,
          player: { x: 0.5, y: 64, z: 0.5, yaw: 0, pitch: 0, gamemode: "creative", health: 20, present: true },
        }); break;
        case "block.get": {
          blockGetParams = req.params; // captured for strict shape assertion below
          if (typeof req.params.x !== "number" || typeof req.params.y !== "number" || typeof req.params.z !== "number") {
            fail("BAD_PARAMS", "fake bridge requires flat numeric x/y/z (protocol §3.3)");
            break;
          }
          reply({ x: req.params.x, y: req.params.y, z: req.params.z, id: "minecraft:stone", properties: {}, hasBlockEntity: false });
          break;
        }
        case "camera.eye": {
          // Strict param shape: camera eye params must arrive flat + numeric,
          // fov optional (protocol §3.12).
          const q = req.params;
          if (![q.x, q.y, q.z, q.yaw, q.pitch].every((v) => typeof v === "number")) {
            fail("BAD_PARAMS", "fake bridge requires flat numeric x/y/z/yaw/pitch (protocol §3.12)");
            break;
          }
          reply({
            format: "png", width: 1280, height: 720, dataBase64: "ZmFrZQ==",
            pose: { x: q.x, y: q.y, z: q.z, yaw: q.yaw, pitch: q.pitch, fov: q.fov ?? null },
            chunks: { centerLoaded: true, loadedNearby: 25, nearbyWindow: 25 },
          });
          break;
        }
        case "camera.set": {
          const q = req.params;
          if (![q.x, q.y, q.z, q.yaw, q.pitch].every((v) => typeof v === "number")) {
            fail("BAD_PARAMS", "fake bridge requires flat numeric x/y/z/yaw/pitch (protocol §3.12)");
            break;
          }
          reply({ active: true, mode: "persistent", pose: { x: q.x, y: q.y, z: q.z, yaw: q.yaw, pitch: q.pitch, fov: q.fov ?? null } });
          break;
        }
        case "camera.clear":
          reply({ active: false, mode: "none" });
          break;
        case "camera.look": {
          // Strict param shape: flat numeric x/y/z plus either flat yaw/pitch
          // or a lookAt [x,y,z] array (protocol §3.14).
          const q = req.params;
          if (![q.x, q.y, q.z].every((v) => typeof v === "number")) {
            fail("BAD_PARAMS", "fake bridge requires flat numeric x/y/z (protocol §3.14)");
            break;
          }
          const hasLookAt = Array.isArray(q.lookAt) && q.lookAt.length === 3
            && q.lookAt.every((v) => typeof v === "number");
          if (!hasLookAt && ![q.yaw, q.pitch].every((v) => typeof v === "number")) {
            fail("BAD_PARAMS", "fake bridge requires yaw/pitch or lookAt (protocol §3.14)");
            break;
          }
          reply({
            originX: q.x, originY: q.y, originZ: q.z,
            yaw: hasLookAt ? 45 : q.yaw, pitch: hasLookAt ? -30 : q.pitch,
            reach: q.reach ?? 64,
            hit: true, hitType: "BLOCK", distance: 3.5,
            x: 12, y: 61, z: 8, id: "fabricaeexnihilo:porcelain_crucible",
            properties: {}, hasBlockEntity: true,
          });
          break;
        }
        case "screen.current": {
          // §3.15: no params at all — sending an empty object is the contract,
          // sending anything else means the consumer invented a parameter.
          screenParams = req.params ?? {};
          if (Object.keys(screenParams).length !== 0) {
            fail("BAD_PARAMS", "screen.current takes no params (protocol §3.15)");
            break;
          }
          reply({
            in_world: true, screen_class: null, screen_title: null,
            window_focused: false, pause_on_lost_focus: false,
            player: { dimension: "minecraft:overworld", pos: [0.5, 64.0, -8.2], game_mode: "creative" },
          });
          break;
        }
        case "client.screenshot": {
          // §3.16: filename (bare name only) + hide_hud (boolean), both optional.
          const q = req.params ?? {};
          shotParams = q;
          const unknown = Object.keys(q).filter((k) => k !== "filename" && k !== "hide_hud");
          if (unknown.length) {
            fail("BAD_PARAMS", `client.screenshot unknown params: ${unknown.join(",")} (protocol §3.16)`);
            break;
          }
          if (q.filename !== undefined) {
            // The bridge is allowed to sanitize, but the CONSUMER must not rely on
            // it: a path with directories is a contract violation we want to see.
            if (typeof q.filename !== "string" || !/^[A-Za-z0-9._-]+$/.test(q.filename)) {
              fail("BAD_PARAMS", "client.screenshot filename must be a bare file name (protocol §3.16)");
              break;
            }
            if (!q.filename.toLowerCase().endsWith(".png")) {
              fail("BAD_PARAMS", "client.screenshot filename must end in .png (protocol §3.16)");
              break;
            }
          }
          if (q.hide_hud !== undefined && typeof q.hide_hud !== "boolean") {
            fail("BAD_PARAMS", "client.screenshot hide_hud must be a boolean (protocol §3.16)");
            break;
          }
          reply({
            path: SHOT_PATH, width: 1280, height: 720, screen_class: null,
            hide_hud: q.hide_hud ?? false, bytes: SHOT_BYTES,
          });
          break;
        }
        case "client.pause_on_lost_focus": {
          // §3.17: optional boolean enabled; absent = read-only.
          const q = req.params ?? {};
          pauseParams = q;
          const unknown = Object.keys(q).filter((k) => k !== "enabled");
          if (unknown.length) {
            fail("BAD_PARAMS", `client.pause_on_lost_focus unknown params: ${unknown.join(",")} (protocol §3.17)`);
            break;
          }
          if (q.enabled !== undefined && typeof q.enabled !== "boolean") {
            fail("BAD_PARAMS", "client.pause_on_lost_focus enabled must be a boolean (protocol §3.17)");
            break;
          }
          const enabled = q.enabled ?? false;
          reply({ enabled, auto_pause_guard: !enabled });
          break;
        }
        case "client.reload_resources": {
          // §3.18: no params.
          reloadParams = req.params ?? {};
          if (Object.keys(reloadParams).length !== 0) {
            fail("BAD_PARAMS", "client.reload_resources takes no params (protocol §3.18)");
            break;
          }
          reply({ reloaded: true, duration_ms: 42 });
          break;
        }
        case "command.run": {
          // §3.9 revision: command (required) + optional side enum + timeoutMs.
          const q = req.params ?? {};
          commandParams = q;
          const unknown = Object.keys(q).filter((k) => !["command", "side", "timeoutMs"].includes(k));
          if (unknown.length) {
            fail("BAD_PARAMS", `command.run unknown params: ${unknown.join(",")} (protocol §3.9)`);
            break;
          }
          if (typeof q.command !== "string" || q.command.length === 0) {
            fail("BAD_PARAMS", "command.run requires a non-empty command string (protocol §3.9)");
            break;
          }
          if (q.side !== undefined && !["auto", "server", "client"].includes(q.side)) {
            fail("BAD_PARAMS", "command.run side must be auto|server|client (protocol §3.9)");
            break;
          }
          if (q.timeoutMs !== undefined && !Number.isInteger(q.timeoutMs)) {
            fail("BAD_PARAMS", "command.run timeoutMs must be an integer (protocol §3.9)");
            break;
          }
          const side = q.side ?? "auto";
          const effective = side === "auto" ? "server" : side;
          reply({
            success: true,
            returnValue: effective === "server" ? 1 : null,
            resultText: effective === "server" ? "Set the time to 12000" : "",
            feedback: effective === "server" ? ["Set the time to 12000"] : [],
            messages: effective === "server" ? ["Set the time to 12000"] : [],
            side: effective,
          });
          break;
        }
        case "player.move": {
          // Strict param shape: flat numeric x/y/z, optional yaw/pitch (§3.13).
          const q = req.params;
          if (![q.x, q.y, q.z].every((v) => typeof v === "number")) {
            fail("BAD_PARAMS", "fake bridge requires flat numeric x/y/z (protocol §3.13)");
            break;
          }
          reply({
            success: true, moved: true,
            x: q.x, y: q.y, z: q.z, yaw: q.yaw ?? 0, pitch: q.pitch ?? 0,
            dimension: "minecraft:overworld",
            groundWasMissing: true, placedGlass: true, placedGlassVerified: true,
          });
          break;
        }
        default: fail("BAD_OP", `unknown op: ${req.op}`);
      }
    }
  });
});
server.listen(PORT, "127.0.0.1");

await new Promise((r) => server.on("listening", r));

const proc = spawn(process.execPath, ["dist/index.js"], {
  stdio: ["pipe", "pipe", "pipe"],
  env: { ...process.env, MCB_PORT: String(PORT) },
});
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
proc.on("exit", (code) => {
  console.error(`MCP server exited early (code=${code})`);
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

send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {
  protocolVersion: "2024-11-05", capabilities: {},
  clientInfo: { name: "integration", version: "0.0.1" } } });
await wait(1, 10000);
send({ jsonrpc: "2.0", method: "notifications/initialized" });
console.log("handshake ok");

// 1. mc_status must return real data through the live bridge.
send({ jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "mc_status", arguments: {} } });
const status = await wait(2);
const statusData = JSON.parse(status.result.content[0].text);
if (status.result.isError === true || statusData.worldLoaded !== true) {
  throw new Error(`mc_status failed: ${status.result.content[0].text.slice(0, 200)}`);
}
console.log("mc_status ok:", statusData.game, "dimension:", statusData.dimension);

// 2. mc_block_get must send FLAT x/y/z params (protocol §3.3) and get data back.
send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: {
  name: "mc_block_get", arguments: { x: 10, y: 64, z: -3 } } });
const block = await wait(3);
const blockData = JSON.parse(block.result.content[0].text);
if (block.result.isError === true || blockData.id !== "minecraft:stone") {
  throw new Error(`mc_block_get failed: ${block.result.content[0].text.slice(0, 200)}`);
}
const p = blockGetParams;
if (typeof p.x !== "number" || p.x !== 10 || p.y !== 64 || p.z !== -3 || Array.isArray(p.x)) {
  throw new Error(`block.get params shape wrong: ${JSON.stringify(p)}`);
}
console.log("mc_block_get ok, params:", JSON.stringify(p));

// 3. Unknown-op mapping: fake bridge's BAD_OP must surface as a clean text error.
// command.run is implemented by the fake bridge below, so use an op it does not
// implement (player.info) to keep exercising the BAD_OP -> [MCB BAD_OP] contract.
send({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "mc_player", arguments: {} } });
// player.info is not implemented by the fake bridge → BAD_OP error contract.
const cmd = await wait(4);
const cmdText = cmd.result.content[0].text;
if (cmd.result.isError !== true || !cmdText.startsWith("[MCB BAD_OP]")) {
  throw new Error(`expected [MCB BAD_OP] contract error, got: ${cmdText.slice(0, 200)}`);
}
console.log("BAD_OP contract ok");

// 4. mc_camera_eye: flat numeric params + image + pose/chunks passthrough.
send({ jsonrpc: "2.0", id: 5, method: "tools/call", params: {
  name: "mc_camera_eye", arguments: { x: 24.5, y: -61.0, z: 8.2, yaw: 90, pitch: 10, fov: 60 } } });
const eye = await wait(5);
const eyeImg = eye.result?.content?.find((c) => c.type === "image");
const eyeText = eye.result?.content?.find((c) => c.type === "text")?.text ?? "";
if (eye.result.isError === true || !eyeImg || eyeImg.mimeType !== "image/png") {
  throw new Error(`mc_camera_eye failed: ${JSON.stringify(eye.result).slice(0, 300)}`);
}
if (!eyeText.includes("yaw=90") || !eyeText.includes("fov=60")) {
  throw new Error(`mc_camera_eye text missing pose echo: ${eyeText}`);
}
console.log("mc_camera_eye ok:", eyeText.slice(0, 140));

// 5. mc_camera_set / mc_camera_clear round trip.
send({ jsonrpc: "2.0", id: 6, method: "tools/call", params: {
  name: "mc_camera_set", arguments: { x: 1, y: 2, z: 3, yaw: 0, pitch: 0 } } });
const camSet = await wait(6);
const setData = JSON.parse(camSet.result.content[0].text);
if (camSet.result.isError === true || setData.mode !== "persistent") {
  throw new Error(`mc_camera_set failed: ${camSet.result.content[0].text.slice(0, 200)}`);
}
send({ jsonrpc: "2.0", id: 7, method: "tools/call", params: { name: "mc_camera_clear", arguments: {} } });
const camClear = await wait(7);
const clearData = JSON.parse(camClear.result.content[0].text);
if (camClear.result.isError === true || clearData.active !== false) {
  throw new Error(`mc_camera_clear failed: ${camClear.result.content[0].text.slice(0, 200)}`);
}
console.log("camera set/clear round trip ok");

// 6. mc_move_player: flat numeric params + ground-safety fields echoed.
send({ jsonrpc: "2.0", id: 8, method: "tools/call", params: {
  name: "mc_move_player", arguments: { x: 24, y: -63, z: 8 } } });
const moved = await wait(8);
const moveData = JSON.parse(moved.result.content[0].text);
if (moved.result.isError === true || moveData.moved !== true) {
  throw new Error(`mc_move_player failed: ${moved.result.content[0].text.slice(0, 200)}`);
}
if (moveData.placedGlass !== true || moveData.placedGlassVerified !== true) {
  throw new Error(`mc_move_player ground-safety fields missing: ${moved.result.content[0].text.slice(0, 200)}`);
}
console.log("mc_move_player ok:", JSON.stringify(moveData));

// 7. mc_camera_look: yaw/pitch form AND lookAt form both reach the bridge.
send({ jsonrpc: "2.0", id: 9, method: "tools/call", params: {
  name: "mc_camera_look", arguments: { x: 13.5, y: 62.5, z: 9.5, yaw: 135, pitch: 35 } } });
const look1 = await wait(9);
const look1Data = JSON.parse(look1.result.content[0].text);
if (look1.result.isError === true || look1Data.hit !== true || look1Data.id !== "fabricaeexnihilo:porcelain_crucible") {
  throw new Error(`mc_camera_look failed: ${look1.result.content[0].text.slice(0, 200)}`);
}
console.log("mc_camera_look (yaw/pitch) ok:", look1Data.id);

send({ jsonrpc: "2.0", id: 10, method: "tools/call", params: {
  name: "mc_camera_look", arguments: { x: 13.5, y: 62.5, z: 9.5, lookAt: [12, 61, 8] } } });
const look2 = await wait(10);
const look2Data = JSON.parse(look2.result.content[0].text);
if (look2.result.isError === true || look2Data.yaw !== 45 || look2Data.pitch !== -30) {
  throw new Error(`mc_camera_look lookAt form failed: ${look2.result.content[0].text.slice(0, 200)}`);
}
console.log("mc_camera_look (lookAt) ok: yaw=" + look2Data.yaw + " pitch=" + look2Data.pitch);

// 8. mc_screen_current: paramless op, full screen.current shape passthrough.
send({ jsonrpc: "2.0", id: 11, method: "tools/call", params: { name: "mc_screen_current", arguments: {} } });
const screen = await wait(11);
const screenData = JSON.parse(screen.result.content[0].text);
if (screen.result.isError === true || screenData.in_world !== true
    || screenData.window_focused !== false || screenData.pause_on_lost_focus !== false
    || screenData.player?.game_mode !== "creative") {
  throw new Error(`mc_screen_current failed: ${screen.result.content[0].text.slice(0, 300)}`);
}
if (JSON.stringify(screenParams) !== "{}") {
  throw new Error(`screen.current must be sent with no params, got: ${JSON.stringify(screenParams)}`);
}
console.log("mc_screen_current ok:", JSON.stringify(screenData.player), "pause_on_lost_focus:", screenData.pause_on_lost_focus);

// 9. mc_command side: explicit enum passes through; absent side is OMITTED so
// the bridge applies its own default (auto) — that is the §3.9 backward-compat
// contract (0.1.x bridges never saw the field).
send({ jsonrpc: "2.0", id: 12, method: "tools/call", params: {
  name: "mc_command", arguments: { command: "time set noon", side: "server" } } });
const cmdServer = await wait(12);
const cmdServerData = JSON.parse(cmdServer.result.content[0].text);
if (cmdServer.result.isError === true || cmdServerData.success !== true
    || cmdServerData.side !== "server" || cmdServerData.returnValue !== 1) {
  throw new Error(`mc_command(side=server) failed: ${cmdServer.result.content[0].text.slice(0, 300)}`);
}
if (commandParams.command !== "time set noon" || commandParams.side !== "server") {
  throw new Error(`command.run params shape wrong: ${JSON.stringify(commandParams)}`);
}
console.log("mc_command(side=server) ok:", JSON.stringify(commandParams));

send({ jsonrpc: "2.0", id: 13, method: "tools/call", params: {
  name: "mc_command", arguments: { command: "say hi" } } });
const cmdAuto = await wait(13);
const cmdAutoData = JSON.parse(cmdAuto.result.content[0].text);
// side=auto resolves to the server path here (fake worldLoaded=true), and the
// response reports the side that actually RAN (§3.9), not "auto".
if (cmdAuto.result.isError === true || cmdAutoData.side !== "server") {
  throw new Error(`mc_command(default side) failed: ${cmdAuto.result.content[0].text.slice(0, 300)}`);
}
if ("side" in commandParams) {
  throw new Error(`mc_command must omit side when unset, got: ${JSON.stringify(commandParams)}`);
}
console.log("mc_command(default) ok: auto resolved to", cmdAutoData.side, "(side omitted on the wire)");

// The side enum is also validated consumer-side (zod), so a bogus value never
// reaches the wire.
send({ jsonrpc: "2.0", id: 14, method: "tools/call", params: {
  name: "mc_command", arguments: { command: "say hi", side: "bogus" } } });
const cmdBadSide = await wait(14);
if (cmdBadSide.result?.isError !== true) {
  throw new Error("mc_command must reject a side outside auto|server|client");
}
console.log("mc_command(bad side) rejected consumer-side");

// 10. mc_client_screenshot: filename/hide_hud reach the bridge, and the tool
// reads the reported file back and inlines it as image content (§4 contract).
send({ jsonrpc: "2.0", id: 15, method: "tools/call", params: {
  name: "mc_client_screenshot", arguments: { filename: "mcb_integration.png", hide_hud: true } } });
const shot = await wait(15);
const shotImg = shot.result?.content?.find((c) => c.type === "image");
const shotText = shot.result?.content?.find((c) => c.type === "text")?.text ?? "";
if (shot.result.isError === true || !shotImg || shotImg.mimeType !== "image/png") {
  throw new Error(`mc_client_screenshot failed: ${JSON.stringify(shot.result).slice(0, 300)}`);
}
if (shotImg.data !== fs.readFileSync(SHOT_PATH).toString("base64")) {
  throw new Error("mc_client_screenshot image content does not match the file on disk");
}
if (shotParams.filename !== "mcb_integration.png" || shotParams.hide_hud !== true) {
  throw new Error(`client.screenshot params shape wrong: ${JSON.stringify(shotParams)}`);
}
if (!shotText.startsWith(SHOT_PATH) || !shotText.includes("1280x720")
    || !shotText.includes("hide_hud=true") || !shotText.includes(`file_read_back=${SHOT_BYTES}`)) {
  throw new Error(`mc_client_screenshot text missing path/size contract: ${shotText}`);
}
console.log("mc_client_screenshot ok:", shotText.slice(0, 160));

// Default args: neither optional param may be invented.
send({ jsonrpc: "2.0", id: 16, method: "tools/call", params: {
  name: "mc_client_screenshot", arguments: {} } });
const shotDefault = await wait(16);
if (shotDefault.result.isError === true || JSON.stringify(shotParams) !== "{}") {
  throw new Error(`mc_client_screenshot default params should be {}, got: ${JSON.stringify(shotParams)}`);
}
const shotDefaultText = shotDefault.result.content.find((c) => c.type === "text")?.text ?? "";
if (!shotDefaultText.includes("hide_hud=false")) {
  throw new Error(`mc_client_screenshot default hide_hud should be false: ${shotDefaultText}`);
}
console.log("mc_client_screenshot(default) ok:", shotDefaultText.slice(0, 140));

// 11. mc_pause_on_lost_focus: write form passes enabled; read-only form omits it.
send({ jsonrpc: "2.0", id: 17, method: "tools/call", params: {
  name: "mc_pause_on_lost_focus", arguments: { enabled: true } } });
const pauseOn = await wait(17);
const pauseOnData = JSON.parse(pauseOn.result.content[0].text);
if (pauseOn.result.isError === true || pauseOnData.enabled !== true || pauseOnData.auto_pause_guard !== false) {
  throw new Error(`mc_pause_on_lost_focus(enabled=true) failed: ${pauseOn.result.content[0].text.slice(0, 300)}`);
}
if (pauseParams.enabled !== true) {
  throw new Error(`client.pause_on_lost_focus params shape wrong: ${JSON.stringify(pauseParams)}`);
}
console.log("mc_pause_on_lost_focus(enabled=true) ok:", JSON.stringify(pauseOnData));

send({ jsonrpc: "2.0", id: 18, method: "tools/call", params: {
  name: "mc_pause_on_lost_focus", arguments: {} } });
const pauseRead = await wait(18);
const pauseReadData = JSON.parse(pauseRead.result.content[0].text);
if (pauseRead.result.isError === true || pauseReadData.enabled !== false || pauseReadData.auto_pause_guard !== true) {
  throw new Error(`mc_pause_on_lost_focus(read-only) failed: ${pauseRead.result.content[0].text.slice(0, 300)}`);
}
if ("enabled" in pauseParams) {
  throw new Error(`client.pause_on_lost_focus read-only must omit enabled, got: ${JSON.stringify(pauseParams)}`);
}
console.log("mc_pause_on_lost_focus(read-only) ok:", JSON.stringify(pauseReadData));

// 12. mc_reload_resources: paramless op.
send({ jsonrpc: "2.0", id: 19, method: "tools/call", params: { name: "mc_reload_resources", arguments: {} } });
const reload = await wait(19, 30000);
const reloadData = JSON.parse(reload.result.content[0].text);
if (reload.result.isError === true || reloadData.reloaded !== true || reloadData.duration_ms !== 42) {
  throw new Error(`mc_reload_resources failed: ${reload.result.content[0].text.slice(0, 300)}`);
}
if (JSON.stringify(reloadParams) !== "{}") {
  throw new Error(`client.reload_resources must be sent with no params, got: ${JSON.stringify(reloadParams)}`);
}
console.log("mc_reload_resources ok:", JSON.stringify(reloadData));

fs.unlinkSync(SHOT_PATH);
proc.removeAllListeners("exit");
proc.kill();
server.close();
console.log("INTEGRATION TEST PASS");
process.exit(0);
