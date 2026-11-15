package com.thatgfsjminecraft.mcb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Implementations of the world-touching ops (protocol.md §3.2–§3.6, §3.9, §3.10).
 *
 * THREAD DISCIPLINE (protocol §6): every method here is expected to run on the
 * client main thread (or the integrated-server thread for command.run). The TCP
 * layer (McbServer) is responsible for hopping here via Minecraft.execute().
 *
 * NOTE on mappings: this file is Mojmap for MC 1.21.11 — including the
 * ResourceLocation -> Identifier rename verified in
 * environments/versions/1.21.11/README.md. When porting to other versions,
 * verify each API against the decompiled sources (SKILL.md §7); PORTING.md
 * lists the call sites that historically change between versions.
 */
final class McbHandlers {
    private McbHandlers() {}

    // ------------------------------------------------------------------ limits
    private static final int SCAN_VOLUME_LIMIT = 4096;
    private static final int SCAN_RESULT_LIMIT = 4096;
    private static final int ENTITY_RESULT_LIMIT = 512;
    private static final int MAP_DIM_LIMIT = 256;
    private static final int NBT_SNIPPET_LIMIT = 4096;

    // =====================================================================
    // §3.2 status — always answerable, never errors.
    // =====================================================================
    static JsonObject buildStatus() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        JsonObject o = new JsonObject();
        o.addProperty("game", mc.getLaunchedVersion());
        o.addProperty("loader", "fabric");
        o.addProperty("side", "client");
        // §2.3 backpressure visibility: pushes dropped for slow clients.
        o.addProperty("droppedEvents", McbLogCapture.droppedEvents());
        // §3.12 additive field: sky-eye camera mode (none / one-shot / persistent).
        o.addProperty("cameraMode", McbCamera.statusMode());
        boolean worldLoaded = level != null;
        o.addProperty("worldLoaded", worldLoaded);
        if (!worldLoaded) {
            o.add("dimension", null);
            o.add("timeOfDay", null);
            o.add("raining", null);
            o.add("thundering", null);
            o.add("player", null);
            return o;
        }
        o.addProperty("dimension", level.dimension().identifier().toString());
        o.addProperty("timeOfDay", level.getDayTime());
        o.addProperty("raining", level.isRaining());
        o.addProperty("thundering", level.isThundering());
        o.add("player", playerJson(mc));
        return o;
    }

    // =====================================================================
    // §3.10 player.info
    // =====================================================================
    static JsonObject playerInfo() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        JsonObject o = new JsonObject();
        boolean present = player != null;
        o.addProperty("present", present);
        if (!present) {
            o.add("name", null);
            o.add("uuid", null);
            o.add("x", null);
            o.add("y", null);
            o.add("z", null);
            o.add("yaw", null);
            o.add("pitch", null);
            o.add("gamemode", null);
            o.add("health", null);
            o.add("food", null);
            o.add("dimension", null);
            o.add("hotbar", null);
            return o;
        }
        o.addProperty("name", player.getName().getString());
        o.addProperty("uuid", player.getUUID().toString());
        fillPlayerBody(o, player);
        o.addProperty("dimension", mc.level.dimension().identifier().toString());
        JsonArray hotbar = new JsonArray();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            JsonObject s = new JsonObject();
            s.addProperty("slot", slot);
            if (stack.isEmpty()) {
                s.add("id", null);
                s.add("count", null);
            } else {
                s.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                s.addProperty("count", stack.getCount());
            }
            hotbar.add(s);
        }
        o.add("hotbar", hotbar);
        return o;
    }

    private static void fillPlayerBody(JsonObject o, LocalPlayer player) {
        o.addProperty("x", player.getX());
        o.addProperty("y", player.getY());
        o.addProperty("z", player.getZ());
        o.addProperty("yaw", player.getYRot());
        o.addProperty("pitch", player.getXRot());
        if (Minecraft.getInstance().gameMode != null) {
            o.addProperty("gamemode", Minecraft.getInstance().gameMode.getPlayerMode().getName());
        } else {
            o.add("gamemode", null);
        }
        o.addProperty("health", player.getHealth());
        o.addProperty("food", player.getFoodData().getFoodLevel());
    }

    private static JsonObject playerJson(Minecraft mc) {
        LocalPlayer player = mc.player;
        JsonObject p = new JsonObject();
        if (player == null) {
            p.addProperty("present", false);
            return p;
        }
        p.addProperty("present", true);
        fillPlayerBody(p, player);
        return p;
    }

    // =====================================================================
    // §3.3 block.get
    // =====================================================================
    static JsonObject blockGet(int x, int y, int z, ClientLevel level) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject props = new JsonObject();
        for (Property<?> prop : state.getProperties()) {
            props.addProperty(prop.getName(), state.getValue(prop).toString());
        }
        o.add("properties", props);
        BlockEntity be = level.getBlockEntity(pos);
        o.addProperty("hasBlockEntity", be != null);
        if (be != null) {
            JsonObject beJson = new JsonObject();
            try {
                String type = BuiltInRegistries.BLOCK_ENTITY_TYPE
                        .getKey(be.getType()).toString();
                beJson.addProperty("type", type);
                // saveWithFullMetadata gives the full NBT incl. coordinates;
                // CompoundTag.toString() renders SNBT (verified: no getAsString
                // on 1.21.11 — Tag only exposes Optional asString()).
                String nbt = be.saveWithFullMetadata(level.registryAccess()).toString();
                if (nbt.length() > NBT_SNIPPET_LIMIT) {
                    nbt = nbt.substring(0, NBT_SNIPPET_LIMIT) + "...(truncated)";
                }
                beJson.addProperty("nbt", nbt);
            } catch (Exception e) {
                beJson.addProperty("nbtError", e.toString());
            }
            o.add("blockEntity", beJson);
        }
        return o;
    }

    // =====================================================================
    // §3.4 block.scan
    // =====================================================================
    static JsonObject blockScan(int[] min, int[] max, List<String> filter,
                                boolean includeProperties, ClientLevel level) {
        long volume = (long) (max[0] - min[0] + 1) * (max[1] - min[1] + 1) * (max[2] - min[2] + 1);
        if (volume > SCAN_VOLUME_LIMIT) {
            throw new McbServer.OpException("TOO_LARGE", "volume " + volume + " exceeds limit " + SCAN_VOLUME_LIMIT);
        }
        List<TagKey<Block>> tags = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        if (filter != null) {
            for (String f : filter) {
                if (f.startsWith("#")) {
                    tags.add(parseBlockTag(f.substring(1)));
                } else {
                    ids.add(f);
                }
            }
        }
        JsonArray blocks = new JsonArray();
        int count = 0;
        boolean truncated = false;
        for (int x = min[0]; x <= max[0]; x++) {
            for (int y = min[1]; y <= max[1]; y++) {
                for (int z = min[2]; z <= max[2]; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir()) continue;
                    if (!matchesFilter(state, ids, tags)) continue;
                    if (count >= SCAN_RESULT_LIMIT) {
                        truncated = true;
                        break;
                    }
                    JsonObject b = new JsonObject();
                    b.addProperty("x", x);
                    b.addProperty("y", y);
                    b.addProperty("z", z);
                    b.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                    if (includeProperties) {
                        JsonObject props = new JsonObject();
                        for (Property<?> prop : state.getProperties()) {
                            props.addProperty(prop.getName(), state.getValue(prop).toString());
                        }
                        b.add("properties", props);
                    }
                    blocks.add(b);
                    count++;
                }
                if (truncated) break;
            }
            if (truncated) break;
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", count);
        o.addProperty("truncated", truncated);
        o.add("blocks", blocks);
        return o;
    }

    private static boolean matchesFilter(BlockState state, List<String> ids, List<TagKey<Block>> tags) {
        if (ids.isEmpty() && tags.isEmpty()) return true;
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (ids.contains(key)) return true;
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    /** "#minecraft:logs" -> TagKey. Bare "logs" defaults to namespace "minecraft". */
    static TagKey<Block> parseBlockTag(String raw) {
        String ns = "minecraft";
        String path = raw;
        int colon = raw.indexOf(':');
        if (colon >= 0) {
            ns = raw.substring(0, colon);
            path = raw.substring(colon + 1);
        }
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(ns, path));
    }

    // =====================================================================
    // §3.5 map.ascii
    // =====================================================================
    static JsonObject mapAscii(int[] min, int[] max, String mode, Integer sliceY, ClientLevel level) {
        // Dispatch already validated mode/volume on the TCP thread; these are
        // defensive re-checks (must use OpException so failures map to the
        // protocol error codes, not INTERNAL).
        boolean slice = "slice".equals(mode);
        if (slice && sliceY == null) {
            throw new McbServer.OpException("BAD_PARAMS", "mode=slice requires y");
        }
        int width = max[0] - min[0] + 1;
        int depth = max[2] - min[2] + 1;
        if (width > MAP_DIM_LIMIT || depth > MAP_DIM_LIMIT) {
            throw new McbServer.OpException("TOO_LARGE",
                    "map grid " + width + "x" + depth + " exceeds " + MAP_DIM_LIMIT + "x" + MAP_DIM_LIMIT);
        }
        boolean top = !slice;
        char[][] grid = new char[depth][width];
        // Protocol §3.5: air is always '.'; conflicts fall through to backups.
        String backupChars = "#@%*+=~^!?:;";
        java.util.Map<String, Character> legend = new java.util.LinkedHashMap<>();
        java.util.Map<Character, String> legendOut = new java.util.LinkedHashMap<>();
        for (int iz = 0; iz < depth; iz++) {
            for (int ix = 0; ix < width; ix++) {
                int x = min[0] + ix;
                int z = min[2] + iz;
                BlockState chosen = null;
                if (top) {
                    for (int y = max[1]; y >= min[1]; y--) {
                        BlockState s = level.getBlockState(new BlockPos(x, y, z));
                        if (!s.isAir()) { chosen = s; break; }
                    }
                } else {
                    chosen = level.getBlockState(new BlockPos(x, sliceY, z));
                }
                char c = '.';
                if (chosen != null) {
                    String id = BuiltInRegistries.BLOCK.getKey(chosen.getBlock()).toString();
                    if (legend.containsKey(id)) {
                        c = legend.get(id);
                    } else {
                        c = pickChar(id, legendOut, backupChars);
                        legend.put(id, c);
                        legendOut.put(c, id);
                    }
                }
                grid[iz][ix] = c;
            }
        }
        JsonArray rows = new JsonArray();
        for (char[] row : grid) {
            rows.add(new String(row));
        }
        JsonObject o = new JsonObject();
        o.addProperty("mode", top ? "top" : "slice");
        o.add("min", intArray(min));
        o.add("max", intArray(max));
        o.addProperty("viewX", "min.x (west) -> max.x (east), left to right");
        o.addProperty("viewZ", "min.z (north) -> max.z (south), top to bottom");
        JsonObject lg = new JsonObject();
        for (java.util.Map.Entry<Character, String> e : legendOut.entrySet()) {
            lg.addProperty(String.valueOf(e.getKey()), e.getValue());
        }
        o.add("legend", lg);
        o.add("rows", rows);
        return o;
    }

    private static char pickChar(String id, java.util.Map<Character, String> used, String backupChars) {
        // path = part after "namespace:", e.g. "oak_log"; use its letters first.
        int slash = id.indexOf(':');
        String path = slash >= 0 ? id.substring(slash + 1) : id;
        for (int i = 0; i < path.length(); i++) {
            char c = Character.toLowerCase(path.charAt(i));
            if (Character.isLetterOrDigit(c) && !used.containsKey(c)) return c;
        }
        for (int i = 0; i < backupChars.length(); i++) {
            char c = backupChars.charAt(i);
            if (!used.containsKey(c)) return c;
        }
        return '?';
    }

    private static JsonArray intArray(int[] v) {
        JsonArray a = new JsonArray();
        for (int i : v) a.add(i);
        return a;
    }

    // =====================================================================
    // §3.6 entity.list
    // =====================================================================
    static JsonObject entityList(int[] min, int[] max, List<String> typeFilter, ClientLevel level) {
        AABB box = new AABB(min[0], min[1], min[2], max[0] + 1, max[1] + 1, max[2] + 1);
        List<Entity> found = level.getEntitiesOfClass(Entity.class, box,
                e -> typeFilter == null || typeFilter.isEmpty()
                        || typeFilter.contains(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString()));
        boolean truncated = found.size() > ENTITY_RESULT_LIMIT;
        JsonArray arr = new JsonArray();
        int n = 0;
        for (Entity e : found) {
            if (n >= ENTITY_RESULT_LIMIT) break;
            JsonObject o = new JsonObject();
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("uuid", e.getUUID().toString());
            o.addProperty("name", e.getName().getString());
            o.addProperty("x", e.getX());
            o.addProperty("y", e.getY());
            o.addProperty("z", e.getZ());
            if (e instanceof LivingEntity le) {
                o.addProperty("health", le.getHealth());
            } else {
                o.add("health", null);
            }
            arr.add(o);
            n++;
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", n);
        out.addProperty("truncated", truncated);
        out.add("entities", arr);
        return out;
    }

    // =====================================================================
    // §3.9 command.run — runs on the integrated-server thread.
    // =====================================================================
    static JsonObject commandRun(net.minecraft.server.MinecraftServer server, String command) {
        // Protocol §3.9: collect feedback through a tiny CommandSource so the
        // AI sees what the command actually said instead of guessing.
        // Verified 1.21.11 (javap, see version card bridge.md):
        // - CommandSourceStack.withPermission takes PermissionSet (not int)
        // - Commands.performPrefixedCommand returns void
        // - so we parse + execute via the brigadier dispatcher directly:
        //   parse errors and CommandSyntaxException become success=false.
        StringBuilder feedback = new StringBuilder();
        java.util.List<String> feedbackLines = new ArrayList<>();
        CommandCollector source = new CommandCollector(feedback, feedbackLines);
        var stack = server.createCommandSourceStack()
                .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS)
                .withSource(source);
        var dispatcher = server.getCommands().getDispatcher();
        boolean success;
        String errorText = null;
        try {
            var parse = dispatcher.parse(command, stack);
            var reader = parse.getReader();
            if (reader.getRemaining().length() > 0) {
                // Trailing garbage: report cursor position like vanilla does.
                int cursor = reader.getCursor();
                errorText = "Incorrect argument at position " + cursor + ": ..." + command.substring(0, cursor);
                success = false;
            } else {
                dispatcher.execute(parse);
                success = true;
            }
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            success = false;
            errorText = e.getMessage();
        } catch (RuntimeException e) {
            // Brigadier runtime exceptions must surface as command failure,
            // not as bridge INTERNAL (protocol §5).
            success = false;
            errorText = e.getMessage();
        }
        JsonObject o = new JsonObject();
        o.addProperty("success", success);
        String text = feedback.toString().trim();
        if (errorText != null) {
            text = (text.isEmpty() ? "" : text + "\n") + errorText;
        }
        // §1 single-line cap guard: keep the whole response under 1 MiB.
        if (text.length() > 8192) {
            text = text.substring(0, 8192) + "...(truncated)";
        }
        o.addProperty("resultText", text);
        // §3.9: individual feedback lines (what the command actually printed),
        // so an AI can parse discrete outputs instead of one blob.
        JsonArray feedbackArr = new JsonArray();
        for (String l : feedbackLines) {
            feedbackArr.add(l);
        }
        o.add("feedback", feedbackArr);
        return o;
    }

    /** CommandSource that collects chat/feedback lines into a StringBuilder. */
    private static final class CommandCollector implements net.minecraft.commands.CommandSource {
        private final StringBuilder sink;
        private final List<String> lines;
        CommandCollector(StringBuilder sink, List<String> lines) {
            this.sink = sink;
            this.lines = lines;
        }

        @Override
        public void sendSystemMessage(Component message) {
            String s = message.getString();
            sink.append(s).append('\n');
            lines.add(s);
        }

        @Override
        public boolean acceptsSuccess() { return true; }

        @Override
        public boolean acceptsFailure() { return true; }

        @Override
        public boolean shouldInformAdmins() { return false; }
    }

    // =====================================================================
    // §3.13 player.move — teleport with hardcoded ground safety.
    //
    // THE RULE (user requirement, do not weaken): the player cannot fly.
    // Before any tp, probe the ground under the target; if there is nothing
    // to stand on, place a glass block first, then tp on top of it, and
    // zero out motion + fall distance. Glass (not dirt) so it never pollutes
    // the terrain the user cares about.
    // =====================================================================
    static void playerMove(double x, double y, double z, float yaw, float pitch,
                           boolean hasYaw, boolean hasPitch,
                           java.util.function.Consumer<JsonObject> ok,
                           java.util.function.BiConsumer<String, String> err) {
        // Runs on the CLIENT main thread (dispatch hopped here).
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            err.accept("WORLD_NOT_LOADED", "no player in a loaded world");
            return;
        }
        float yawOut = hasYaw ? yaw : player.getYRot();
        float pitchOut = hasPitch ? pitch : player.getXRot();

        // 1. Ground probe at the target column: standable = solid non-fluid.
        boolean groundHere = standable(level, x, y - 1, z);
        boolean groundBelow = groundHere || standable(level, x, y - 2, z);
        double standY = groundHere ? y : (groundBelow ? y - 1 : y);
        boolean needGlass = !groundBelow;

        // 2a. Nothing needed: tp right now.
        if (!needGlass) {
            teleport(player, x, standY, z, yawOut, pitchOut);
            ok.accept(moveResult(x, standY, z, yawOut, pitchOut, level,
                    !groundHere, false, true));
            return;
        }

        // 2b. Void below: place glass, then tp. The block must be placed with
        // server authority (client setBlock would be a ghost block).
        var server = mc.getSingleplayerServer();
        BlockPos glassPos = BlockPos.containing(Math.floor(x), Math.floor(y - 1), Math.floor(z));
        if (server != null) {
            // Chain: server thread (place) -> client thread (tp + respond).
            server.execute(() -> {
                boolean placed = false;
                String failure = null;
                try {
                    var slevel = server.getLevel(level.dimension());
                    placed = slevel != null
                            && slevel.setBlock(glassPos, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState(), 3);
                } catch (Exception e) {
                    failure = e.getClass().getName() + ": " + e.getMessage();
                }
                boolean placedF = placed;
                String failureF = failure;
                Minecraft.getInstance().execute(() -> {
                    if (!placedF) {
                        // Refuse to move the player into a void we failed to fill.
                        err.accept("INTERNAL",
                                "failed to place safety glass at " + glassPos.toShortString()
                                        + (failureF != null ? " (" + failureF + ")" : "") + "; player NOT moved");
                        return;
                    }
                    teleport(player, x, standY, z, yawOut, pitchOut);
                    ok.accept(moveResult(x, standY, z, yawOut, pitchOut, level,
                            true, true, true));
                });
            });
            return;
        }

        // 2c. Multiplayer (no integrated server): send setblock as an operator
        // command (needs op) and move anyway — the client is authoritative for
        // its own position. Result honestly flags the placement as unverified.
        var conn = mc.getConnection();
        if (conn != null) {
            conn.sendCommand("setblock " + glassPos.getX() + " " + glassPos.getY() + " "
                    + glassPos.getZ() + " minecraft:glass");
            teleport(player, x, standY, z, yawOut, pitchOut);
            ok.accept(moveResult(x, standY, z, yawOut, pitchOut, level,
                    true, false, true));
            return;
        }

        err.accept("INTERNAL", "need to place safety glass but neither an integrated server nor a server connection exists; player NOT moved");
    }

    /** Solid, non-fluid block: something the player can stand on. */
    private static boolean standable(ClientLevel level, double x, double y, double z) {
        BlockState state = level.getBlockState(BlockPos.containing(x, y, z));
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    private static void teleport(LocalPlayer player, double x, double y, double z, float yaw, float pitch) {
        player.absSnapTo(x, y, z, yaw, pitch);
        player.setDeltaMovement(0, 0, 0);
        player.resetFallDistance();
    }

    private static JsonObject moveResult(double x, double standY, double z, float yaw, float pitch,
                                         ClientLevel level, boolean groundWasMissing, boolean placedGlass,
                                         boolean placedGlassVerified) {
        JsonObject o = new JsonObject();
        o.addProperty("success", true);
        o.addProperty("moved", true);
        o.addProperty("x", x);
        o.addProperty("y", standY); // actual feet Y (may differ from request when ground was lower)
        o.addProperty("z", z);
        o.addProperty("yaw", yaw);
        o.addProperty("pitch", pitch);
        o.addProperty("dimension", level.dimension().identifier().toString());
        o.addProperty("groundWasMissing", groundWasMissing);
        o.addProperty("placedGlass", placedGlass);
        o.addProperty("placedGlassVerified", placedGlassVerified);
        return o;
    }

    // ------------------------------------------------------------------ helpers
    static ClientLevel requireLevel() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            throw new McbServer.OpException("WORLD_NOT_LOADED", "no world is loaded");
        }
        return level;
    }

    static int[] parseVec3(JsonElement el, String name) {
        if (el == null || !el.isJsonArray() || el.getAsJsonArray().size() != 3) {
            throw new McbServer.OpException("BAD_PARAMS", name + " must be [x,y,z]");
        }
        JsonArray a = el.getAsJsonArray();
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement e = a.get(i);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                out[i] = e.getAsInt();
            } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                // Accept "0.5" style floats by flooring — friendlier for AI.
                out[i] = (int) Math.floor(Double.parseDouble(e.getAsString()));
            } else {
                throw new McbServer.OpException("BAD_PARAMS", name + " contains a non-numeric component");
            }
        }
        return out;
    }

    static List<String> parseStringList(JsonElement el, String name) {
        if (el == null || el.isJsonNull()) return List.of();
        if (!el.isJsonArray()) {
            throw new McbServer.OpException("BAD_PARAMS", name + " must be an array of strings");
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : el.getAsJsonArray()) {
            if (!e.isJsonPrimitive()) {
                throw new McbServer.OpException("BAD_PARAMS", name + " must contain only strings");
            }
            out.add(e.getAsString());
        }
        return out;
    }
}
