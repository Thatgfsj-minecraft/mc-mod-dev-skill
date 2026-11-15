#!/usr/bin/env node
/**
 * MCB MCP stdio smoke test (no live bridge required):
 * initialize handshake, list tools, call mc_status against a dead bridge and
 * expect a clean `isError` result with the exact BRIDGE_UNREACHABLE contract
 * error. Exit 0 = pass.
 */
import { spawn } from "node:child_process";

const proc = spawn(process.execPath, ["dist/index.js"], { stdio: ["pipe", "pipe", "pipe"] });
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
if (names.length !== 15) throw new Error(`expected 15 tools, got ${names.length}`);

// mc_status against a dead bridge: exact error contract — isError flagged and
// the precise BRIDGE_UNREACHABLE code (a generic "MCB" match once masked a
// crash bug, see review P0-1).
send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: {
  name: "mc_status", arguments: {} } });
const status = await wait(3, 20000);
const text = status.result.content[0].text;
console.log("mc_status ->", text.slice(0, 140));
if (status.result.isError !== true) throw new Error("expected isError:true on failure path");
if (!text.startsWith("[MCB BRIDGE_UNREACHABLE]")) {
  throw new Error(`expected [MCB BRIDGE_UNREACHABLE] contract error, got: ${text}`);
}

// mc_block_get argument shape must be validated by zod, not passed through:
// sending a garbage coordinate must fail cleanly (BAD_PARAMS path, not crash).
send({ jsonrpc: "2.0", id: 4, method: "tools/call", params: {
  name: "mc_block_get", arguments: { x: "not-a-number", y: 0, z: 0 } } });
const badArgs = await wait(4, 20000);
const badText = badArgs.result?.content?.[0]?.text ?? "";
console.log("mc_block_get(bad args) ->", badText.slice(0, 140));
if (badArgs.result?.isError !== true) throw new Error("expected isError:true for invalid args");

proc.removeAllListeners("exit"); // don't fast-fail on our own intentional kill
proc.kill();
console.log("SMOKE TEST PASS");
process.exit(0);
