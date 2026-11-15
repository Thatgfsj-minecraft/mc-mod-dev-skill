#!/usr/bin/env node
/**
 * Integration test against a FAKE bridge (no game needed).
 *
 * Exists because smoke.mjs only exercises the dead-bridge path — which is
 * exactly how the P0-1 bug (this.socket never assigned) shipped undetected:
 * a write-to-null never happens when the connection never opens.
 *
 * The fake bridge speaks MCB v1 well enough to answer status / block.get /
 * ping, and STRICTLY validates the block.get params shape (flat x/y/z), so a
 * consumer regression like the P0-2 tuple-as-x bug fails here immediately.
 */
import net from "node:net";
import { spawn } from "node:child_process";

const PORT = 25586 + Math.floor(Math.random() * 2000);
let blockGetParams = null;

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
send({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "mc_command", arguments: { command: "x" } } });
// command.run is not implemented by the fake bridge → BAD_OP error contract.
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

proc.removeAllListeners("exit");
proc.kill();
server.close();
console.log("INTEGRATION TEST PASS");
process.exit(0);
