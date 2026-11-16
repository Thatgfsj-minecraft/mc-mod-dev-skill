package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * MCB v1 §7 watch 会话引擎：服务端权威采样与 diff。
 *
 * <p>THREAD DISCIPLINE (§6.1 / §7.10.1): every method in this file reads the world
 * and therefore MUST run on the integrated-server thread. {@link WatchEngine}
 * is the only caller and only invokes it from inside {@code server.execute(...)}.
 * The reporting helpers ({@link #blockSummary}, {@link #entitySummary},
 * {@link #snapshotBlocks}) build the lightweight JSON that the IO thread later
 * serializes.
 *
 * <p>§7.10.2 chunk protection: {@link #capture} NEVER force-loads a chunk. A
 * column whose chunk is not loaded is skipped and its (chunkX, chunkZ) is
 * recorded in {@link Sample#truncatedChunks}; the report layer treats positions
 * in those chunks as "unknown" instead of "changed to air".
 *
 * <p>Memory (§7.9): a sample stores at most 32768 block references in a pair of
 * parallel primitive/reference arrays (no per-block object). Entity identity is
 * the persistent UUID; the runtime entity id is only an auxiliary lookup used
 * to tell {@code out_of_bounds} from {@code removed} (see README note on id
 * reuse).
 */
final class WatchSnapshot {
    private WatchSnapshot() {}

    /** §7.1: region volume hard cap (protocol §7.9 memory discipline). */
    static final int VOLUME_LIMIT = 32768;
    /** §7.1: per-frame detail list cap upper bound. */
    static final int DETAIL_LIMIT_MAX = 200;
    /** §7.1: tracked entity cap upper bound. */
    static final int MAX_ENTITY_COUNT = 512;
    /** §7.3.3: block-entity SNBT snippet cap (keeps a report well below 1 MiB). */
    static final int NBT_SNIPPET_LIMIT = 512;

    // =====================================================================
    // Region bounds
    // =====================================================================
    static final class Bounds {
        final int minX, minY, minZ, maxX, maxY, maxZ;

        /** @param min already per-axis normalized by the caller (§7.1). */
        Bounds(int[] min, int[] max) {
            this.minX = min[0]; this.minY = min[1]; this.minZ = min[2];
            this.maxX = max[0]; this.maxY = max[1]; this.maxZ = max[2];
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        AABB entityAabb() {
            return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.add("min", intArray(minX, minY, minZ));
            o.add("max", intArray(maxX, maxY, maxZ));
            return o;
        }
    }

    // =====================================================================
    // Filter matchers (§7.1: id / glob / #tag; unknown tag is not an error
    // because tags are datapack data, but malformed syntax is BAD_PARAMS).
    // =====================================================================
    static final class BlockMatcher {
        private final List<String> ids;
        private final List<Pattern> globs;
        private final List<TagKey<Block>> tags;
        private final boolean empty;

        BlockMatcher(List<String> ids, List<Pattern> globs, List<TagKey<Block>> tags) {
            this.ids = ids; this.globs = globs; this.tags = tags;
            this.empty = ids.isEmpty() && globs.isEmpty() && tags.isEmpty();
        }

        boolean isEmpty() { return empty; }

        boolean matches(BlockState state) {
            if (empty) return true;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (ids.contains(id)) return true;
            for (Pattern p : globs) if (p.matcher(id).matches()) return true;
            for (TagKey<Block> tag : tags) if (state.is(tag)) return true;
            return false;
        }
    }

    static final class EntityMatcher {
        private final List<String> ids;
        private final List<Pattern> globs;
        private final List<TagKey<EntityType<?>>> tags;
        private final boolean empty;

        EntityMatcher(List<String> ids, List<Pattern> globs, List<TagKey<EntityType<?>>> tags) {
            this.ids = ids; this.globs = globs; this.tags = tags;
            this.empty = ids.isEmpty() && globs.isEmpty() && tags.isEmpty();
        }

        boolean isEmpty() { return empty; }

        boolean matches(Entity entity) {
            if (empty) return true;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            if (ids.contains(id)) return true;
            for (Pattern p : globs) if (p.matcher(id).matches()) return true;
            for (TagKey<EntityType<?>> tag : tags) if (entity.getType().is(tag)) return true;
            return false;
        }

        boolean matchesTypeId(String id) {
            if (empty) return true;
            if (ids.contains(id)) return true;
            for (Pattern p : globs) if (p.matcher(id).matches()) return true;
            // A #tag condition cannot be checked from an id string alone; the
            // entity_removed condition evaluates against live entries so this
            // path is only a fallback (tags are uncommon there).
            return false;
        }
    }

    /** Parse a §7.1 filter array. Unknown/malformed element -> BAD_PARAMS. */
    static BlockMatcher parseBlockFilter(List<String> raw) {
        List<String> ids = new ArrayList<>();
        List<Pattern> globs = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String f : raw) {
            if (f == null || f.isBlank()) {
                throw new McbServer.OpException("BAD_PARAMS", "block filter entries must be non-empty strings");
            }
            if (f.startsWith("#")) {
                String path = f.substring(1);
                Identifier id = Identifier.tryParse(path);
                if (id == null) {
                    throw new McbServer.OpException("BAD_PARAMS", "invalid block tag in filter: " + f);
                }
                tags.add(TagKey.create(Registries.BLOCK, id));
            } else if (f.indexOf('*') >= 0) {
                globs.add(globPattern(f));
            } else {
                if (Identifier.tryParse(f) == null) {
                    throw new McbServer.OpException("BAD_PARAMS", "invalid block id in filter: " + f);
                }
                ids.add(f);
            }
        }
        return new BlockMatcher(ids, globs, tags);
    }

    /** Parse a §7.1 entity type filter array. */
    static EntityMatcher parseEntityFilter(List<String> raw) {
        List<String> ids = new ArrayList<>();
        List<Pattern> globs = new ArrayList<>();
        List<TagKey<EntityType<?>>> tags = new ArrayList<>();
        for (String f : raw) {
            if (f == null || f.isBlank()) {
                throw new McbServer.OpException("BAD_PARAMS", "entity filter entries must be non-empty strings");
            }
            if (f.startsWith("#")) {
                String path = f.substring(1);
                Identifier id = Identifier.tryParse(path);
                if (id == null) {
                    throw new McbServer.OpException("BAD_PARAMS", "invalid entity tag in filter: " + f);
                }
                tags.add(TagKey.create(Registries.ENTITY_TYPE, id));
            } else if (f.indexOf('*') >= 0) {
                globs.add(globPattern(f));
            } else {
                if (Identifier.tryParse(f) == null) {
                    throw new McbServer.OpException("BAD_PARAMS", "invalid entity type id in filter: " + f);
                }
                ids.add(f);
            }
        }
        return new EntityMatcher(ids, globs, tags);
    }

    /** "mymod:*" -> regex. '*' is the only metacharacter in MCB globs. */
    static Pattern globPattern(String glob) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        for (int i = 0; i < glob.length(); i++) {
            if (glob.charAt(i) == '*') {
                if (i > start) sb.append(Pattern.quote(glob.substring(start, i)));
                sb.append(".*");
                start = i + 1;
            }
        }
        if (start < glob.length()) sb.append(Pattern.quote(glob.substring(start)));
        return Pattern.compile(sb.toString());
    }

    // =====================================================================
    // Track configuration (§7.1 tracks.*)
    // =====================================================================
    static final class Tracked {
        boolean blocksEnabled = true;
        boolean ignoreAirToAir = true;
        boolean includeNbt = false;
        BlockMatcher blockFilter = new BlockMatcher(List.of(), List.of(), List.of());

        boolean entitiesEnabled = true;
        boolean excludePlayers = false;
        EntityMatcher entityFilter = new EntityMatcher(List.of(), List.of(), List.of());

        boolean wantPos = true;
        boolean wantHp = true;
        boolean wantAlive = true;
        boolean wantName = false;

        double posEpsilon = 0.25;
        double hpEpsilon = 0.5;
    }

    // =====================================================================
    // Entity entry: UUID is the identity (§7.10.4); runtimeId is auxiliary.
    // =====================================================================
    static final class EntityEntry {
        final UUID uuid;
        final String typeId;
        final int runtimeId;
        final double x, y, z;
        final Double hp;
        final boolean alive;
        final boolean living;
        final boolean player;
        final String name;
        final BlockPos blockPos;
        final int chunkX, chunkZ;

        EntityEntry(Entity e, Tracked t) {
            this.uuid = e.getUUID();
            this.typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            this.runtimeId = e.getId();
            this.x = e.getX(); this.y = e.getY(); this.z = e.getZ();
            this.living = e instanceof LivingEntity;
            this.hp = (e instanceof LivingEntity le) ? Double.valueOf(le.getHealth()) : null;
            this.alive = e.isAlive();
            this.player = e instanceof Player;
            this.name = t.wantName ? e.getName().getString() : null;
            this.blockPos = e.blockPosition();
            this.chunkX = blockPos.getX() >> 4;
            this.chunkZ = blockPos.getZ() >> 4;
        }

        /** Field projection controlled by tracks.entities.fields (§7.1). */
        JsonObject toJson(Tracked t) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", uuid.toString());
            o.addProperty("type", typeId);
            if (t.wantPos) {
                o.add("pos", doubleArray(x, y, z));
            }
            if (t.wantHp) {
                if (hp == null) o.add("hp", null); else o.addProperty("hp", hp);
            }
            if (t.wantAlive) {
                o.addProperty("alive", alive);
            }
            if (t.wantName && name != null) {
                o.addProperty("name", name);
            }
            return o;
        }

        double distanceTo(double ox, double oy, double oz) {
            double dx = x - ox, dy = y - oy, dz = z - oz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    // =====================================================================
    // Sample
    // =====================================================================
    static final class Sample {
        /** Keys in capture order: x asc, then z asc, then y asc (diff merges this order). */
        final long[] keys;
        final BlockState[] states;
        final int count;
        final Map<UUID, EntityEntry> entities;
        final int nonAirCount;
        final boolean entityCountTruncated;
        /** Packed (chunkX << 32 | chunkZ & 0xffffffffL), sorted ascending. */
        final long[] truncatedChunks;

        Sample(long[] keys, BlockState[] states, int count, Map<UUID, EntityEntry> entities,
               int nonAirCount, boolean entityCountTruncated, long[] truncatedChunks) {
            this.keys = keys; this.states = states; this.count = count;
            this.entities = entities;
            this.nonAirCount = nonAirCount;
            this.entityCountTruncated = entityCountTruncated;
            this.truncatedChunks = truncatedChunks;
        }

        static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xffffffffL); }

        boolean chunkLoaded(int cx, int cz) {
            return java.util.Arrays.binarySearch(truncatedChunks, chunkKey(cx, cz)) < 0;
        }

        /** O(n) block lookup; only used for one-off condition baselines, never per block. */
        BlockState lookup(long blockKey) {
            for (int i = 0; i < count; i++) {
                if (keys[i] == blockKey) return states[i];
            }
            return null;
        }

        BlockState lookup(BlockPos pos) { return lookup(pos.asLong()); }
    }

    // =====================================================================
    // Capture — MUST run on the server thread (§7.10.1).
    // =====================================================================
    static Sample capture(ServerLevel level, Bounds b, Tracked t, int[] origin,
                          int maxEntityCount, boolean sampleBlocks, boolean sampleEntities) {
        long[] keys;
        BlockState[] states;
        int count = 0;
        int nonAir = 0;
        List<Long> truncated = null;

        if (sampleBlocks) {
            int worldMinY = level.getMinY();
            int worldMaxY = level.getMaxY();
            int y0 = Math.max(b.minY, worldMinY);
            int y1 = Math.min(b.maxY, worldMaxY);
            long cap = (long) (b.maxX - b.minX + 1) * (b.maxZ - b.minZ + 1) * Math.max(0, y1 - y0 + 1);
            int capacity = (int) Math.min(cap, VOLUME_LIMIT * 2L);
            keys = new long[Math.max(0, capacity)];
            states = new BlockState[Math.max(0, capacity)];
            if (y0 <= y1) {
                int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
                boolean columnLoaded = false;
                for (int x = b.minX; x <= b.maxX; x++) {
                    int cx = x >> 4;
                    for (int z = b.minZ; z <= b.maxZ; z++) {
                        int cz = z >> 4;
                        if (cx != lastCx || cz != lastCz) {
                            lastCx = cx; lastCz = cz;
                            // §7.10.2: never force-load. hasChunk() is a pure
                            // lookup, unlike getBlockState() which may load.
                            columnLoaded = level.isLoaded(new BlockPos(x, Math.max(y0, worldMinY), z));
                            if (!columnLoaded) {
                                if (truncated == null) truncated = new ArrayList<>();
                                truncated.add(Sample.chunkKey(cx, cz));
                            }
                        }
                        if (!columnLoaded) continue;
                        for (int y = y0; y <= y1; y++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockState state = level.getBlockState(pos);
                            if (count == keys.length) {
                                // Cannot happen (volume cap) — defensive: grow.
                                keys = java.util.Arrays.copyOf(keys, keys.length * 2 + 16);
                                states = java.util.Arrays.copyOf(states, states.length * 2 + 16);
                            }
                            keys[count] = pos.asLong();
                            states[count] = state;
                            count++;
                            if (!state.isAir()) nonAir++;
                        }
                    }
                }
            }
        } else {
            keys = new long[0];
            states = new BlockState[0];
        }

        long[] chunks = sortedDedup(truncated);

        Map<UUID, EntityEntry> ents = new HashMap<>();
        boolean entTruncated = false;
        if (sampleEntities) {
            AABB box = b.entityAabb();
            List<Entity> found = level.getEntitiesOfClass(Entity.class, box,
                    e -> !(t.excludePlayers && e instanceof Player) && t.entityFilter.matches(e));
            if (found.size() > maxEntityCount) {
                entTruncated = true;
                List<Entity> sorted = new ArrayList<>(found);
                sorted.sort(entityOrder(origin));
                found = sorted.subList(0, maxEntityCount);
            }
            for (Entity e : found) {
                EntityEntry entry = new EntityEntry(e, t);
                ents.put(entry.uuid, entry);
            }
        }
        return new Sample(keys, states, count, ents, nonAir, entTruncated, chunks);
    }

    /** §7.1: truncate by distance to origin, else x -> y -> z lexicographic. */
    private static Comparator<Entity> entityOrder(int[] origin) {
        Comparator<Entity> tie = Comparator.<Entity>comparingDouble(Entity::getX)
                .thenComparingDouble(Entity::getY)
                .thenComparingDouble(Entity::getZ)
                .thenComparing(e -> e.getUUID().toString());
        if (origin == null) return tie;
        return Comparator.<Entity>comparingDouble(
                        e -> (e.getX() - origin[0]) * (e.getX() - origin[0])
                                + (e.getY() - origin[1]) * (e.getY() - origin[1])
                                + (e.getZ() - origin[2]) * (e.getZ() - origin[2]))
                .thenComparing(tie);
    }

    private static long[] sortedDedup(List<Long> in) {
        if (in == null || in.isEmpty()) return new long[0];
        long[] arr = new long[in.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = in.get(i);
        java.util.Arrays.sort(arr);
        int n = 0;
        for (int i = 0; i < arr.length; i++) {
            if (i == 0 || arr[i] != arr[i - 1]) arr[n++] = arr[i];
        }
        return java.util.Arrays.copyOf(arr, n);
    }

    // =====================================================================
    // Block diff
    // =====================================================================
    static final class BlockChange {
        final long key;
        final BlockState from;
        final BlockState to;
        BlockChange(long key, BlockState from, BlockState to) {
            this.key = key; this.from = from; this.to = to;
        }
        int x() { return BlockPos.getX(key); }
        int y() { return BlockPos.getY(key); }
        int z() { return BlockPos.getZ(key); }
        String fromId() { return BuiltInRegistries.BLOCK.getKey(from.getBlock()).toString(); }
        String toId() { return BuiltInRegistries.BLOCK.getKey(to.getBlock()).toString(); }
    }

    private static final Comparator<BlockChange> XYZ_ORDER = Comparator
            .comparingInt(BlockChange::x)
            .thenComparingInt(BlockChange::y)
            .thenComparingInt(BlockChange::z);

    /** Merge two captures in capture order (x, z, y); both arrays preserve it. */
    private static int compareKey(long a, long b) {
        int ax = BlockPos.getX(a), bx = BlockPos.getX(b);
        if (ax != bx) return Integer.compare(ax, bx);
        int az = BlockPos.getZ(a), bz = BlockPos.getZ(b);
        if (az != bz) return Integer.compare(az, bz);
        return Integer.compare(BlockPos.getY(a), BlockPos.getY(b));
    }

    /**
     * Relevant tracked block changes between two samples, sorted x -> y -> z.
     * Positions whose chunk was not loaded in {@code now} are unknown, not air
     * (§7.10.2): they are skipped.
     */
    static List<BlockChange> diffBlocks(Sample before, Sample now, Tracked t) {
        List<BlockChange> out = new ArrayList<>();
        if (!t.blocksEnabled) return out;
        int i = 0, j = 0;
        while (i < before.count && j < now.count) {
            long ka = before.keys[i], kb = now.keys[j];
            int c = compareKey(ka, kb);
            if (c == 0) {
                BlockState a = before.states[i];
                BlockState b = now.states[j];
                if (!a.equals(b) && relevantBlockChange(a, b, t)) {
                    out.add(new BlockChange(ka, a, b));
                }
                i++; j++;
            } else if (c < 0) {
                i++; // before-only: chunk not loaded now (unknown) -> skip
            } else {
                j++; // now-only: chunk was not loaded at t0 -> skip
            }
        }
        out.sort(XYZ_ORDER);
        return out;
    }

    private static boolean relevantBlockChange(BlockState from, BlockState to, Tracked t) {
        if (t.ignoreAirToAir && from.isAir() && to.isAir()) return false;
        return t.blockFilter.isEmpty()
                || t.blockFilter.matches(from)
                || t.blockFilter.matches(to);
    }

    /** Cheap settle check (§7.10.3): early-exit variant of {@link #diffBlocks}. */
    static boolean anyBlockChange(Sample before, Sample now, Tracked t) {
        if (!t.blocksEnabled) return false;
        int i = 0, j = 0;
        while (i < before.count && j < now.count) {
            long ka = before.keys[i], kb = now.keys[j];
            int c = compareKey(ka, kb);
            if (c == 0) {
                BlockState a = before.states[i];
                BlockState b = now.states[j];
                if (!a.equals(b) && relevantBlockChange(a, b, t)) return true;
                i++; j++;
            } else if (c < 0) {
                i++;
            } else {
                j++;
            }
        }
        return false;
    }

    // =====================================================================
    // Entity diff (§7.3.2 / §7.10.4)
    // =====================================================================
    static final class EntityDelta {
        final List<EntityEntry> entered = new ArrayList<>();
        final List<EntityEntry> removed = new ArrayList<>();
        final List<String> removedReasons = new ArrayList<>();
        /** {entry, hpBefore, hpAfter} */
        final List<Object[]> hpPairs = new ArrayList<>();
        final List<EntityEntry> moved = new ArrayList<>();
        final Map<UUID, double[]> movedFrom = new HashMap<>();

        boolean anyChange() {
            return !entered.isEmpty() || !removed.isEmpty() || !hpPairs.isEmpty() || !moved.isEmpty();
        }
    }

    /**
     * Per-tick entity delta. {@code out_of_bounds} vs {@code removed} uses the
     * runtime id only as an auxiliary lookup (§7.10.4): the live entity must
     * still carry the same UUID, otherwise a recycled id is ignored.
     */
    static EntityDelta diffEntities(Sample before, Sample now, ServerLevel level, Tracked t) {
        EntityDelta d = new EntityDelta();
        if (!t.entitiesEnabled) return d;
        for (EntityEntry e : now.entities.values()) {
            EntityEntry old = before.entities.get(e.uuid);
            if (old == null) {
                d.entered.add(e);
                continue;
            }
            if (old.hp != null && e.hp != null && Math.abs(old.hp - e.hp) > t.hpEpsilon) {
                d.hpPairs.add(new Object[] {e, old.hp, e.hp});
            }
            double dx = e.x - old.x, dy = e.y - old.y, dz = e.z - old.z;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) > t.posEpsilon) {
                d.moved.add(e);
                d.movedFrom.put(e.uuid, new double[] {old.x, old.y, old.z});
            }
        }
        for (EntityEntry old : before.entities.values()) {
            if (now.entities.containsKey(old.uuid)) continue;
            // §7.10.2: an entity whose chunk is not loaded now is UNKNOWN, not
            // removed. Reading level.isLoaded() here is a pure lookup.
            if (!level.isLoaded(old.blockPos)) continue;
            boolean removed = true;
            Entity live = level.getEntity(old.runtimeId);
            if (live != null && !live.isRemoved() && live.getUUID().equals(old.uuid)) {
                removed = false; // still alive somewhere else -> out_of_bounds
            }
            d.removed.add(old);
            d.removedReasons.add(removed ? "removed" : "out_of_bounds");
        }
        return d;
    }

    // =====================================================================
    // Report sections (§7.3)
    // =====================================================================
    static final class Section {
        final JsonObject json;
        final boolean truncated;
        final int truncatedCount;
        Section(JsonObject json, boolean truncated, int truncatedCount) {
            this.json = json; this.truncated = truncated; this.truncatedCount = truncatedCount;
        }
    }

    /** §7.3.1 block_summary. */
    static Section blockSummary(Sample base, Sample now, Tracked t, int[] origin, int detailLimit) {
        List<BlockChange> changes = diffBlocks(base, now, t);
        int total = changes.size();
        JsonObject o = new JsonObject();
        o.addProperty("total_changed", total);
        if (total == 0) {
            o.add("affected_bounds", null);
            o.add("max_radius_from_origin", null);
            o.add("transitions", new JsonObject());
            o.add("sample_changes", new JsonArray());
            return new Section(o, false, 0);
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        double maxRadius = -1;
        Map<String, Integer> transitions = new LinkedHashMap<>();
        for (BlockChange c : changes) {
            int x = c.x(), y = c.y(), z = c.z();
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            transitions.merge(c.fromId() + " -> " + c.toId(), 1, Integer::sum);
            if (origin != null) {
                double dx = x - origin[0], dy = y - origin[1], dz = z - origin[2];
                double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (r > maxRadius) maxRadius = r;
            }
        }
        JsonObject bounds = new JsonObject();
        bounds.add("min", intArray(minX, minY, minZ));
        bounds.add("max", intArray(maxX, maxY, maxZ));
        o.add("affected_bounds", bounds);
        if (origin == null) {
            o.add("max_radius_from_origin", null);
        } else {
            o.addProperty("max_radius_from_origin", round2(maxRadius < 0 ? 0 : maxRadius));
        }

        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(transitions.entrySet());
        ordered.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        JsonObject transitionsJson = new JsonObject();
        int transCount = Math.min(detailLimit, ordered.size());
        for (int i = 0; i < transCount; i++) {
            transitionsJson.addProperty(ordered.get(i).getKey(), ordered.get(i).getValue());
        }
        o.add("transitions", transitionsJson);

        JsonArray samples = new JsonArray();
        int sampleCount = Math.min(detailLimit, total);
        for (int i = 0; i < sampleCount; i++) {
            BlockChange c = changes.get(i);
            JsonObject s = new JsonObject();
            s.add("pos", intArray(c.x(), c.y(), c.z()));
            s.addProperty("from", c.fromId());
            s.addProperty("to", c.toId());
            samples.add(s);
        }
        o.add("sample_changes", samples);
        int truncatedCount = Math.max(0, total - detailLimit) + Math.max(0, ordered.size() - detailLimit);
        return new Section(o, truncatedCount > 0, truncatedCount);
    }

    /** §7.3.1 entity_summary. */
    static Section entitySummary(Sample base, Sample now, ServerLevel level, Tracked t, int detailLimit) {
        List<JsonObject> impacts = new ArrayList<>();
        int removedCount = 0;
        int enteredCount = 0;
        int affected = 0;

        for (EntityEntry e : now.entities.values()) {
            EntityEntry old = base.entities.get(e.uuid);
            if (old == null) {
                enteredCount++;
                affected++;
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.uuid.toString());
                o.addProperty("type", e.typeId);
                o.add("hp_before", null);
                addHp(o, "hp_after", e.hp);
                o.addProperty("status", "entered");
                impacts.add(o);
            } else if (old.hp != null && e.hp != null && Math.abs(old.hp - e.hp) > t.hpEpsilon) {
                affected++;
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.uuid.toString());
                o.addProperty("type", e.typeId);
                o.addProperty("hp_before", old.hp);
                addHp(o, "hp_after", e.hp);
                o.addProperty("status", "alive");
                impacts.add(o);
            }
        }
        for (EntityEntry old : base.entities.values()) {
            if (now.entities.containsKey(old.uuid)) continue;
            if (!level.isLoaded(old.blockPos)) continue;
            Entity live = level.getEntity(old.runtimeId);
            boolean stillAlive = live != null && !live.isRemoved() && live.getUUID().equals(old.uuid);
            affected++;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", old.uuid.toString());
            o.addProperty("type", old.typeId);
            addHp(o, "hp_before", old.hp);
            if (stillAlive) {
                Double hpAfter = null;
                if (live instanceof LivingEntity le) hpAfter = Double.valueOf(le.getHealth());
                addHp(o, "hp_after", hpAfter);
                o.addProperty("status", "out_of_bounds");
            } else {
                removedCount++;
                // §7.3.1: removed + was LivingEntity -> 0.0, otherwise null.
                addHp(o, "hp_after", old.living ? Double.valueOf(0.0) : null);
                o.addProperty("status", "removed");
            }
            impacts.add(o);
        }

        impacts.sort(Comparator.comparing(o -> o.get("uuid").getAsString()));
        int truncatedCount = Math.max(0, impacts.size() - detailLimit);
        JsonArray arr = new JsonArray();
        int n = Math.min(detailLimit, impacts.size());
        for (int i = 0; i < n; i++) arr.add(impacts.get(i));

        JsonObject o = new JsonObject();
        o.addProperty("affected_count", affected);
        o.addProperty("removed_count", removedCount);
        o.addProperty("entered_count", enteredCount);
        o.add("impacts", arr);
        return new Section(o, truncatedCount > 0, truncatedCount);
    }

    private static void addHp(JsonObject o, String key, Double hp) {
        if (hp == null) o.add(key, null); else o.addProperty(key, hp);
    }

    /** §7.3.3 snapshot blocks: non-air, filter-matching, deterministic order. */
    static Section snapshotBlocks(Sample now, ServerLevel level, Tracked t, int detailLimit) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < now.count; i++) {
            BlockState state = now.states[i];
            if (state.isAir()) continue;
            if (!t.blocksEnabled || !t.blockFilter.matches(state)) continue;
            candidates.add(i);
        }
        candidates.sort((ia, ib) -> {
            long a = now.keys[ia], b = now.keys[ib];
            int c = Integer.compare(BlockPos.getX(a), BlockPos.getX(b));
            if (c != 0) return c;
            c = Integer.compare(BlockPos.getY(a), BlockPos.getY(b));
            if (c != 0) return c;
            return Integer.compare(BlockPos.getZ(a), BlockPos.getZ(b));
        });
        int total = candidates.size();
        JsonArray arr = new JsonArray();
        int n = Math.min(detailLimit, total);
        for (int i = 0; i < n; i++) {
            long key = now.keys[candidates.get(i)];
            BlockPos pos = BlockPos.of(key);
            BlockState state = now.states[candidates.get(i)];
            JsonObject b = new JsonObject();
            b.add("pos", intArray(pos.getX(), pos.getY(), pos.getZ()));
            b.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            JsonObject props = new JsonObject();
            try {
                for (Property<?> prop : state.getProperties()) {
                    props.addProperty(prop.getName(), state.getValue(prop).toString());
                }
            } catch (RuntimeException ignored) {
                // A property read must never take the whole report down.
            }
            b.add("properties", props);
            if (t.includeNbt) {
                try {
                    if (level.isLoaded(pos)) {
                        BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) {
                            String nbt = be.saveWithFullMetadata(level.registryAccess()).toString();
                            if (nbt.length() > NBT_SNIPPET_LIMIT) {
                                nbt = nbt.substring(0, NBT_SNIPPET_LIMIT) + "...(truncated)";
                            }
                            b.addProperty("nbt", nbt);
                        }
                    }
                } catch (RuntimeException ignored) {
                }
            }
            arr.add(b);
        }
        JsonObject o = new JsonObject();
        o.addProperty("block_count", total);
        o.add("blocks", arr);
        int truncatedCount = Math.max(0, total - detailLimit);
        return new Section(o, truncatedCount > 0, truncatedCount);
    }

    /** §7.3.3 snapshot entities. */
    static Section snapshotEntities(Sample now, Tracked t, int detailLimit) {
        List<EntityEntry> all = new ArrayList<>(now.entities.values());
        all.sort(Comparator.comparing(e -> e.uuid.toString()));
        int total = all.size();
        JsonArray arr = new JsonArray();
        int n = Math.min(detailLimit, total);
        for (int i = 0; i < n; i++) arr.add(all.get(i).toJson(t));
        JsonObject o = new JsonObject();
        o.addProperty("entity_count", total);
        o.add("entities", arr);
        int truncatedCount = Math.max(0, total - detailLimit);
        return new Section(o, truncatedCount > 0, truncatedCount);
    }

    // ------------------------------------------------------------------ json helpers
    static JsonArray intArray(int x, int y, int z) {
        JsonArray a = new JsonArray();
        a.add(x); a.add(y); a.add(z);
        return a;
    }

    static JsonArray intArray(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    static JsonArray doubleArray(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round3(x)); a.add(round3(y)); a.add(round3(z));
        return a;
    }

    static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    static double round3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    static Collection<EntityEntry> sortedEntries(Map<UUID, EntityEntry> map) {
        List<EntityEntry> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing(e -> e.uuid.toString()));
        return list;
    }
}
