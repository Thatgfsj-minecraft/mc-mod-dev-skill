package com.thatgfsjminecraft.mcb;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.List;
import java.util.Optional;

/**
 * World construction for protocol §8 (v0.5.4) — the pure "settings/terrain" half
 * of {@link McbWorldOps}.
 *
 * <p>Split out of McbWorldOps to keep every code file under the repo's 1000-line
 * budget. Nothing here touches threads, the network or the client state machine:
 * every method is a pure builder that {@link McbWorldOps}'s tick state machine
 * calls on the client thread while creating the world.
 *
 * <p>1.21.11 notes (verified against decompiled Mojmap sources, see
 * environments/versions/1.21.11/bridge.md): gamerules live in
 * net.minecraft.world.level.gamerules and doFireTick no longer exists, so
 * {@code fire_tick=false} maps to {@code fire_spread_radius_around_player=0}
 * ({@code -1} would mean "no spread-radius restriction"). Flat worlds also need
 * an explicit structure-override set: an empty {@code Optional} makes
 * FlatLevelSource fall back to "every registered structure set".
 */
final class McbWorldPresets {
    private McbWorldPresets() {}

    /** §8.1: LevelSettings carries game type, difficulty, cheats and initial gamerules. */
    static LevelSettings levelSettings(McbWorldOps.CreateRequest req) {
        GameRules rules = new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures());
        // §8.3 mapping: 1.21.11 gamerules are snake_case and doFireTick is gone.
        rules.set(GameRules.SPAWN_MOBS, req.doMobSpawning(), null);
        rules.set(GameRules.KEEP_INVENTORY, req.keepInventory(), null);
        // fire_tick=false -> fire_spread_radius_around_player=0 (1.21.11 has no doFireTick).
        rules.set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, req.fireTick() ? -1 : 0, null);
        if (req.lockTime() != null) rules.set(GameRules.ADVANCE_TIME, false, null);
        if (req.lockWeather() != null) rules.set(GameRules.ADVANCE_WEATHER, false, null);
        return new LevelSettings(req.name(), req.gameMode(), false, req.difficulty(),
                req.allowCommands(), rules, WorldDataConfiguration.DEFAULT);
    }

    /** §8.1: the dimensions function handed to createFreshLevel. */
    static WorldDimensions dimensions(McbWorldOps.CreateRequest req, HolderLookup.Provider provider) {
        return switch (req.preset()) {
            case VANILLA_QUIET -> WorldPresets.createNormalWorldDimensions(provider);
            case FLAT_CLEAN -> flatDimensions(provider, req.generateStructures(), false);
            case BEDROCK_LAB -> flatDimensions(provider, req.generateStructures(), true);
            case VOID_PLATFORM -> voidDimensions(provider);
        };
    }

    /**
     * Flat preset (bedrock_lab / flat_clean): superflat overworld only, all other
     * dimensions stay vanilla.
     *
     * §8.3 note: WorldOptions.generateStructures already gates structure
     * generation server-side, but the flat generator ALSO needs an explicit
     * structure override set — otherwise FlatLevelSource falls back to "every
     * registered structure set". So: generate_structures=false -> empty override
     * set; true -> the vanilla superflat set (strongholds + villages).
     */
    private static WorldDimensions flatDimensions(HolderLookup.Provider provider, boolean structures,
                                                  boolean bedrockOnly) {
        HolderGetter<Biome> biomes = provider.lookupOrThrow(Registries.BIOME);
        HolderGetter<PlacedFeature> features = provider.lookupOrThrow(Registries.PLACED_FEATURE);
        HolderGetter<StructureSet> structureSets = provider.lookupOrThrow(Registries.STRUCTURE_SET);
        Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);

        Optional<HolderSet<StructureSet>> overrides = structures
                ? Optional.of(HolderSet.direct(
                        structureSets.getOrThrow(BuiltinStructureSets.STRONGHOLDS),
                        structureSets.getOrThrow(BuiltinStructureSets.VILLAGES)))
                : Optional.of(HolderSet.empty());

        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
                overrides, plains, FlatLevelGeneratorSettings.createLakesList(features));
        if (bedrockOnly) {
            settings.getLayersInfo().add(new FlatLayerInfo(3, Blocks.BEDROCK));
        } else {
            settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
            settings.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
            settings.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
        }
        settings.updateLayers();
        return WorldPresets.createNormalWorldDimensions(provider)
                .replaceOverworldGenerator(provider, new FlatLevelSource(settings));
    }

    /** void_platform: all-air superflat (void) — the platform itself is placed in McbWorldOps#preApply. */
    private static WorldDimensions voidDimensions(HolderLookup.Provider provider) {
        HolderGetter<Biome> biomes = provider.lookupOrThrow(Registries.BIOME);
        Holder<Biome> plains = biomes.getOrThrow(Biomes.PLAINS);
        FlatLevelGeneratorSettings settings = new FlatLevelGeneratorSettings(
                Optional.of(HolderSet.empty()), plains, List.of());
        settings.updateLayers(); // no layers -> all air -> voidGen
        return WorldPresets.createNormalWorldDimensions(provider)
                .replaceOverworldGenerator(provider, new FlatLevelSource(settings));
    }
}
