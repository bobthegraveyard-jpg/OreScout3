package dev.orescout.worldgen;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Reconstructs the vanilla feature selection and random stream for one chunk.
 *
 * <p>Vanilla selects features from the union of the biome palettes in the
 * surrounding 3x3 chunks, intersects that set with the biome source's possible
 * biomes, maps each selected feature into the generator's globally indexed
 * per-step order, and runs the selected indices in ascending order. This class
 * performs that selection against OreScout-owned ProtoChunks only.</p>
 *
 * <p>It deliberately does not place features. The caller must supply a local
 * world-generation adapter and execute each returned entry using
 * {@link #randomFor(long, ChunkPos, int, int)}. No live level or server chunk
 * is accepted by this API.</p>
 */
public final class VanillaDecorationPlanner {
    private final NoiseBasedChunkGenerator generator;
    private final VanillaFeatureSchedule schedule;
    private final int minY;
    private final int height;

    public VanillaDecorationPlanner(
            NoiseBasedChunkGenerator generator,
            VanillaFeatureSchedule schedule,
            int minY,
            int height) {
        this.generator = generator;
        this.schedule = schedule;
        this.minY = minY;
        this.height = height;
    }

    /**
     * Selects exactly the globally indexed placed features contributed by the
     * biomes present in the source chunk's 3x3 neighborhood.
     *
     * @param center target/source chunk whose decoration is being planned
     * @param localChunks map of OreScout-generated chunks, including the full 3x3 neighborhood
     */
    public List<VanillaFeatureSchedule.DecorationStep> select(
            ChunkPos center,
            Map<ChunkPos, ? extends ChunkAccess> localChunks) {
        Set<Holder<Biome>> neighborhoodBiomes = collectNeighborhoodBiomes(center, localChunks);
        List<VanillaFeatureSchedule.DecorationStep> selectedSteps = new ArrayList<>();

        for (VanillaFeatureSchedule.DecorationStep step : schedule.steps()) {
            Map<PlacedFeature, Integer> globalIndices = new HashMap<>();
            for (VanillaFeatureSchedule.FeatureEntry entry : step.features()) {
                globalIndices.put(entry.feature(), entry.index());
            }

            TreeSet<Integer> selectedIndices = new TreeSet<>();
            for (Holder<Biome> biome : neighborhoodBiomes) {
                List<?> biomeSteps = featureSteps(generator.getBiomeGenerationSettings(biome));
                if (step.step() >= biomeSteps.size()) {
                    continue;
                }

                Object featureSet = biomeSteps.get(step.step());
                if (!(featureSet instanceof Iterable<?> holders)) {
                    throw new IllegalStateException(
                            "Biome feature step " + step.step() + " is not iterable");
                }

                for (Object value : holders) {
                    if (!(value instanceof Holder<?> holder)
                            || !(holder.value() instanceof PlacedFeature placedFeature)) {
                        throw new IllegalStateException(
                                "Unexpected value in biome placed-feature list: " + value);
                    }
                    Integer index = globalIndices.get(placedFeature);
                    if (index != null) {
                        selectedIndices.add(index);
                    }
                }
            }

            List<VanillaFeatureSchedule.FeatureEntry> ordered = new ArrayList<>(selectedIndices.size());
            for (int index : selectedIndices) {
                VanillaFeatureSchedule.FeatureEntry entry = step.features().get(index);
                if (entry.index() != index) {
                    throw new IllegalStateException("Vanilla feature index mapping is inconsistent");
                }
                ordered.add(entry);
            }
            selectedSteps.add(new VanillaFeatureSchedule.DecorationStep(step.step(), ordered));
        }
        return List.copyOf(selectedSteps);
    }

    private Set<Holder<Biome>> collectNeighborhoodBiomes(
            ChunkPos center,
            Map<ChunkPos, ? extends ChunkAccess> localChunks) {
        Set<Holder<Biome>> biomes = new HashSet<>();
        int minQuartY = Math.floorDiv(minY, 4);
        int maxQuartY = Math.floorDiv(minY + height - 1, 4);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos pos = new ChunkPos(center.x() + dx, center.z() + dz);
                ChunkAccess chunk = localChunks.get(pos);
                if (chunk == null) {
                    throw new IllegalArgumentException(
                            "Missing locally generated 3x3 biome-neighborhood chunk " + pos);
                }

                int minQuartX = Math.floorDiv(pos.getMinBlockX(), 4);
                int minQuartZ = Math.floorDiv(pos.getMinBlockZ(), 4);
                for (int quartY = minQuartY; quartY <= maxQuartY; quartY++) {
                    for (int quartZ = minQuartZ; quartZ < minQuartZ + 4; quartZ++) {
                        for (int quartX = minQuartX; quartX < minQuartX + 4; quartX++) {
                            biomes.add(chunk.getNoiseBiome(quartX, quartY, quartZ));
                        }
                    }
                }
            }
        }
        return biomes;
    }

    private static List<?> featureSteps(Object generationSettings) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : generationSettings.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && List.class.isAssignableFrom(method.getReturnType())) {
                candidates.add(method);
            }
        }
        for (Method method : generationSettings.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && List.class.isAssignableFrom(method.getReturnType())
                    && !candidates.contains(method)) {
                candidates.add(method);
            }
        }

        for (Method method : candidates) {
            try {
                method.setAccessible(true);
                Object value = method.invoke(generationSettings);
                if (value instanceof List<?> list && isPlacedFeatureStepList(list, method)) {
                    return list;
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                // Keep inspecting candidates; the correct accessor is identified by its value type.
            }
        }
        throw new IllegalStateException(
                "Could not identify Minecraft 26.2's biome placed-feature step list on "
                        + generationSettings.getClass().getName());
    }

    private static boolean isPlacedFeatureStepList(List<?> steps, Method accessor) {
        for (Object step : steps) {
            if (!(step instanceof Iterable<?> entries)) {
                return false;
            }
            for (Object entry : entries) {
                if (entry instanceof Holder<?> holder
                        && holder.value() instanceof PlacedFeature) {
                    return true;
                }
                if (entry instanceof PlacedFeature) {
                    return true;
                }
            }
        }
        String genericType = accessor.getGenericReturnType().getTypeName();
        return genericType.contains("PlacedFeature");
    }

    /**
     * Creates the vanilla per-feature random stream for one selected feature.
     * The decoration seed uses the source chunk's minimum block coordinates;
     * the feature seed then incorporates its global index and decoration step.
     */
    public static WorldgenRandom randomFor(
            long worldSeed,
            ChunkPos sourceChunk,
            int decorationStep,
            int featureIndex) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        long decorationSeed = random.setDecorationSeed(
                worldSeed, sourceChunk.getMinBlockX(), sourceChunk.getMinBlockZ());
        random.setFeatureSeed(decorationSeed, featureIndex, decorationStep);
        return random;
    }

    /** Vanilla's origin position for a chunk's decoration pass. */
    public BlockPosOrigin origin(ChunkPos sourceChunk) {
        return new BlockPosOrigin(sourceChunk.getMinBlockX(), minY, sourceChunk.getMinBlockZ());
    }

    public record BlockPosOrigin(int x, int y, int z) {}
}
