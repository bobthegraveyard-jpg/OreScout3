package dev.orescout.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.orescout.worldgen.LocalChunkPipeline;
import dev.orescout.worldgen.LocalWorldGenLevel;
import dev.orescout.worldgen.VanillaDecorationPlanner;
import dev.orescout.worldgen.VanillaFeatureSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Executes the vanilla 26.2 Overworld decoration pass in a private local chunk
 * set and extracts ore coordinates. This class accepts only the fixed world
 * seed and chunk coordinates; it has no ClientLevel or ServerLevel parameter.
 *
 * <p>Current scope is terrain/carvers plus placed-feature decoration. Structure
 * starts and references are not yet simulated, so this result must not be
 * advertised as an exact final vanilla world map until the structure stage and
 * regression fixtures are complete.</p>
 */
public final class SeedOreGenerationEngine implements AutoCloseable {
    public static final long TARGET_SEED = SeedWorldKnowledge.WORLD_SEED;

    private final LocalChunkPipeline pipeline;
    private final RandomState randomState;
    private final NoiseBasedChunkGenerator generator;
    private final VanillaFeatureSchedule schedule;
    private final VanillaDecorationPlanner planner;
    private final int minY;
    private final int height;

    public SeedOreGenerationEngine() {
        pipeline = new LocalChunkPipeline();
        generator = pipeline.generator();
        randomState = pipeline.randomState(TARGET_SEED);
        minY = pipeline.heightAccessor().getMinY();
        height = pipeline.heightAccessor().getHeight();
        schedule = VanillaFeatureSchedule.from(generator, pipeline.registries());
        planner = new VanillaDecorationPlanner(generator, schedule, minY, height);
    }

    /**
     * Generates the requested chunk from seed-derived local data only.
     * All nine biome-neighborhood chunks are generated locally; only the
     * requested center chunk is decorated and scanned.
     */
    public GeneratedOreChunk generate(int chunkX, int chunkZ) {
        ChunkPos center = new ChunkPos(chunkX, chunkZ);
        Map<ChunkPos, ProtoChunk> localChunks = new LinkedHashMap<>();

        // Every source chunk in the target's 3x3 write neighborhood needs its
        // own 3x3 biome neighborhood. Generate the resulting 5x5 terrain halo.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = chunkX + dx;
                int z = chunkZ + dz;
                ProtoChunk chunk = pipeline.generate(TARGET_SEED, randomState, x, z);
                localChunks.put(chunk.getPos(), chunk);
            }
        }

        ProtoChunk target = localChunks.get(center);
        int placedCalls = 0;

        // Vanilla features may write across their source chunk boundary. Replay
        // all nine source chunks, each with the same 3x3 writable region that a
        // vanilla WorldGenRegion provides, then read only the requested center.
        for (int sourceDx = -1; sourceDx <= 1; sourceDx++) {
            for (int sourceDz = -1; sourceDz <= 1; sourceDz++) {
                ChunkPos source = new ChunkPos(chunkX + sourceDx, chunkZ + sourceDz);
                List<VanillaFeatureSchedule.DecorationStep> selected =
                        planner.select(source, localChunks);
                java.util.Set<ChunkPos> writableChunks = new java.util.HashSet<>();
                for (int writeDx = -1; writeDx <= 1; writeDx++) {
                    for (int writeDz = -1; writeDz <= 1; writeDz++) {
                        writableChunks.add(new ChunkPos(
                                source.x() + writeDx, source.z() + writeDz));
                    }
                }

                LocalWorldGenLevel localLevel = new LocalWorldGenLevel(
                        localChunks,
                        writableChunks,
                        pipeline.registries(),
                        pipeline.biomeManager(TARGET_SEED, randomState),
                        generator,
                        TARGET_SEED,
                        minY,
                        height);
                WorldGenLevel level = localLevel.view();
                BlockPos origin = new BlockPos(
                        source.getMinBlockX(), minY, source.getMinBlockZ());

                for (VanillaFeatureSchedule.DecorationStep step : selected) {
                    for (VanillaFeatureSchedule.FeatureEntry entry : step.features()) {
                        WorldgenRandom random = VanillaDecorationPlanner.randomFor(
                                TARGET_SEED, source, step.step(), entry.index());
                        place(entry.feature(), level, generator, random, origin);
                        placedCalls++;
                    }
                }
            }
        }

        List<SeedWorldKnowledge.OrePosition> ores = extractOres(target);
        return new GeneratedOreChunk(
                chunkX, chunkZ, ores, placedCalls, pipeline.summarize(target));
    }

    private static void place(
            PlacedFeature feature,
            WorldGenLevel level,
            NoiseBasedChunkGenerator generator,
            WorldgenRandom random,
            BlockPos origin) {
        Method placement = null;
        for (Method method : feature.getClass().getMethods()) {
            if (!(method.getName().equals("place")
                    || method.getName().equals("generate")
                    || method.getName().equals("placeWithBiomeCheck"))) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 4
                    && parameters[0].isInstance(level)
                    && parameters[1].isInstance(generator)
                    && parameters[2].isInstance(random)
                    && parameters[3].isInstance(origin)) {
                if (method.getName().equals("placeWithBiomeCheck")) {
                    placement = method;
                    break;
                }
                if (placement == null || method.getName().equals("place")) {
                    placement = method;
                }
            }
        }
        if (placement == null) {
            throw new IllegalStateException(
                    "No compatible vanilla placement method for " + feature);
        }
        try {
            placement.invoke(feature, level, generator, random, origin);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot invoke vanilla placed feature " + feature, exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(
                    "Vanilla placed feature failed: " + feature, exception.getCause());
        }
    }

    private static List<SeedWorldKnowledge.OrePosition> extractOres(ChunkAccess chunk) {
        java.util.ArrayList<SeedWorldKnowledge.OrePosition> result = new java.util.ArrayList<>();
        ChunkPos pos = chunk.getPos();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight();

        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = chunk.getBlockState(cursor.set(
                            pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z));
                    String oreId = oreId(state.getBlock());
                    if (oreId != null) {
                        result.add(new SeedWorldKnowledge.OrePosition(
                                pos.getMinBlockX() + x, y, pos.getMinBlockZ() + z, oreId));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static String oreId(Block block) {
        if (block == Blocks.DIAMOND_ORE) return "Diamond";
        if (block == Blocks.DEEPSLATE_DIAMOND_ORE) return "Deepslate diamond";
        if (block == Blocks.EMERALD_ORE) return "Emerald";
        if (block == Blocks.DEEPSLATE_EMERALD_ORE) return "Deepslate emerald";
        if (block == Blocks.COAL_ORE) return "Coal";
        if (block == Blocks.DEEPSLATE_COAL_ORE) return "Deepslate coal";
        if (block == Blocks.GOLD_ORE) return "Gold";
        if (block == Blocks.DEEPSLATE_GOLD_ORE) return "Deepslate gold";
        if (block == Blocks.IRON_ORE) return "Iron";
        if (block == Blocks.DEEPSLATE_IRON_ORE) return "Deepslate iron";
        if (block == Blocks.COPPER_ORE) return "Copper";
        if (block == Blocks.DEEPSLATE_COPPER_ORE) return "Deepslate copper";
        if (block == Blocks.LAPIS_ORE) return "Lapis";
        if (block == Blocks.DEEPSLATE_LAPIS_ORE) return "Deepslate lapis";
        if (block == Blocks.REDSTONE_ORE) return "Redstone";
        if (block == Blocks.DEEPSLATE_REDSTONE_ORE) return "Deepslate redstone";
        if (block == Blocks.NETHER_GOLD_ORE) return "Nether gold";
        if (block == Blocks.NETHER_QUARTZ_ORE) return "Nether quartz";
        if (block == Blocks.ANCIENT_DEBRIS) return "Ancient debris";
        return null;
    }

    @Override
    public void close() throws Exception {
        pipeline.close();
    }

    public record GeneratedOreChunk(
            int chunkX,
            int chunkZ,
            List<SeedWorldKnowledge.OrePosition> ores,
            int placedFeatureCalls,
            String terrainAndDecorationSummary) {
        public GeneratedOreChunk {
            ores = List.copyOf(ores);
        }
    }
}
