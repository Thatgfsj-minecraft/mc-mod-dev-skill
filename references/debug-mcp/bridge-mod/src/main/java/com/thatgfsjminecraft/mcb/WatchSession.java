package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MCB v1 §7 — one watch session: configuration, baseline (t0), per-tick rule
 * evaluation, report generation and the {@code watch.await} monitor.
 *
 * <p>Every world read is delegated to {@code WatchSession.onServerTick(...)},
 * which {@link WatchEngine} only calls from inside {@code server.execute(...)}
 * (§7.10.1). {@code watch.await} waits on this object's monitor from the IO
 * pool; the server thread only briefly {@code notifyAll()}s when a report is
 * appended, so the game thread is never blocked by a consumer (§6, §7.4).
 *
 * <p>Lifecycle (§7.9): {@code close(reason)} releases the baseline samples
 * immediately, keeps up to 64 reports for the 60 s retention window, and wakes
 * every waiter. The engine owns the registry and the 60 s eviction.
 */
final class WatchSession {
    /** §7.4: reports buffered per session (gap visibility on eviction). */
    static final int REPORT_BUFFER = 64;
    /** Bound on the removed-entity history used by entity_removed conditions. */
    private static final int REMOVED_HISTORY_LIMIT = 1024;

    // ------------------------------------------------------------------ immutable config
    final String watchId;
    final WatchSnapshot.Bounds bounds;
    final int[] origin;                 // nullable
    final WatchSnapshot.Tracked tracked;
    final WatchRules.Parsed rules;
    final String reportMode;
    final int detailLimit;
    final int maxEntityCount;
    final Object owner;
    final ResourceKey<Level> levelKey;
    final long startTick;
    final long startLogId;
    final long createdMs;
    final boolean perTickBlocks;
    final boolean perTickEntities;

    // ------------------------------------------------------------------ runtime (server thread unless noted)
    private WatchSnapshot.Sample t0;
    private WatchSnapshot.Sample current;
    private WatchSnapshot.Sample prev;
    private WatchSnapshot.Sample lastReportBase;
    private final List<WatchSnapshot.EntityEntry> removedHistory = new ArrayList<>();
    private Map<Long, BlockState> t0Lookup; // lazy, only for block conditions
    private long currentTick;
    private boolean trackedChangeThisTick;
    private boolean rootHandled;
    private boolean timelineMode;

    // timeline accumulator (§7.3.2)
    private final List<JsonObject> timelineEvents = new ArrayList<>();
    private final Map<String, Integer> timelineCounts = new LinkedHashMap<>();
    private long timelineTotal;

    // reports: guarded by this
    private final ArrayDeque<JsonObject> reports = new ArrayDeque<>();
    private int highestSeq;
    volatile long lastActivityMs;
    private volatile long lastReportMs;

    // close state: guarded by this
    private volatile boolean closed;
    private String closedReason;
    private long closedAtMs;

    WatchSession(Config cfg, Object owner, ResourceKey<Level> levelKey,
                 long startTick, long startLogId, WatchSnapshot.Sample t0) {
        this.watchId = cfg.watchId;
        this.bounds = cfg.bounds;
        this.origin = cfg.origin;
        this.tracked = cfg.tracked;
        this.rules = cfg.rules;
        this.reportMode = cfg.reportMode;
        this.detailLimit = cfg.detailLimit;
        this.maxEntityCount = cfg.maxEntityCount;
        this.owner = owner;
        this.levelKey = levelKey;
        this.startTick = startTick;
        this.startLogId = startLogId;
        this.createdMs = System.currentTimeMillis();
        this.lastActivityMs = createdMs;
        this.lastReportMs = createdMs;
        this.currentTick = startTick;
        this.t0 = t0;
        this.lastReportBase = t0;
        this.prev = t0;
        this.timelineMode = reportMode.equals("timeline_events");
        this.perTickBlocks = timelineMode || containsType(rules.root, "settle_quiet");
        this.perTickEntities = timelineMode
                || containsType(rules.root, "settle_quiet")
                || containsEntityCondition(rules.root);
    }

    // =====================================================================
    // Configuration parsing (TCP thread — no world access)
    // =====================================================================
    static final class Config {
        String watchId;
        WatchSnapshot.Bounds bounds;
        int[] origin;
        String dimensionId;   // nullable => player's current dimension
        WatchSnapshot.Tracked tracked;
        WatchRules.Parsed rules;
        String reportMode = "summary_diff";
        int detailLimit = 20;
        int maxEntityCount = 256;
    }

    /** §7.1: validate every param immediately (BAD_PARAMS / TOO_LARGE / RULE_INVALID). */
    static Config parse(JsonObject p) {
        Config cfg = new Config();
        if (!p.has("watch_id") || !p.get("watch_id").isJsonPrimitive()
                || !p.get("watch_id").getAsJsonPrimitive().isString()) {
            throw new McbServer.OpException("BAD_PARAMS", "watch_id must be a string");
        }
        String watchId = p.get("watch_id").getAsString();
        if (watchId.indexOf('.') >= 0) {
            throw new McbServer.OpException("BAD_PARAMS", "watch_id must not contain '.' (reserved for protocol namespaces)");
        }
        if (!watchId.matches("^[A-Za-z0-9_-]{1,64}$")) {
            throw new McbServer.OpException("BAD_PARAMS", "watch_id must match ^[A-Za-z0-9_-]{1,64}$");
        }
        cfg.watchId = watchId;

        if (!p.has("min") || !p.has("max")) {
            throw new McbServer.OpException("BAD_PARAMS", "min and max are required");
        }
        int[] min = McbHandlers.parseVec3(p.get("min"), "min");
        int[] max = McbHandlers.parseVec3(p.get("max"), "max");
        for (int i = 0; i < 3; i++) { // §7.1: per-axis normalization
            int lo = Math.min(min[i], max[i]);
            int hi = Math.max(min[i], max[i]);
            min[i] = lo;
            max[i] = hi;
        }
        cfg.bounds = new WatchSnapshot.Bounds(min, max);
        if (cfg.bounds.volume() > WatchSnapshot.VOLUME_LIMIT) {
            throw new McbServer.OpException("TOO_LARGE",
                    "region volume " + cfg.bounds.volume() + " exceeds limit " + WatchSnapshot.VOLUME_LIMIT);
        }
        if (p.has("origin") && !p.get("origin").isJsonNull()) {
            cfg.origin = McbHandlers.parseVec3(p.get("origin"), "origin");
        }
        if (p.has("dimension") && !p.get("dimension").isJsonNull()) {
            if (!p.get("dimension").isJsonPrimitive() || !p.get("dimension").getAsJsonPrimitive().isString()) {
                throw new McbServer.OpException("BAD_PARAMS", "dimension must be a string");
            }
            cfg.dimensionId = p.get("dimension").getAsString();
            if (Identifier.tryParse(cfg.dimensionId) == null) {
                throw new McbServer.OpException("BAD_PARAMS", "dimension is not a valid identifier");
            }
        }

        cfg.tracked = parseTracks(p);
        cfg.detailLimit = intParam(p, "detail_limit", 20, 1, WatchSnapshot.DETAIL_LIMIT_MAX);
        cfg.maxEntityCount = intParam(p, "max_entity_count", 256, 1, WatchSnapshot.MAX_ENTITY_COUNT);
        if (p.has("report_mode") && !p.get("report_mode").isJsonNull()) {
            if (!p.get("report_mode").isJsonPrimitive() || !p.get("report_mode").getAsJsonPrimitive().isString()) {
                throw new McbServer.OpException("BAD_PARAMS", "report_mode must be a string");
            }
            String mode = p.get("report_mode").getAsString();
            if (!mode.equals("summary_diff") && !mode.equals("timeline_events") && !mode.equals("snapshot")) {
                throw new McbServer.OpException("BAD_PARAMS",
                        "report_mode must be summary_diff, timeline_events or snapshot");
            }
            cfg.reportMode = mode;
        }
        if (!p.has("upload_rule") || !p.get("upload_rule").isJsonObject()) {
            throw new McbServer.OpException("RULE_INVALID", "upload_rule is required and must be an object");
        }
        cfg.rules = WatchRules.parse(p.getAsJsonObject("upload_rule"));
        return cfg;
    }

    private static WatchSnapshot.Tracked parseTracks(JsonObject p) {
        WatchSnapshot.Tracked t = new WatchSnapshot.Tracked();
        JsonObject tracks = objectOrNull(p, "tracks", "BAD_PARAMS");
        if (tracks == null) return t;
        JsonObject blocks = objectOrNull(tracks, "blocks", "BAD_PARAMS");
        if (blocks != null) {
            t.blocksEnabled = bool(blocks, "enabled", true, "BAD_PARAMS");
            t.ignoreAirToAir = bool(blocks, "ignore_air_to_air", true, "BAD_PARAMS");
            t.includeNbt = bool(blocks, "include_nbt", false, "BAD_PARAMS");
            if (blocks.has("filter") && !blocks.get("filter").isJsonNull()) {
                t.blockFilter = WatchSnapshot.parseBlockFilter(
                        McbHandlers.parseStringList(blocks.get("filter"), "filter"));
            }
        }
        JsonObject entities = objectOrNull(tracks, "entities", "BAD_PARAMS");
        if (entities != null) {
            t.entitiesEnabled = bool(entities, "enabled", true, "BAD_PARAMS");
            t.excludePlayers = bool(entities, "exclude_players", false, "BAD_PARAMS");
            if (entities.has("type_filter") && !entities.get("type_filter").isJsonNull()) {
                t.entityFilter = WatchSnapshot.parseEntityFilter(
                        McbHandlers.parseStringList(entities.get("type_filter"), "type_filter"));
            }
            if (entities.has("fields") && !entities.get("fields").isJsonNull()) {
                List<String> fields = McbHandlers.parseStringList(entities.get("fields"), "fields");
                t.wantPos = fields.contains("pos");
                t.wantHp = fields.contains("hp");
                t.wantAlive = fields.contains("alive");
                t.wantName = fields.contains("name");
                for (String f : fields) {
                    if (!f.equals("pos") && !f.equals("hp") && !f.equals("alive") && !f.equals("name")) {
                        throw new McbServer.OpException("BAD_PARAMS",
                                "tracks.entities.fields may only contain pos / hp / alive / name");
                    }
                }
            }
            t.posEpsilon = doubleParam(entities, "pos_epsilon", 0.25, 0.0, 16.0);
            t.hpEpsilon = doubleParam(entities, "hp_epsilon", 0.5, 0.0, 1000.0);
        }
        return t;
    }

    private static JsonObject objectOrNull(JsonObject p, String key, String code) {
        if (!p.has(key) || p.get(key).isJsonNull()) return null;
        if (!p.get(key).isJsonObject()) {
            throw new McbServer.OpException(code, key + " must be an object");
        }
        return p.getAsJsonObject(key);
    }

    private static boolean bool(JsonObject p, String key, boolean dflt, String code) {
        if (!p.has(key) || p.get(key).isJsonNull()) return dflt;
        JsonElement el = p.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            throw new McbServer.OpException(code, key + " must be a boolean");
        }
        return el.getAsBoolean();
    }

    private static int intParam(JsonObject p, String key, int dflt, int min, int max) {
        if (!p.has(key) || p.get(key).isJsonNull()) return dflt;
        JsonElement el = p.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be an integer");
        }
        int v = el.getAsInt();
        if (v < min || v > max) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be within " + min + ".." + max);
        }
        return v;
    }

    private static double doubleParam(JsonObject p, String key, double dflt, double min, double max) {
        if (!p.has(key) || p.get(key).isJsonNull()) return dflt;
        JsonElement el = p.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be a number");
        }
        double v = el.getAsDouble();
        if (Double.isNaN(v) || v < min || v > max) {
            throw new McbServer.OpException("BAD_PARAMS", key + " must be within " + min + ".." + max);
        }
        return v;
    }

    // ------------------------------------------------------------------ rule tree introspection
    private static boolean containsType(WatchRules.Node node, String type) {
        if (node == null) return false;
        if (node.type.equals(type)) return true;
        if (node instanceof WatchRules.AnyNode n) {
            for (WatchRules.Node c : n.children) if (containsType(c, type)) return true;
        } else if (node instanceof WatchRules.AllNode n) {
            for (WatchRules.Node c : n.children) if (containsType(c, type)) return true;
        } else if (node instanceof WatchRules.SequenceNode n) {
            for (WatchRules.Node c : n.steps) if (containsType(c, type)) return true;
        } else if (node instanceof WatchRules.RepeatNode n) {
            if (containsType(n.inner, type)) return true;
            if (containsType(n.until, type)) return true;
        }
        return false;
    }

    private static boolean containsEntityCondition(WatchRules.Node node) {
        if (node == null) return false;
        if (node instanceof WatchRules.LeafNode leaf && leaf.condition != null) {
            return leaf.condition.kind.equals("entity_removed")
                    || leaf.condition.kind.equals("entity_hp_below");
        }
        if (node instanceof WatchRules.AnyNode n) {
            for (WatchRules.Node c : n.children) if (containsEntityCondition(c)) return true;
        } else if (node instanceof WatchRules.AllNode n) {
            for (WatchRules.Node c : n.children) if (containsEntityCondition(c)) return true;
        } else if (node instanceof WatchRules.SequenceNode n) {
            for (WatchRules.Node c : n.steps) if (containsEntityCondition(c)) return true;
        } else if (node instanceof WatchRules.RepeatNode n) {
            if (containsEntityCondition(n.inner)) return true;
            if (containsEntityCondition(n.until)) return true;
        }
        return false;
    }

    // =====================================================================
    // Per-tick evaluation — SERVER THREAD ONLY (§7.10.1)
    // =====================================================================
    void onServerTick(ServerLevel level, long tick) {
        if (closed) return;
        currentTick = tick;
        boolean repeating = rules.root.type.equals("interval") || rules.root.type.equals("repeat");
        if (rootHandled && !repeating) return;

        if (perTickBlocks || perTickEntities) {
            boolean wantBlocks = perTickBlocks && tracked.blocksEnabled;
            boolean wantEntities = perTickEntities && tracked.entitiesEnabled;
            WatchSnapshot.Sample fresh = WatchSnapshot.capture(level, bounds, tracked, origin,
                    maxEntityCount, wantBlocks, wantEntities);
            WatchSnapshot.EntityDelta delta = null;
            trackedChangeThisTick = false;
            if (prev != null) {
                if (wantBlocks && WatchSnapshot.anyBlockChange(prev, fresh, tracked)) {
                    trackedChangeThisTick = true;
                }
                if (wantEntities) {
                    delta = WatchSnapshot.diffEntities(prev, fresh, level, tracked);
                    if (delta.anyChange()) trackedChangeThisTick = true;
                }
            }
            // Timeline ordering within a tick: block changes first, then entity
            // changes (both deterministic / sorted); see §7.3.2.
            if (wantBlocks && prev != null) processBlockDelta(prev, fresh, tick);
            if (delta != null) processEntityDelta(delta, tick);
            prev = fresh;
            current = fresh;
        } else {
            current = null;
            trackedChangeThisTick = false;
        }
        if (closed) return; // a concurrent lifecycle stop won the race

        WatchRules.Ctx ctx = new CtxImpl(level, tick);
        WatchRules.Res r = rules.root.eval(ctx);
        if (r.fired) {
            WatchSnapshot.Sample sampleNow = current != null ? current : fullCapture(level);
            if (!repeating) {
                emitReport(r.path, r.trace, r.timedOut, level, tick, sampleNow);
                rootHandled = true;
                if (rules.autoStop) {
                    WatchEngine.stopSession(this, "auto_stop");
                }
            } else if (rules.root.type.equals("repeat")) {
                emitReport(r.path, r.trace, r.timedOut, level, tick, sampleNow);
                if (timelineMode) resetTimeline();
                WatchRules.RepeatNode rep = (WatchRules.RepeatNode) rules.root;
                if (rep.exhausted || r.timedOut || rules.autoStop) {
                    WatchEngine.stopSession(this, "auto_stop");
                }
            } else { // interval
                emitReport(r.path, r.trace, r.timedOut, level, tick, sampleNow);
                lastReportBase = sampleNow;
                if (timelineMode) resetTimeline();
                WatchRules.LeafNode leaf = (WatchRules.LeafNode) rules.root;
                if (rules.autoStop || (leaf.maxCount > 0 && leaf.intervalCount >= leaf.maxCount)) {
                    WatchEngine.stopSession(this, "auto_stop");
                }
            }
        } else if (rules.root.type.equals("repeat")) {
            WatchRules.RepeatNode rep = (WatchRules.RepeatNode) rules.root;
            if (rep.exhausted) {
                // until matched: end the session, the last report stays final.
                WatchEngine.stopSession(this, "auto_stop");
            }
        }
    }

    private WatchSnapshot.Sample fullCapture(ServerLevel level) {
        return WatchSnapshot.capture(level, bounds, tracked, origin, maxEntityCount,
                tracked.blocksEnabled, tracked.entitiesEnabled);
    }

    // =====================================================================
    // Timeline accumulation (§7.3.2)
    // =====================================================================
    private void processBlockDelta(WatchSnapshot.Sample before, WatchSnapshot.Sample after, long tick) {
        if (!timelineMode) return;
        for (WatchSnapshot.BlockChange c : WatchSnapshot.diffBlocks(before, after, tracked)) {
            JsonObject e = new JsonObject();
            e.addProperty("tick", tick);
            e.addProperty("type", "block_changed");
            e.add("pos", WatchSnapshot.intArray(c.x(), c.y(), c.z()));
            e.addProperty("from", c.fromId());
            e.addProperty("to", c.toId());
            addTimelineEvent(e, "block_changed");
        }
    }

    private void processEntityDelta(WatchSnapshot.EntityDelta d, long tick) {
        for (WatchSnapshot.EntityEntry e : d.removed) removedHistory.add(e);
        if (removedHistory.size() > REMOVED_HISTORY_LIMIT) {
            removedHistory.subList(0, removedHistory.size() - REMOVED_HISTORY_LIMIT).clear();
        }
        if (!timelineMode) return;
        List<WatchSnapshot.EntityEntry> entered = new ArrayList<>(d.entered);
        entered.sort(java.util.Comparator.comparing(x -> x.uuid.toString()));
        for (WatchSnapshot.EntityEntry e : entered) {
            JsonObject o = new JsonObject();
            o.addProperty("tick", tick);
            o.addProperty("type", "entity_entered");
            o.addProperty("uuid", e.uuid.toString());
            o.addProperty("entity_type", e.typeId);
            o.add("pos", WatchSnapshot.doubleArray(e.x, e.y, e.z));
            addTimelineEvent(o, "entity_entered");
        }
        List<Object[]> hps = new ArrayList<>(d.hpPairs);
        hps.sort(java.util.Comparator.comparing(x -> ((WatchSnapshot.EntityEntry) x[0]).uuid.toString()));
        for (Object[] pair : hps) {
            WatchSnapshot.EntityEntry e = (WatchSnapshot.EntityEntry) pair[0];
            JsonObject o = new JsonObject();
            o.addProperty("tick", tick);
            o.addProperty("type", "entity_hp");
            o.addProperty("uuid", e.uuid.toString());
            o.addProperty("entity_type", e.typeId);
            o.addProperty("from", (Double) pair[1]);
            o.addProperty("to", (Double) pair[2]);
            addTimelineEvent(o, "entity_hp");
        }
        List<WatchSnapshot.EntityEntry> removedSorted = new ArrayList<>(d.removed);
        removedSorted.sort(java.util.Comparator.comparing(x -> x.uuid.toString()));
        for (WatchSnapshot.EntityEntry e : removedSorted) {
            JsonObject o = new JsonObject();
            o.addProperty("tick", tick);
            o.addProperty("type", "entity_removed");
            o.addProperty("uuid", e.uuid.toString());
            o.addProperty("entity_type", e.typeId);
            String reason = d.removedReasons.get(d.removed.indexOf(e));
            o.addProperty("reason", reason);
            addTimelineEvent(o, "entity_removed");
        }
        List<WatchSnapshot.EntityEntry> moved = new ArrayList<>(d.moved);
        moved.sort(java.util.Comparator.comparing(x -> x.uuid.toString()));
        for (WatchSnapshot.EntityEntry e : moved) {
            double[] from = d.movedFrom.get(e.uuid);
            JsonObject o = new JsonObject();
            o.addProperty("tick", tick);
            o.addProperty("type", "entity_moved");
            o.addProperty("uuid", e.uuid.toString());
            o.addProperty("entity_type", e.typeId);
            if (from != null) o.add("from", WatchSnapshot.doubleArray(from[0], from[1], from[2]));
            o.add("to", WatchSnapshot.doubleArray(e.x, e.y, e.z));
            addTimelineEvent(o, "entity_moved");
        }
    }

    private void addTimelineEvent(JsonObject e, String kind) {
        timelineTotal++;
        timelineCounts.merge(kind, 1, Integer::sum);
        if (timelineEvents.size() < detailLimit) timelineEvents.add(e);
    }

    private void resetTimeline() {
        timelineEvents.clear();
        timelineTotal = 0;
        timelineCounts.clear();
    }

    // =====================================================================
    // Report generation (§7.3)
    // =====================================================================
    private void emitReport(String reason, List<String> trace, boolean timedOut,
                            ServerLevel level, long tick, WatchSnapshot.Sample sampleNow) {
        JsonObject report;
        synchronized (this) {
            if (closed) return; // could have been stopped by the lifecycle sweep
            highestSeq++;
            // §7.2.1: interval reports are incremental since the last report;
            // every other rule compares against the t0 baseline.
            WatchSnapshot.Sample base = rules.root.type.equals("interval") ? lastReportBase : t0;
            if (base == null) base = sampleNow;
            report = buildReport(reason, trace, timedOut, tick, level, sampleNow, highestSeq, base);
            reports.addLast(report);
            while (reports.size() > REPORT_BUFFER) reports.pollFirst();
            lastReportMs = System.currentTimeMillis();
            lastActivityMs = lastReportMs;
            notifyAll();
        }
        WatchEngine.broadcastTriggered(this, report);
    }

    /** §7.5 watch.snapshot: same shape as a rule report, seq = latest, not buffered. */
    JsonObject buildSnapshotReport(ServerLevel level, long tick) {
        WatchSnapshot.Sample baseline;
        synchronized (this) {
            if (closed || t0 == null) {
                throw new McbServer.OpException("WATCH_NOT_FOUND",
                        "watch session baseline already released: " + watchId);
            }
            baseline = t0;
        }
        WatchSnapshot.Sample sampleNow = fullCapture(level);
        // §7.5: watch.snapshot is always "t1 vs t0", never t1 vs last report.
        return buildReport("snapshot", List.of("snapshot"), false, tick, level, sampleNow, highestSeq, baseline);
    }

    private JsonObject buildReport(String reason, List<String> trace, boolean timedOut,
                                   long tick, ServerLevel level, WatchSnapshot.Sample sampleNow,
                                   int seq, WatchSnapshot.Sample summaryBase) {
        JsonObject o = new JsonObject();
        o.addProperty("watch_id", watchId);
        o.addProperty("seq", seq);
        o.addProperty("report_mode", reportMode);
        o.addProperty("trigger_reason", reason);
        com.google.gson.JsonArray traceArr = new com.google.gson.JsonArray();
        for (String s : trace) traceArr.add(s);
        o.add("trigger_trace", traceArr);
        o.addProperty("elapsed_ticks", tick - startTick);
        o.addProperty("side", "server");
        if (timedOut) o.addProperty("timed_out", true);

        boolean truncated = false;
        int truncatedCount = 0;
        switch (reportMode) {
            case "timeline_events": {
                com.google.gson.JsonArray events = new com.google.gson.JsonArray();
                for (JsonObject e : timelineEvents) events.add(e);
                o.add("events", events);
                JsonObject counts = new JsonObject();
                for (Map.Entry<String, Integer> e : timelineCounts.entrySet()) {
                    counts.addProperty(e.getKey(), e.getValue());
                }
                o.add("counts", counts);
                truncatedCount = (int) Math.max(0, timelineTotal - timelineEvents.size());
                truncated = truncatedCount > 0;
                break;
            }
            case "snapshot": {
                WatchSnapshot.Section blocks = WatchSnapshot.snapshotBlocks(sampleNow, level, tracked, detailLimit);
                WatchSnapshot.Section ents = WatchSnapshot.snapshotEntities(sampleNow, tracked, detailLimit);
                mergeSection(o, blocks);
                mergeSection(o, ents);
                truncated = blocks.truncated || ents.truncated;
                truncatedCount = blocks.truncatedCount + ents.truncatedCount;
                break;
            }
            default: { // summary_diff
                WatchSnapshot.Sample base = summaryBase != null ? summaryBase : sampleNow;
                WatchSnapshot.Section blockSection = WatchSnapshot.blockSummary(base, sampleNow, tracked, origin, detailLimit);
                WatchSnapshot.Section entitySection = WatchSnapshot.entitySummary(base, sampleNow, level, tracked, detailLimit);
                mergeSection(o, blockSection);
                mergeSection(o, entitySection);
                truncated = blockSection.truncated || entitySection.truncated;
                truncatedCount = blockSection.truncatedCount + entitySection.truncatedCount;
                break;
            }
        }
        o.addProperty("truncated", truncated);
        o.addProperty("truncated_count", truncatedCount);
        return o;
    }

    private static void mergeSection(JsonObject report, WatchSnapshot.Section section) {
        for (Map.Entry<String, JsonElement> e : section.json.entrySet()) {
            report.add(e.getKey(), e.getValue());
        }
    }

    // =====================================================================
    // watch.await — IO thread, never the game thread (§7.4)
    // =====================================================================
    JsonObject awaitData(long afterSeq, long waitMs) {
        lastActivityMs = System.currentTimeMillis();
        long deadline = System.currentTimeMillis() + waitMs;
        synchronized (this) {
            while (true) {
                JsonObject found = firstReportAfter(afterSeq);
                boolean overflowed = overflowed(afterSeq);
                // §7.4: next_seq is the latest raw seq in the buffer — always,
                // even when there is no match. A consumer cursor that is ahead
                // of the session gets the real latest seq back so it can align
                // instead of drifting forever.
                int next = highestSeq;
                if (found != null) {
                    return awaitResponse(true, next, overflowed, found);
                }
                // A closed session will never produce another report: return
                // immediately instead of burning the full wait_ms budget.
                if (closed) {
                    return awaitResponse(false, next, overflowed, null);
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return awaitResponse(false, next, overflowed, null);
                }
                try {
                    wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return awaitResponse(false, next, overflowed, null);
                }
            }
        }
    }

    private static JsonObject awaitResponse(boolean matched, int nextSeq, boolean overflowed, JsonObject report) {
        JsonObject o = new JsonObject();
        o.addProperty("matched", matched);
        o.addProperty("next_seq", nextSeq);
        o.addProperty("overflowed", overflowed);
        if (matched) o.add("report", report);
        return o;
    }

    private JsonObject firstReportAfter(long afterSeq) {
        for (JsonObject r : reports) {
            if (r.get("seq").getAsLong() > afterSeq) return r;
        }
        return null;
    }

    private boolean overflowed(long afterSeq) {
        long oldest;
        if (reports.isEmpty()) {
            if (highestSeq == 0) return false;
            // Defensive: reports exist per highestSeq but the buffer is empty
            // (should not happen — eviction keeps the newest 64). Treat the
            // gap as starting right after the newest report.
            oldest = (long) highestSeq + 1;
        } else {
            oldest = reports.peekFirst().get("seq").getAsLong();
        }
        return oldest > afterSeq + 1;
    }

    long highestSeq() {
        synchronized (this) {
            return highestSeq;
        }
    }

    long lastReportMs() { return lastReportMs; }

    // =====================================================================
    // Lifecycle (§7.7 / §7.9)
    // =====================================================================
    /**
     * Mark the session closed. NOTHING that the server thread may still be
     * reading is cleared here — sample release is a separate step
     * ({@link #releaseSnapshot()}) so a stop from the TCP/timer thread can
     * never null a sample under a running tick.
     */
    void close(String reason) {
        synchronized (this) {
            if (closed) return;
            markClosed(reason);
        }
    }

    /**
     * §7.7 watch.stop: atomically mark the session closed and capture the
     * highest-seq report. Closing first guarantees a concurrent server-tick
     * {@code emitReport} either lands in the captured report or is skipped —
     * no "report exists but final_report missed it" race.
     */
    JsonObject closeForStop(String reason) {
        synchronized (this) {
            if (!closed) markClosed(reason);
            return reports.isEmpty() ? null : reports.peekLast();
        }
    }

    private void markClosed(String reason) {
        closed = true;
        closedReason = reason;
        closedAtMs = System.currentTimeMillis();
        notifyAll();
    }

    /**
     * §7.9: release the baseline snapshot / live samples. Runs on the
     * integrated-server thread when one is alive (see WatchEngine); all reads
     * of these fields are null-tolerant and gated by the volatile
     * {@link #closed} flag, so the fallback direct call is safe too.
     */
    void releaseSnapshot() {
        synchronized (this) {
            t0 = null;
            prev = null;
            current = null;
            lastReportBase = null;
            t0Lookup = null;
            removedHistory.clear();
            timelineEvents.clear();
            timelineCounts.clear();
            timelineTotal = 0;
        }
    }

    boolean isClosed() { return closed; }

    String closedReason() { return closedReason; }

    long closedAtMs() { return closedAtMs; }

    /** §7.7: highest-seq buffered report, or null. */
    JsonObject finalReport() {
        synchronized (this) {
            return reports.isEmpty() ? null : reports.peekLast();
        }
    }

    int bufferedReports() {
        synchronized (this) {
            return reports.size();
        }
    }

    /** §7.6 watch.list entry (active sessions only). */
    JsonObject metadata(long serverTick) {
        JsonObject o = new JsonObject();
        o.addProperty("watch_id", watchId);
        o.add("bounds", bounds.toJson());
        o.addProperty("dimension", dimensionName());
        long elapsed = serverTick >= 0 ? serverTick - startTick : 0L;
        o.addProperty("elapsed_ticks", Math.max(0, elapsed));
        o.addProperty("rule_type", rules.root.type);
        o.addProperty("report_mode", reportMode);
        o.addProperty("auto_stop", rules.autoStop);
        o.addProperty("buffered_reports", bufferedReports());
        o.addProperty("seq", highestSeq());
        o.addProperty("phase", isClosed() ? "completed" : "running");
        return o;
    }

    String dimensionName() {
        Identifier id = levelKey.identifier();
        return id.toString();
    }

    // =====================================================================
    // Rule evaluation context
    // =====================================================================
    private final class CtxImpl implements WatchRules.Ctx {
        private final ServerLevel level;
        private final long tick;

        CtxImpl(ServerLevel level, long tick) {
            this.level = level;
            this.tick = tick;
        }

        @Override public long tick() { return tick; }
        @Override public long startTick() { return startTick; }
        @Override public long startLogId() { return startLogId; }
        @Override public ServerLevel level() { return level; }
        @Override public WatchSnapshot.Sample t0() { return t0; }
        @Override public WatchSnapshot.Sample current() { return current; }
        @Override public boolean blocksTracked() { return tracked.blocksEnabled; }
        @Override public boolean entitiesTracked() { return tracked.entitiesEnabled; }
        @Override public boolean trackedChangeThisTick() { return trackedChangeThisTick; }
        @Override public List<WatchSnapshot.EntityEntry> removedHistory() { return removedHistory; }

        @Override public java.util.Collection<WatchSnapshot.EntityEntry> currentEntities() {
            return current != null ? current.entities.values() : List.of();
        }

        @Override public java.util.Collection<WatchSnapshot.EntityEntry> baselineEntities() {
            return t0 != null ? t0.entities.values() : List.of();
        }

        @Override public String blockIdAt(int[] pos) {
            BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
            if (!level.isLoaded(bp)) return null; // §7.10.2: never force-load
            return BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString();
        }

        @Override public String blockPropertyAt(int[] pos, String property) {
            BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
            if (!level.isLoaded(bp)) return null;
            return WatchRules.LeafNode.propertyValue(level.getBlockState(bp), property);
        }

        @Override public BlockState baselineBlock(int[] pos) {
            if (t0 == null) return null;
            if (t0Lookup == null) {
                t0Lookup = new HashMap<>(Math.max(16, t0.count * 2));
                for (int i = 0; i < t0.count; i++) t0Lookup.put(t0.keys[i], t0.states[i]);
            }
            return t0Lookup.get(new BlockPos(pos[0], pos[1], pos[2]).asLong());
        }

        @Override public WatchRules.LogScan scanLog(long afterId, Pattern pattern, org.apache.logging.log4j.Level minLevel) {
            McbLogCapture.Snapshot snap = McbLogCapture.readAfter(afterId, minLevel, 1000);
            boolean matched = false;
            for (McbLogCapture.Entry e : snap.entries) {
                if (pattern == null || pattern.matcher(e.message).find()) {
                    matched = true;
                    break;
                }
            }
            return new WatchRules.LogScan(matched, Math.max(afterId, snap.nextId));
        }
    }

    /** Called by the engine tick pump to keep watch.list's elapsed_ticks fresh. */
    void noteTick(long tick) {
        this.currentTick = tick;
    }

    long currentTick() { return currentTick; }

    /** owner identity check for watch.stop (§7.7). */
    boolean isOwnedBy(Object token) { return owner == token; }
}
