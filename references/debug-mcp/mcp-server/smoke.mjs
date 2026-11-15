#!/usr/bin/env node
/**
 * MCB MCP stdio smoke test: initialize handshake, list tools, call mc_status
 * (game not running -> expect a clean BRIDGE_UNREACHABLE error, which is the
 * correct contract behavior), call tools/list. Exit 0 = pass.
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
if (names.length !== 10) throw new Error(`expected 10 tools, got ${names.length}`);

// mc_status against a dead bridge must be a clean text error, not a crash.
send({ jsonrpc: "2.0", id: 3, method: "tools/call", params: {
  name: "mc_status", arguments: {} } });
const status = await wait(3, 20000);
const text = status.result.content[0].text;
console.log("mc_status ->", text.slice(0, 120));
if (!text.includes("MCB")) throw new Error("expected MCB error prefix");

proc.kill();
console.log("SMOKE TEST PASS");
process.exit(0);
