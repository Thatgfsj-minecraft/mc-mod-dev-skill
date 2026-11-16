package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.apache.logging.log4j.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MCB v1 §7.2 — uploaded rule tree: parsing, strict validation and the per-tick
 * state machine.
 *
 * <p>Parsing happens on the TCP thread ({@link #parse}) so structural errors are
 * answered immediately as {@code RULE_INVALID} without touching the world.
 * Evaluation happens on the server thread ({@link #eval}) once per server tick.
 *
 * <p>Timeouts are measured from the tick a node became active (the root is
 * activated when the session is created, a composite child when its parent
 * first evaluates it). A timed-out node yields a {@code "timeout"} path with
 * {@code timedOut = true} (§7.2.3); the session turns that into a report.
 *
 * <p>The state machine is deliberately side-effect free except for its own
 * latches. Report generation belongs to {@link WatchSession} — a trigger is
 * only a {@link Res}.
 */
final class WatchRules {
    private WatchRules() {}

    /** §7.2.2 nesting limits. */
    static final int MAX_DEPTH = 4;
    static final int MAX_NODES = 16;
    /** §7.2: serialized rule tree cap. */
    static final int MAX_RULE_BYTES = 16 * 1024;
    /** Server tick rate used to convert *_seconds into ticks. */
    static final double TICK_RATE = 20.0;

    static McbServer.OpException invalid(String message) {
        return new McbServer.OpException("RULE_INVALID", message);
    }

    static long secondsToTicks(double seconds) {
        return Math.max(1L, Math.round(seconds * TICK_RATE));
    }

    // =====================================================================
    // Trigger result
    // =====================================================================
    static final class Res {
        static final Res NONE = new Res(false, false, null, List.of());
        final boolean fired;
        final boolean timedOut;
        final String path;
        final List<String> trace;

        private Res(boolean fired, boolean timedOut, String path, List<String> trace) {
            this.fired = fired; this.timedOut = timedOut;
            this.path = path; this.trace = trace;
        }

        static Res fired(String path, List<String> trace) {
            List<String> t = trace == null || trace.isEmpty() ? List.of(path) : List.copyOf(trace);
            return new Res(true, false, path, t);
        }

        static Res timeout() {
            return new Res(true, true, "timeout", List.of("timeout"));
        }
    }

    // =====================================================================
    // Evaluation context — implemented by WatchSession (server thread only)
    // =====================================================================
    interface Ctx {
        /** Absolute integrated-server tick of the current evaluation. */
        long tick();
        /** Tick the session was created on (t0). */
        long startTick();
        /** Highest log id that existed at watch.start (event rules never replay older lines). */
        long startLogId();
        ServerLevel level();

        WatchSnapshot.Sample t0();
        /** Sample captured for this tick (may be null when the rule needs no per-tick diff). */
        WatchSnapshot.Sample current();

        boolean blocksTracked();
        boolean entitiesTracked();
        /** settle_quiet: did any tracked block/entity change since the previous tick? */
        boolean trackedChangeThisTick();

        /** Entities that were tracked and became removed at some point this session. */
        List<WatchSnapshot.EntityEntry> removedHistory();
        Collection<WatchSnapshot.EntityEntry> currentEntities();
        Collection<WatchSnapshot.EntityEntry> baselineEntities();

        /** Guarded current-block reads (§7.10.2: unloaded chunk -> null, never force-load). */
        String blockIdAt(int[] pos);
        String blockPropertyAt(int[] pos, String property);
        /** t0 block state at pos; null when the chunk was not loaded at t0. */
        BlockState baselineBlock(int[] pos);

        /** §7.2.1 event rule: scan buffered log lines after a cursor. */
        LogScan scanLog(long afterId, Pattern pattern, Level minLevel);
    }

    static final class LogScan {
        static final LogScan NONE = new LogScan(false, 0);
        final boolean matched;
        final long nextCursor;
        LogScan(boolean matched, long nextCursor) {
            this.matched = matched; this.nextCursor = nextCursor;
        }
    }

    // =====================================================================
    // Node base
    // =====================================================================
    abstract static class Node {
        final String type;
        final int depth;
        /** Tick the node first became active; -1 until then. */
        long activatedTick = -1;
        /** Whether a one-shot node already triggered. */
        boolean firedOnce;

        Node(String type, int depth) {
            this.type = type;
            this.depth = depth;
        }

        abstract Res eval(Ctx c);

        void reset() {
            activatedTick = -1;
            firedOnce = false;
        }

        void activate(Ctx c) {
            if (activatedTick < 0) activatedTick = c.tick();
        }

        /**
         * Bring a subtree online at an explicit tick. Needed when a parent
         * advances to its next child (sequence step / repeat round): the child
         * must count its own timeout from that moment, not from its first
         * evaluation one tick later.
         */
        void markSubtreeActivated(long tick) {
            if (activatedTick < 0) activatedTick = tick;
            markChildrenActivated(tick);
        }

        /** Overridden by composites; leaves have no children. */
        void markChildrenActivated(long tick) {
        }

        boolean deadlineExceeded(Ctx c, long timeoutTicks) {
            return c.tick() - activatedTick >= timeoutTicks;
        }
    }

    // =====================================================================
    // Leaf node
    // =====================================================================
    static final class LeafNode extends Node {
        // after_*
        long afterTicks;
        String afterReason;
        // settle_quiet
        int quietTicks;
        long maxWaitTicks;
        long lastChangeTick = -1;
        // on_condition
        Condition condition;
        long timeoutTicks;
        // interval
        long intervalTicks;
        int maxCount = -1;
        long nextIntervalTick = -1;
        int intervalCount;
        // event
        Pattern pattern;
        Level minLevel;
        long logCursor = -1;

        LeafNode(String type, int depth) {
            super(type, depth);
        }

        @Override
        void reset() {
            super.reset();
            firedOnce = false;
            lastChangeTick = -1;
            nextIntervalTick = -1;
            intervalCount = 0;
            // logCursor deliberately survives: a repeat round must not replay
            // log lines that were already consumed by an earlier round.
        }

        @Override
        Res eval(Ctx c) {
            activate(c);
            switch (type) {
                case "after_seconds":
                case "after_ticks": {
                    if (firedOnce) return Res.NONE;
                    if (c.tick() - activatedTick >= afterTicks) {
                        firedOnce = true;
                        return Res.fired(afterReason, List.of(afterReason));
                    }
                    return Res.NONE;
                }
                case "settle_quiet": {
                    if (firedOnce) return Res.NONE;
                    if (c.trackedChangeThisTick()) lastChangeTick = c.tick();
                    long since = lastChangeTick < 0 ? activatedTick : lastChangeTick;
                    if (c.tick() - since >= quietTicks) {
                        firedOnce = true;
                        return Res.fired("settle_quiet", List.of("settle_quiet"));
                    }
                    if (c.tick() - activatedTick >= maxWaitTicks) {
                        firedOnce = true;
                        return Res.timeout();
                    }
                    return Res.NONE;
                }
                case "on_condition": {
                    if (firedOnce) return Res.NONE;
                    if (conditionMet(c)) {
                        firedOnce = true;
                        String path = condition.describe();
                        return Res.fired(path, List.of(path));
                    }
                    if (deadlineExceeded(c, timeoutTicks)) {
                        firedOnce = true;
                        return Res.timeout();
                    }
                    return Res.NONE;
                }
                case "interval": {
                    if (nextIntervalTick < 0) {
                        nextIntervalTick = activatedTick + intervalTicks;
                    }
                    if (maxCount > 0 && intervalCount >= maxCount) return Res.NONE;
                    if (c.tick() >= nextIntervalTick) {
                        intervalCount++;
                        nextIntervalTick = c.tick() + intervalTicks;
                        String path = "interval(" + intervalTicks + ")#" + intervalCount;
                        return Res.fired(path, List.of(path));
                    }
                    return Res.NONE;
                }
                case "event": {
                    if (firedOnce) return Res.NONE;
                    if (logCursor < 0) logCursor = c.startLogId();
                    LogScan scan = c.scanLog(logCursor, pattern, minLevel);
                    logCursor = scan.nextCursor;
                    if (scan.matched) {
                        firedOnce = true;
                        String path = "event(log.line)";
                        return Res.fired(path, List.of(path));
                    }
                    if (deadlineExceeded(c, timeoutTicks)) {
                        firedOnce = true;
                        return Res.timeout();
                    }
                    return Res.NONE;
                }
                default:
                    throw new IllegalStateException("unknown leaf type " + type);
            }
        }

        private boolean conditionMet(Ctx c) {
            switch (condition.kind) {
                case "block_match": {
                    String current = c.blockIdAt(condition.pos);
                    if (current == null || !current.equals(condition.toBlock)) return false;
                    BlockState base = c.baselineBlock(condition.pos);
                    if (base == null) return true; // unknown t0 -> not "already there"
                    String baseId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(base.getBlock()).toString();
                    return !baseId.equals(condition.toBlock);
                }
                case "block_state": {
                    String current = c.blockPropertyAt(condition.pos, condition.property);
                    if (current == null || !current.equals(condition.value)) return false;
                    BlockState base = c.baselineBlock(condition.pos);
                    if (base == null) return true;
                    String baseValue = propertyValue(base, condition.property);
                    return baseValue == null || !baseValue.equals(condition.value);
                }
                case "entity_removed": {
                    for (WatchSnapshot.EntityEntry e : c.removedHistory()) {
                        if (condition.uuid != null && !condition.uuid.equals(e.uuid)) continue;
                        if (!condition.entityMatcher.matchesTypeId(e.typeId)) continue;
                        return true;
                    }
                    return false;
                }
                case "entity_hp_below": {
                    for (WatchSnapshot.EntityEntry e : c.currentEntities()) {
                        if (e.hp == null) continue;
                        if (condition.uuid != null && !condition.uuid.equals(e.uuid)) continue;
                        if (!condition.entityMatcher.matchesTypeId(e.typeId)
                                && !condition.entityMatcher.isEmpty()) {
                            continue;
                        }
                        if (e.hp < condition.threshold) return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }

        static String propertyValue(BlockState state, String property) {
            for (Property<?> p : state.getProperties()) {
                if (p.getName().equals(property)) {
                    return state.getValue(p).toString();
                }
            }
            return null;
        }
    }

    // =====================================================================
    // Condition descriptor (§7.2.1)
    // =====================================================================
    static final class Condition {
        final String kind;
        int[] pos;
        String toBlock;
        String property;
        String value;
        WatchSnapshot.EntityMatcher entityMatcher;
        UUID uuid;
        double threshold;

        Condition(String kind) { this.kind = kind; }

        String describe() {
            switch (kind) {
                case "block_match":
                    return "on_condition(block_match@" + posString() + "->" + toBlock + ")";
                case "block_state":
                    return "on_condition(block_state@" + posString() + "." + property + "=" + value + ")";
                case "entity_removed":
                    return "on_condition(entity_removed@" + typeDesc() + ")";
                case "entity_hp_below":
                    return "on_condition(entity_hp_below@" + typeDesc() + "<" + threshold + ")";
                default:
                    return "on_condition(" + kind + ")";
            }
        }

        private String posString() {
            return "[" + pos[0] + "," + pos[1] + "," + pos[2] + "]";
        }

        private String typeDesc() {
            if (uuid != null) return uuid.toString();
            return "any";
        }
    }

    // =====================================================================
    // Composite nodes
    // =====================================================================
    static final class AnyNode extends Node {
        final List<Node> children = new ArrayList<>();
        long timeoutTicks;

        AnyNode(int depth) { super("any", depth); }

        @Override
        void reset() {
            super.reset();
            for (Node n : children) n.reset();
        }

        @Override
        void markChildrenActivated(long tick) {
            for (Node child : children) child.markSubtreeActivated(tick);
        }

        @Override
        Res eval(Ctx c) {
            activate(c);
            for (int i = 0; i < children.size(); i++) {
                Res r = children.get(i).eval(c);
                if (r.fired) {
                    if (r.timedOut) return Res.timeout();
                    return Res.fired("any[" + i + "]:" + r.path, r.trace);
                }
            }
            if (deadlineExceeded(c, timeoutTicks)) return Res.timeout();
            return Res.NONE;
        }
    }

    static final class AllNode extends Node {
        final List<Node> children = new ArrayList<>();
        boolean simultaneous;
        long timeoutTicks;
        final List<Boolean> satisfied = new ArrayList<>();
        final List<String> trace = new ArrayList<>();

        AllNode(int depth) { super("all", depth); }

        @Override
        void markChildrenActivated(long tick) {
            for (Node child : children) child.markSubtreeActivated(tick);
        }

        @Override
        void reset() {
            super.reset();
            satisfied.clear();
            trace.clear();
            for (Node n : children) n.reset();
        }

        @Override
        Res eval(Ctx c) {
            activate(c);
            if (simultaneous) {
                boolean[] thisTick = new boolean[children.size()];
                boolean all = true;
                for (int i = 0; i < children.size(); i++) {
                    Res r = children.get(i).eval(c);
                    if (r.timedOut) return Res.timeout();
                    if (r.fired) {
                        thisTick[i] = true;
                    } else {
                        all = false;
                    }
                }
                if (all) {
                    List<String> t = new ArrayList<>();
                    t.add("all(simultaneous)");
                    for (int i = 0; i < children.size(); i++) {
                        t.add("all[" + i + "]");
                    }
                    return Res.fired("all(simultaneous)", t);
                }
                if (deadlineExceeded(c, timeoutTicks)) return Res.timeout();
                return Res.NONE;
            }
            // latched (default)
            for (int i = 0; i < children.size(); i++) {
                if (satisfied.size() > i && Boolean.TRUE.equals(satisfied.get(i))) continue;
                Res r = children.get(i).eval(c);
                if (r.timedOut) return Res.timeout();
                if (r.fired) {
                    while (satisfied.size() <= i) satisfied.add(Boolean.FALSE);
                    satisfied.set(i, Boolean.TRUE);
                    trace.add("all[" + i + "]:" + r.path);
                }
            }
            boolean all = satisfied.size() >= children.size();
            for (int i = 0; i < children.size() && all; i++) {
                all = satisfied.size() > i && Boolean.TRUE.equals(satisfied.get(i));
            }
            if (all) {
                List<String> t = new ArrayList<>();
                t.add("all(latched)");
                t.addAll(trace);
                return Res.fired("all(latched)", t);
            }
            if (deadlineExceeded(c, timeoutTicks)) return Res.timeout();
            return Res.NONE;
        }
    }

    static final class SequenceNode extends Node {
        final List<Node> steps = new ArrayList<>();
        long timeoutTicks;
        int active;
        final List<String> captured = new ArrayList<>();

        SequenceNode(int depth) { super("sequence", depth); }

        @Override
        void markChildrenActivated(long tick) {
            // Only the first step is active at sequence start; later steps are
            // brought online by the advance below.
            if (!steps.isEmpty()) steps.get(0).markSubtreeActivated(tick);
        }

        @Override
        void reset() {
            super.reset();
            active = 0;
            captured.clear();
            for (Node n : steps) n.reset();
        }

        @Override
        Res eval(Ctx c) {
            activate(c);
            if (active >= steps.size()) return Res.NONE;
            Res r = steps.get(active).eval(c);
            if (r.timedOut) return Res.timeout();
            if (r.fired) {
                captured.add("sequence[" + active + "]:" + r.path);
                active++;
                if (active < steps.size()) steps.get(active).markSubtreeActivated(c.tick());
            }
            if (active >= steps.size()) {
                String path = String.join(" -> ", captured);
                return Res.fired(path, captured);
            }
            if (deadlineExceeded(c, timeoutTicks)) return Res.timeout();
            return Res.NONE;
        }
    }

    static final class RepeatNode extends Node {
        Node inner;
        Node until;
        int maxCount = 1;
        long timeoutTicks;
        int round;
        boolean exhausted;

        RepeatNode(int depth) { super("repeat", depth); }

        @Override
        void markChildrenActivated(long tick) {
            if (inner != null) inner.markSubtreeActivated(tick);
            if (until != null) until.markSubtreeActivated(tick);
        }

        @Override
        void reset() {
            super.reset();
            round = 0;
            exhausted = false;
            if (inner != null) inner.reset();
        }

        @Override
        Res eval(Ctx c) {
            activate(c);
            if (exhausted) return Res.NONE;
            if (until != null) {
                Res u = until.eval(c);
                if (u.timedOut) return Res.timeout();
                if (u.fired) {
                    exhausted = true;
                    return Res.NONE; // round limit reached by until: stop, no extra report
                }
            }
            Res r = inner.eval(c);
            if (r.timedOut) return Res.timeout();
            if (r.fired) {
                round++;
                inner.reset();
                // The next round starts now, not on the next evaluation tick.
                inner.markSubtreeActivated(c.tick());
                if (maxCount > 0 && round >= maxCount) exhausted = true;
                String path = "repeat#" + round + ":" + r.path;
                return Res.fired(path, List.of(path));
            }
            if (deadlineExceeded(c, timeoutTicks)) return Res.timeout();
            return Res.NONE;
        }
    }

    // =====================================================================
    // Parsed rule tree
    // =====================================================================
    static final class Parsed {
        final Node root;
        final boolean autoStop;
        Parsed(Node root, boolean autoStop) {
            this.root = root;
            this.autoStop = autoStop;
        }
    }

    private static boolean defaultAutoStop(String type) {
        // §7.1/§7.2.1: interval and repeat survive; everything else is one-shot.
        return !type.equals("interval") && !type.equals("repeat");
    }

    static Parsed parse(JsonObject rule) {
        if (rule.toString().length() > MAX_RULE_BYTES) {
            throw invalid("serialized upload_rule exceeds " + MAX_RULE_BYTES + " bytes");
        }
        Parser p = new Parser();
        Node root = p.node(rule, 1, true);
        if (p.nodes > MAX_NODES) {
            throw invalid("rule tree has more than " + MAX_NODES + " nodes");
        }
        boolean autoStop = defaultAutoStop(root.type);
        if (rule.has("auto_stop")) {
            JsonElement el = rule.get("auto_stop");
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
                throw invalid("auto_stop must be a boolean");
            }
            autoStop = el.getAsBoolean();
        }
        return new Parsed(root, autoStop);
    }

    /** Recursive descent parser with depth/node accounting (§7.2.2). */
    private static final class Parser {
        int nodes;

        Node node(JsonObject o, int depth, boolean root) {
            if (depth > MAX_DEPTH) {
                throw invalid("rule nesting depth exceeds " + MAX_DEPTH);
            }
            nodes++;
            if (nodes > MAX_NODES) {
                throw invalid("rule tree has more than " + MAX_NODES + " nodes");
            }
            String type = str(o, "type", null);
            if (type == null) throw invalid("every rule node needs a string type");
            switch (type) {
                case "after_seconds": {
                    LeafNode n = new LeafNode(type, depth);
                    if (o.has("ticks")) throw invalid("after_seconds accepts seconds, not ticks");
                    double seconds = num(o, "seconds", Double.NaN);
                    if (Double.isNaN(seconds) || seconds <= 0 || seconds > 3600) {
                        throw invalid("after_seconds.seconds must be >0 and <=3600");
                    }
                    n.afterTicks = secondsToTicks(seconds);
                    n.afterReason = "after_seconds(" + trimSeconds(seconds) + ")";
                    return n;
                }
                case "after_ticks": {
                    LeafNode n = new LeafNode(type, depth);
                    if (o.has("seconds")) throw invalid("after_ticks accepts ticks, not seconds");
                    int ticks = integer(o, "ticks", -1, 1, 72000);
                    if (ticks < 0) throw invalid("after_ticks.ticks is required (1..72000)");
                    n.afterTicks = ticks;
                    n.afterReason = "after_ticks(" + ticks + ")";
                    return n;
                }
                case "settle_quiet": {
                    LeafNode n = new LeafNode(type, depth);
                    n.quietTicks = integer(o, "quiet_ticks", 20, 1, 1200);
                    n.maxWaitTicks = secondsToTicks(number(o, "max_wait_seconds", 30.0, 1.0, 300.0));
                    return n;
                }
                case "on_condition": {
                    LeafNode n = new LeafNode(type, depth);
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 30.0, 0.1, 300.0));
                    JsonObject cond = obj(o, "condition", true);
                    n.condition = condition(cond);
                    return n;
                }
                case "interval": {
                    LeafNode n = new LeafNode(type, depth);
                    n.intervalTicks = integer(o, "interval_ticks", 20, 1, 1200);
                    if (o.has("max_count")) n.maxCount = integer(o, "max_count", -1, 1, 1000);
                    return n;
                }
                case "event": {
                    LeafNode n = new LeafNode(type, depth);
                    String source = str(o, "event", null);
                    if (source == null) throw invalid("event rule needs an event source");
                    if (!source.equals("log.line")) {
                        throw invalid("unsupported event source: " + source);
                    }
                    if (o.has("pattern")) {
                        String raw = str(o, "pattern", null);
                        if (raw == null) throw invalid("event.pattern must be a string");
                        try {
                            n.pattern = Pattern.compile(raw);
                        } catch (PatternSyntaxException e) {
                            throw invalid("event.pattern does not compile: " + e.getMessage());
                        }
                    }
                    String levelName = o.has("level_min") ? str(o, "level_min", "INFO") : "INFO";
                    Level level = Level.getLevel(levelName);
                    if (level == null) throw invalid("event.level_min must be DEBUG/INFO/WARN/ERROR");
                    n.minLevel = level;
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 30.0, 0.1, 300.0));
                    return n;
                }
                case "any": {
                    AnyNode n = new AnyNode(depth);
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 30.0, 0.1, 300.0));
                    List<JsonObject> rules = ruleArray(o, "rules", 2, 16);
                    for (JsonObject child : rules) n.children.add(node(child, depth + 1, false));
                    return n;
                }
                case "all": {
                    AllNode n = new AllNode(depth);
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 30.0, 0.1, 300.0));
                    if (o.has("mode")) {
                        String mode = str(o, "mode", "latched");
                        if (!mode.equals("latched") && !mode.equals("simultaneous")) {
                            throw invalid("all.mode must be latched or simultaneous");
                        }
                        n.simultaneous = mode.equals("simultaneous");
                    }
                    List<JsonObject> rules = ruleArray(o, "rules", 2, 16);
                    for (JsonObject child : rules) n.children.add(node(child, depth + 1, false));
                    return n;
                }
                case "sequence": {
                    SequenceNode n = new SequenceNode(depth);
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 30.0, 0.1, 300.0));
                    List<JsonObject> steps = ruleArray(o, "steps", 2, 16);
                    for (JsonObject step : steps) n.steps.add(node(step, depth + 1, false));
                    return n;
                }
                case "repeat": {
                    RepeatNode n = new RepeatNode(depth);
                    n.maxCount = integer(o, "max_count", 1, 1, 100);
                    n.timeoutTicks = secondsToTicks(number(o, "timeout_seconds", 300.0, 0.1, 3600.0));
                    n.inner = node(obj(o, "rule", true), depth + 1, false);
                    if (o.has("until")) {
                        if (!o.get("until").isJsonObject()) throw invalid("repeat.until must be a rule object");
                        n.until = node(o.getAsJsonObject("until"), depth + 1, false);
                    }
                    return n;
                }
                default:
                    throw invalid("unknown rule type: " + type);
            }
        }

        private Condition condition(JsonObject cond) {
            String kind = str(cond, "kind", null);
            if (kind == null) throw invalid("condition.kind is required");
            Condition c = new Condition(kind);
            switch (kind) {
                case "block_match": {
                    c.pos = vec3(cond, "pos");
                    c.toBlock = str(cond, "to_block", null);
                    if (c.toBlock == null) throw invalid("block_match.to_block is required");
                    if (Identifier.tryParse(c.toBlock) == null) {
                        throw invalid("block_match.to_block is not a valid block id");
                    }
                    return c;
                }
                case "block_state": {
                    c.pos = vec3(cond, "pos");
                    c.property = str(cond, "property", null);
                    c.value = str(cond, "value", null);
                    if (c.property == null || c.value == null) {
                        throw invalid("block_state needs property and value");
                    }
                    return c;
                }
                case "entity_removed": {
                    String type = str(cond, "entity_type", null);
                    if (type == null) throw invalid("entity_removed.entity_type is required");
                    c.entityMatcher = entityMatcher(type);
                    if (cond.has("uuid")) c.uuid = uuid(cond, "uuid");
                    return c;
                }
                case "entity_hp_below": {
                    if (!cond.has("threshold")) throw invalid("entity_hp_below.threshold is required");
                    c.threshold = number(cond, "threshold", 0.0, -1e9, 1e9);
                    if (cond.has("uuid")) c.uuid = uuid(cond, "uuid");
                    String type = str(cond, "entity_type", null);
                    c.entityMatcher = type == null
                            ? new WatchSnapshot.EntityMatcher(List.of(), List.of(), List.of())
                            : entityMatcher(type);
                    return c;
                }
                default:
                    throw invalid("unknown condition kind: " + kind);
            }
        }

        private WatchSnapshot.EntityMatcher entityMatcher(String raw) {
            try {
                return WatchSnapshot.parseEntityFilter(List.of(raw));
            } catch (McbServer.OpException e) {
                throw invalid("invalid entity_type in condition: " + e.getMessage());
            }
        }
    }

    // =====================================================================
    // JSON helpers (strict: rule params are strong-validated per §7.2.2)
    // =====================================================================
    private static String trimSeconds(double seconds) {
        // "after_seconds(3.0)" — one decimal is the documented normal form.
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    private static JsonObject obj(JsonObject o, String key, boolean required) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            if (required) throw invalid("missing required object: " + key);
            return null;
        }
        if (!o.get(key).isJsonObject()) throw invalid(key + " must be an object");
        return o.getAsJsonObject(key);
    }

    private static List<JsonObject> ruleArray(JsonObject o, String key, int min, int max) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            throw invalid(key + " must be an array of rule objects");
        }
        JsonArray arr = o.getAsJsonArray(key);
        if (arr.size() < min || arr.size() > max) {
            throw invalid(key + " must contain " + min + ".." + max + " entries");
        }
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) throw invalid(key + " entries must be rule objects");
            out.add(el.getAsJsonObject());
        }
        return out;
    }

    private static int[] vec3(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw invalid(key + " must be [x,y,z]");
        JsonArray a = o.getAsJsonArray(key);
        if (a.size() != 3) throw invalid(key + " must be [x,y,z]");
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement el = a.get(i);
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
                throw invalid(key + " components must be integers");
            }
            out[i] = el.getAsInt();
        }
        return out;
    }

    private static UUID uuid(JsonObject o, String key) {
        String raw = str(o, key, null);
        if (raw == null) throw invalid(key + " must be a UUID string");
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(key + " is not a valid UUID");
        }
    }

    private static String str(JsonObject o, String key, String dflt) {
        if (!o.has(key) || o.get(key).isJsonNull()) return dflt;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw invalid(key + " must be a string");
        }
        return el.getAsString();
    }

    private static double num(JsonObject o, String key, double dflt) {
        if (!o.has(key) || o.get(key).isJsonNull()) return dflt;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw invalid(key + " must be a number");
        }
        return el.getAsDouble();
    }

    private static double number(JsonObject o, String key, double dflt, double min, double max) {
        double v = num(o, key, dflt);
        if (Double.isNaN(v) || v < min || v > max) {
            throw invalid(key + " must be within " + min + ".." + max);
        }
        return v;
    }

    private static int integer(JsonObject o, String key, int dflt, int min, int max) {
        if (!o.has(key) || o.get(key).isJsonNull()) return dflt;
        JsonElement el = o.get(key);
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw invalid(key + " must be an integer");
        }
        double d = el.getAsDouble();
        if (d != Math.rint(d) || Double.isInfinite(d)) throw invalid(key + " must be an integer");
        int v = (int) d;
        if (v < min || v > max) throw invalid(key + " must be within " + min + ".." + max);
        return v;
    }

    static BlockPos pos(int[] p) {
        return new BlockPos(p[0], p[1], p[2]);
    }
}
