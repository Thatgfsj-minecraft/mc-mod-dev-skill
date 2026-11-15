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
import java.util.function.Consumer;

/**
 * Captures every log line produced by the game process into a ring buffer,
 * and pushes new entries to all connected bridge clients as `log` events.
 *
 * Implements protocol.md §3.7 (log.history) and §3.8 (log.wait) storage side,
 * plus the `log` event (§2.3).
 *
 * Implementation notes:
 * - Minecraft uses log4j2, so we attach a log4j2 core Appender to the root
 *   logger. log4j-core is already on the classpath (Minecraft ships it).
 * - Entries get monotonically increasing ids starting at 1, assigned in the
 *   appender thread. Ring buffer capacity 10000 (protocol §3.7).
 * - `overflowed` bookkeeping: when entries are evicted we remember the highest
 *   evicted id so log.history can report gaps (protocol: gaps must be visible).
 */
public final class McbLogCapture {
    private McbLogCapture() {}

    private static final int CAPACITY = 10000;

    private static final ArrayDeque<Entry> buffer = new ArrayDeque<>(CAPACITY);
    private static long nextId = 1;
    private static long highestEvictedId = 0;

    /** Subscribers = connected bridge clients (set by McbServer). */
    private static final List<Consumer<Entry>> subscribers = new ArrayList<>();

    public static synchronized void attach() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Appender appender = new Appender("McbBridgeAppender");
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
            Entry entry;
            List<Consumer<Entry>> currentSubs;
            synchronized (McbLogCapture.class) {
                entry = new Entry(nextId++, event.getTimeMillis(),
                        event.getLevel().name(), event.getLoggerName(),
                        event.getThreadName(), message);
                if (buffer.size() >= CAPACITY) {
                    buffer.pollFirst();
                    highestEvictedId = entry.id - CAPACITY;
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

    /** Snapshot of entries with id > afterId. Returns entries + nextId + overflow flag. */
    public static synchronized Snapshot readAfter(long afterId, int limit) {
        List<Entry> out = new ArrayList<>();
        long last = afterId;
        for (Entry e : buffer) {
            if (e.id > afterId) {
                if (out.size() >= limit) {
                    return new Snapshot(out, last, false);
                }
                out.add(e);
                last = e.id;
            }
        }
        return new Snapshot(out, last, afterId > 0 && afterId < highestEvictedId);
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final long lastId;
        public final boolean overflowed;
        Snapshot(List<Entry> entries, long lastId, boolean overflowed) {
            this.entries = entries; this.lastId = lastId; this.overflowed = overflowed;
        }
    }

    public static synchronized long highestId() {
        return nextId - 1;
    }

    /** Check whether any buffered entry with id > afterId matches the pattern. */
    public static synchronized Entry findMatch(String regex, Level minLevel, long afterId) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
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
