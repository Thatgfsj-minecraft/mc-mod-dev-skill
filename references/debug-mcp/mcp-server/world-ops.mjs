/**
 * World lifecycle test extension for integration.mjs (protocol §8, v0.5.4).
 *
 * Kept in its own module so integration.mjs stays a readable test script and
 * under the repo's 1000-line-per-file budget. This file owns three things:
 *   - the FAKE BRIDGE handlers for world.create / world.delete (strict wire
 *     validation: §8.0 defaults must be materialised by the consumer schema,
 *     protocol field names only, timeout_ms -> timeoutMs, null = do not lock),
 *   - the offline assertions for mc_world_create / mc_world_delete,
 *   - the MCB_LIVE=1 real-machine path (create -> status -> delete), skipped by
 *     default so the suite needs no game.
 *
 * integration.mjs imports handleWorldOp for its fake-bridge switch and calls the
 * two run* functions from its main flow.
 */

/** Last world.* params seen by the fake bridge, asserted by the checks below. */
const worldCaptures = { create: null, delete: null };

/** Order-insensitive deep comparison of a captured wire params object. */
const sortedJson = (o) => JSON.stringify(Object.fromEntries(Object.entries(o ?? {}).sort(([a], [b]) => a.localeCompare(b))));
function assertWire(actual, expected, label) {
  if (sortedJson(actual) !== sortedJson(expected)) {
    throw new Error(`${label} wire shape wrong: got ${sortedJson(actual)} want ${sortedJson(expected)}`);
  }
}

const isVec3 = (v) => Array.isArray(v) && v.length === 3 && v.every((n) => typeof n === "number");
const SAVE_NAME = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * Fake-bridge handlers for the §8 ops. Returns true when the op was handled;
 * the caller falls through to BAD_OP for anything else.
 */
export function handleWorldOp(op, params, reply, fail) {
  if (op === "world.create") {
    // §8.1: name + preset required; §8.0 defaults MUST already be materialised
    // by the consumer schema (an AI passing only name+preset must still get the
    // safe contract). timeoutMs is the protocol's camelCase spelling.
    const q = params ?? {};
    worldCaptures.create = { ...q };
    const allowed = ["name", "preset", "clean_if_exists", "game_mode", "generate_structures",
      "do_mob_spawning", "difficulty", "lock_time", "lock_weather", "fire_tick",
      "keep_inventory", "allow_commands", "seed", "timeoutMs"];
    const unknown = Object.keys(q).filter((k) => !allowed.includes(k));
    if (unknown.length) return fail("BAD_PARAMS", `world.create unknown params: ${unknown.join(",")} (protocol §8.1)`);
    if (typeof q.name !== "string" || !SAVE_NAME.test(q.name)) {
      return fail("BAD_PARAMS", "world.create name must match [A-Za-z0-9_-]{1,64} (protocol §8.1)");
    }
    if (!["bedrock_lab", "flat_clean", "vanilla_quiet", "void_platform"].includes(q.preset)) {
      return fail("PRESET_INVALID", "world.create preset is not one of the 4 presets (protocol §8.2)");
    }
    const bools = ["clean_if_exists", "generate_structures", "do_mob_spawning", "fire_tick", "keep_inventory", "allow_commands"];
    if (bools.some((k) => typeof q[k] !== "boolean")) {
      return fail("BAD_PARAMS", "world.create boolean defaults must be materialised (protocol §8.0)");
    }
    if (!["creative", "survival", "adventure", "spectator"].includes(q.game_mode)) {
      return fail("BAD_PARAMS", "world.create game_mode invalid (protocol §8.1)");
    }
    if (!["peaceful", "easy", "normal", "hard"].includes(q.difficulty)) {
      return fail("BAD_PARAMS", "world.create difficulty invalid (protocol §8.1)");
    }
    if (!(q.lock_time === null || (Number.isInteger(q.lock_time) && q.lock_time >= 0 && q.lock_time <= 23999))) {
      return fail("BAD_PARAMS", "world.create lock_time must be int 0..23999 or null (protocol §8.1)");
    }
    if (!(q.lock_weather === null || ["clear", "rain", "thunder"].includes(q.lock_weather))) {
      return fail("BAD_PARAMS", "world.create lock_weather must be clear|rain|thunder or null (protocol §8.1)");
    }
    if (q.seed !== undefined && !Number.isInteger(q.seed)) {
      return fail("BAD_PARAMS", "world.create seed must be an integer when set (protocol §8.1)");
    }
    if (q.timeoutMs !== undefined && (!Number.isInteger(q.timeoutMs) || q.timeoutMs < 30000 || q.timeoutMs > 600000)) {
      return fail("BAD_PARAMS", "world.create timeoutMs must be an integer 30000..600000 (protocol §8.1)");
    }
    reply({
      world_name: q.name, preset: q.preset, game_mode: q.game_mode, difficulty: q.difficulty,
      spawn: q.preset === "void_platform" ? [0, 65, 0] : [0, -61, 0],
      seed: q.seed ?? 12345,
      gamerules_applied: ["spawn_mobs", "advance_time", "advance_weather", "keep_inventory"],
      time_locked: q.lock_time, elapsed_ms: 8500,
    });
    return true;
  }
  if (op === "world.delete") {
    // §8.4: name required; force boolean; timeoutMs int. The §8.4 safety red
    // line is bridge-side: non-"mcb_" saves are refused, force or not.
    const q = params ?? {};
    worldCaptures.delete = { ...q };
    const unknown = Object.keys(q).filter((k) => !["name", "force", "timeoutMs"].includes(k));
    if (unknown.length) return fail("BAD_PARAMS", `world.delete unknown params: ${unknown.join(",")} (protocol §8.4)`);
    if (typeof q.name !== "string" || !SAVE_NAME.test(q.name)) {
      return fail("BAD_PARAMS", "world.delete name must match [A-Za-z0-9_-]{1,64} (protocol §8.4)");
    }
    if (typeof q.force !== "boolean") {
      return fail("BAD_PARAMS", "world.delete force must be materialised as a boolean (protocol §8.4)");
    }
    if (q.timeoutMs !== undefined && (!Number.isInteger(q.timeoutMs) || q.timeoutMs < 10000 || q.timeoutMs > 300000)) {
      return fail("BAD_PARAMS", "world.delete timeoutMs must be an integer 10000..300000 (protocol §8.4)");
    }
    if (!q.name.startsWith("mcb_")) {
      return fail("WORLD_DELETE_FORBIDDEN", "refusing to delete a non-mcb_ save (protocol §8.4 safety red line)");
    }
    reply({ world_name: q.name, deleted: true, elapsed_ms: 420 });
    return true;
  }
  return false;
}

/**
 * Offline assertions (run against the fake bridge by integration.mjs).
 * send/wait are integration.mjs's MCP stdio helpers.
 */
export async function runWorldCreateDeleteChecks(send, wait) {
  // 20. The §8.0 safe defaults are owned by the consumer schema, so a call with
  // only name+preset must put the WHOLE contract on the wire — and seed must
  // stay absent (the bridge owns 12345).
  const call = async (id, name, args, ms = 30000) => {
    send({ jsonrpc: "2.0", id, method: "tools/call", params: { name, arguments: args } });
    return wait(id, ms);
  };
  const textOf = (r) => r.result?.content?.find((c) => c.type === "text")?.text ?? "";

  const worldCreate = await call(29, "mc_world_create", { name: "mcb_test", preset: "bedrock_lab" });
  const wcData = JSON.parse(textOf(worldCreate));
  if (worldCreate.result.isError === true || wcData.world_name !== "mcb_test" || wcData.preset !== "bedrock_lab"
      || wcData.game_mode !== "creative" || wcData.difficulty !== "normal"
      || wcData.time_locked !== 6000 || wcData.seed !== 12345
      || !Array.isArray(wcData.spawn) || wcData.spawn[1] !== -61
      || !Array.isArray(wcData.gamerules_applied) || wcData.elapsed_ms !== 8500) {
    throw new Error(`mc_world_create failed: ${textOf(worldCreate).slice(0, 300)}`);
  }
  assertWire(worldCaptures.create, {
    name: "mcb_test", preset: "bedrock_lab", clean_if_exists: true, game_mode: "creative",
    generate_structures: false, do_mob_spawning: false, difficulty: "normal", lock_time: 6000,
    lock_weather: "clear", fire_tick: false, keep_inventory: true, allow_commands: true,
    timeoutMs: 120000,
  }, "world.create(defaults)");
  if ("seed" in worldCaptures.create) throw new Error("world.create must not invent a seed default (§8.0)");
  if ("timeout_ms" in worldCaptures.create) {
    throw new Error("world.create leaked the tool param name timeout_ms; wire field is timeoutMs (§8.1)");
  }
  console.log("mc_world_create(defaults) ok:", JSON.stringify(wcData));

  // 21. Every §8.0 default is overridable — and null means "do not lock" (§8.1).
  const worldCreate2 = await call(30, "mc_world_create", {
    name: "mcb_survival", preset: "void_platform", clean_if_exists: false, game_mode: "survival",
    generate_structures: true, do_mob_spawning: true, difficulty: "hard",
    lock_time: null, lock_weather: null, fire_tick: true, keep_inventory: false,
    allow_commands: false, seed: 42, timeout_ms: 300000,
  });
  const wcData2 = JSON.parse(textOf(worldCreate2));
  if (worldCreate2.result.isError === true || wcData2.game_mode !== "survival" || wcData2.difficulty !== "hard"
      || wcData2.time_locked !== null || wcData2.seed !== 42
      || JSON.stringify(wcData2.spawn) !== JSON.stringify([0, 65, 0])) {
    throw new Error(`mc_world_create(overrides) failed: ${textOf(worldCreate2).slice(0, 300)}`);
  }
  assertWire(worldCaptures.create, {
    name: "mcb_survival", preset: "void_platform", clean_if_exists: false, game_mode: "survival",
    generate_structures: true, do_mob_spawning: true, difficulty: "hard", lock_time: null,
    lock_weather: null, fire_tick: true, keep_inventory: false, allow_commands: false,
    seed: 42, timeoutMs: 300000,
  }, "world.create(overrides)");
  console.log("mc_world_create(overrides) ok: survival/hard, unlocked time+weather, timeoutMs=300000");

  // 22. mc_world_delete: force defaults to false and timeoutMs is the wire field.
  const worldDelete = await call(31, "mc_world_delete", { name: "mcb_test" });
  const wdData = JSON.parse(textOf(worldDelete));
  if (worldDelete.result.isError === true || wdData.world_name !== "mcb_test" || wdData.deleted !== true) {
    throw new Error(`mc_world_delete failed: ${textOf(worldDelete).slice(0, 300)}`);
  }
  assertWire(worldCaptures.delete, { name: "mcb_test", force: false, timeoutMs: 60000 }, "world.delete(defaults)");
  console.log("mc_world_delete(defaults) ok:", JSON.stringify(wdData));

  // 23. force=true reaches the wire; the §8.4 safety red line surfaces verbatim
  // (WORLD_DELETE_FORBIDDEN for a non-"mcb_" save — force does NOT bypass it).
  const worldDelete2 = await call(32, "mc_world_delete", { name: "mcb_force", force: true });
  if (worldDelete2.result.isError === true) {
    throw new Error(`mc_world_delete(force) failed: ${textOf(worldDelete2).slice(0, 300)}`);
  }
  assertWire(worldCaptures.delete, { name: "mcb_force", force: true, timeoutMs: 60000 }, "world.delete(force)");
  const forbidden = await call(33, "mc_world_delete", { name: "player_world", force: true });
  if (forbidden.result.isError !== true || !textOf(forbidden).startsWith("[MCB WORLD_DELETE_FORBIDDEN]")) {
    throw new Error(`expected [MCB WORLD_DELETE_FORBIDDEN] contract, got: ${textOf(forbidden).slice(0, 200)}`);
  }
  console.log("mc_world_delete ok: force passthrough + WORLD_DELETE_FORBIDDEN contract");

  // 24. Illegal save names are rejected consumer-side (path traversal stays out
  // of the tool), and the bad call never reaches the wire.
  const beforeBadName = sortedJson(worldCaptures.create);
  const badName = await call(34, "mc_world_create", { name: "../evil", preset: "flat_clean" });
  if (badName.result?.isError !== true) throw new Error("mc_world_create must reject a name with path separators (§8.1)");
  if (sortedJson(worldCaptures.create) !== beforeBadName) throw new Error("rejected world.create still reached the bridge");
  console.log("mc_world_create(bad name) rejected consumer-side");
}

/**
 * MCB_LIVE=1 only. Talks to an actual game through a second MCP process
 * (startMcp is passed in by integration.mjs): create -> status -> delete, with
 * best-effort cleanup so a failed assertion never leaves a test world behind.
 */
export async function runLiveWorldPath(startMcp) {
  if (process.env.MCB_LIVE !== "1") {
    console.log("LIVE world path skipped (set MCB_LIVE=1 with a running game to run it)");
    return;
  }
  const LIVE_WORLD = "mcb_integration_world";
  const live = startMcp({ ...process.env });
  live.send({ jsonrpc: "2.0", id: 1, method: "initialize", params: {
    protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "integration-live", version: "0.0.1" } } });
  await live.wait(1, 10000);
  live.send({ jsonrpc: "2.0", method: "notifications/initialized" });
  let liveFailure = null;
  let createdOk = false;
  try {
    const created = await live.call("mc_world_create", { name: LIVE_WORLD, preset: "flat_clean" }, 2, 180000);
    console.log("LIVE mc_world_create ->", created.text.slice(0, 200));
    if (created.result.isError === true) {
      if (created.text.startsWith("[MCB BAD_OP]")) {
        console.log("LIVE world ops: bridge predates protocol §8 (BAD_OP); skipped");
      } else {
        throw new Error(`live mc_world_create failed: ${created.text.slice(0, 300)}`);
      }
    } else {
      createdOk = true; // always try to delete it, even if an assertion below fails
      const wc = JSON.parse(created.text);
      if (wc.world_name !== LIVE_WORLD || wc.preset !== "flat_clean"
          || wc.game_mode !== "creative" || wc.difficulty !== "normal"
          || wc.time_locked !== 6000 || !Array.isArray(wc.gamerules_applied)
          || !isVec3(wc.spawn)) {
        throw new Error(`live mc_world_create response violates §8.0/§8.1: ${created.text.slice(0, 300)}`);
      }
      const st = await live.call("mc_status", {}, 3);
      const stData = JSON.parse(st.text);
      if (st.result.isError === true || stData.worldLoaded !== true) {
        throw new Error(`live mc_status after create must report worldLoaded=true: ${st.text.slice(0, 200)}`);
      }
      console.log(`LIVE mc_world_create ok: spawn=${JSON.stringify(wc.spawn)} time_locked=${wc.time_locked}`);
    }
  } catch (e) {
    liveFailure = e;
  }
  if (createdOk) {
    const del = await live.call("mc_world_delete", { name: LIVE_WORLD, force: true }, 4, 120000);
    console.log("LIVE mc_world_delete ->", del.text.slice(0, 200));
    const delData = del.result.isError === true ? null : JSON.parse(del.text);
    if (del.result.isError === true || delData?.deleted !== true) {
      liveFailure ??= new Error(`live mc_world_delete failed: ${del.text.slice(0, 300)}`);
    } else {
      console.log("LIVE mc_world_delete ok");
    }
  }
  live.stop();
  if (liveFailure) throw liveFailure;
}
