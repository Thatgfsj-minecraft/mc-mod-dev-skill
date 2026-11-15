package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Captures every log line produced by the game process into a ring buffer,
 * and pushes new entries to connected bridge clients as `log` events.
 *
 * Implements protocol.md §3.7 (log.history) and §3.8 (log.wait) storage side,
 * plus the `log` event (§2.3).
 *
 * Implementation notes:
 * - Minecraft uses log4j2, so we attach a log4j2 core Appender to the root
 *   logger. log4j-core is already on the classpath (Minecraft ships it).
 * - Entries get monotonically increasing ids starting at 1, assigned in the
 *   appender thread. Ring buffer capacity 10000 (protocol §3.7).
 * - §3.7 cursor semantics are implemented in readAfter() exactly as the
 *   protocol defines them — do not "simplify" them: nextId must advance on
 *   empty pages, and overflowed must be computable from a single comparison.
 * - §2.3 backpressure: event push is best-effort. A slow client's outbound
 *   queue dropping an event is counted in droppedEvents, never blocking the
 *   logger thread.
 */
public final class McbLogCapture {
    private McbLogCapture() {}

    private static final int CAPACITY = 10000;
    /** §1 single-line cap: log messages above this are truncated on capture. */
    private static final int MAX_MESSAGE_CHARS = 8192;

    private static final ArrayDeque<Entry> buffer = new ArrayDeque<>(CAPACITY);
    private static long nextId = 1;

    /** §2.3: log event pushes dropped because a client's outbound queue was full. */
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong();
    public static long droppedEvents() { return DROPPED_EVENTS.get(); }
    static void onEventDropped() { DROPPED_EVENTS.incrementAndGet(); }

    /** Subscribers = connected bridge clients (set by McbServer). */
    private static final List<Consumer<Entry>> subscribers = new ArrayList<>();

    public static synchronized void attach() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Appender appender = new Appender("McbBridgeAppender");
        // P0: log4j2 appenders MUST be started before use — an unstarted
        // appender silently drops every event (no error, no logging at all).
        appender.start();
        config.addAppender(appender);
        ctx.getRootLogger().addAppender(appender);
        ctx.updateLoggers();
    }

    // ---------------------------------------------------------------------
    // Entry model — matches the `log` event / log.history entry shape in the
    // protocol exactly, so serialization has no translation layer.
    // ---------------------------------------------------------------------
    public static final class Entry {
        public final long id;
        public final long ts;
        public final String level;
        public final String logger;
        public final String thread;
        public final String message;

        Entry(long id, long ts, String level, String logger, String thread, String message) {
            this.id = id; this.ts = ts; this.level = level;
            this.logger = logger; this.thread = thread; this.message = message;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("ts", ts);
            o.addProperty("level", level);
            o.addProperty("logger", logger);
            o.addProperty("thread", thread);
            o.addProperty("message", message);
            return o;
        }
    }

    private static final class Appender extends AbstractAppender {
        Appender(String name) {
            // ignoreExceptions=true so a bridge bug can never kill logging.
            super(name, null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                // Stack traces are the most valuable thing for a debugging AI.
                StringBuilder sb = new StringBuilder(message);
                sb.append('\n').append(thrown.toString());
                for (StackTraceElement el : thrown.getStackTrace()) {
                    sb.append("\n\tat ").append(el);
                }
                message = sb.toString();
            }
            // §1 single-line cap: truncate BEFORE serialization so one huge
            // log line can never break NDJSON framing at the transport.
            if (message.length() > MAX_MESSAGE_CHARS) {
                message = message.substring(0, MAX_MESSAGE_CHARS) + "...(truncated)";
            }
            Entry entry;
            List<Consumer<Entry>> currentSubs;
            synchronized (McbLogCapture.class) {
                entry = new Entry(nextId++, event.getTimeMillis(),
                        event.getLevel().name(), event.getLoggerName(),
                        event.getThreadName(), message);
                if (buffer.size() >= CAPACITY) {
                    buffer.pollFirst(); // ring eviction: oldest entries go first
                }
                buffer.addLast(entry);
                currentSubs = List.copyOf(subscribers);
            }
            for (Consumer<Entry> sub : currentSubs) {
                try {
                    sub.accept(entry);
                } catch (Exception e) {
                    // A dead subscriber must not affect logging.
                }
            }
        }
    }

    /**
     * §3.7 log.history — cursor semantics implemented EXACTLY as protocol.md
     * defines them (both reference implementations must agree on these):
     *
     * - entries: id > afterId AND passing levelMin; limit applies AFTER
     *   filtering (levelMin is a read-time filter — the buffer always holds
     *   all levels so switching levelMin never changes the "history").
     * - nextId: raw id of the last entry scanned. When the whole buffer was
     *   scanned (page not cut short by limit), that is the newest raw id —
     *   so a page with zero matches still advances the cursor and never spins.
     *   When the page WAS cut short, nextId is the raw id we stopped at — the
     *   next page continues right after it, so filtered-out entries are never
     *   skipped over by a limited page.
     * - overflowed: oldest buffered id > afterId + 1, i.e. raw entries in
     *   (afterId, oldest) were evicted. Gaps must be visible (§3.7).
     */
    public static synchronized Snapshot readAfter(long afterId, Level minLevel, int limit) {
        List<Entry> out = new ArrayList<>();
        long cursor = afterId;
        boolean scanComplete = true;
        for (Entry e : buffer) {
            if (e.id <= afterId) continue;
            cursor = e.id;
            if (out.size() < limit && levelAtLeast(e.level, minLevel)) {
                out.add(e);
                if (out.size() >= limit) {
                    scanComplete = false;
                    break;
                }
            }
        }
        if (scanComplete) {
            long newest = nextId - 1;
            if (cursor < newest) cursor = newest;
        }
        boolean overflowed = !buffer.isEmpty() && buffer.peekFirst().id > afterId + 1;
        return new Snapshot(out, cursor, overflowed);
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final long nextId;
        public final boolean overflowed;
        Snapshot(List<Entry> entries, long nextId, boolean overflowed) {
            this.entries = entries; this.nextId = nextId; this.overflowed = overflowed;
        }
    }

    public static synchronized long highestId() {
        return nextId - 1;
    }

    /** Check whether any buffered entry with id > afterId matches the pattern. */
    public static synchronized Entry findMatch(Pattern p, Level minLevel, long afterId) {
        for (Entry e : buffer) {
            if (e.id > afterId && levelAtLeast(e.level, minLevel) && p.matcher(e.message).find()) {
                return e;
            }
        }
        return null;
    }

    static boolean levelAtLeast(String level, Level min) {
        try {
            return Level.getLevel(level).intLevel() >= min.intLevel();
        } catch (Exception e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ subscriptions
    public static synchronized void subscribe(Consumer<Entry> sub) {
        subscribers.add(sub);
    }

    public static synchronized void unsubscribe(Consumer<Entry> sub) {
        subscribers.remove(sub);
    }
}
