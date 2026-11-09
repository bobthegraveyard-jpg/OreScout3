package dev.orescout.worldgen;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Reads the exact per-decoration-step PlacedFeature order built by vanilla's
 * PlacedFeatureIndexer for the Overworld generator.
 *
 * <p>Vanilla computes this order from the generator's complete possible-biome
 * set, using its own feature-order constraints. Reimplementing that topological
 * sort would risk subtle divergence, so OreScout asks the 26.2 generator to
 * initialise its own memoized index and reads the resulting immutable schedule.
 * This class does not execute features and does not access a live world.</p>
 *
 * <p>The private field is located structurally (by its Supplier type), rather
 * than by a string field name, so runtime remapping does not break the lookup.
 * The indexed-feature value is likewise read by its List field. A layout
 * mismatch fails closed instead of silently producing a guessed schedule.</p>
 */
public final class VanillaFeatureSchedule {
    private final List<DecorationStep> steps;

    private VanillaFeatureSchedule(List<DecorationStep> steps) {
        this.steps = List.copyOf(steps);
    }

    public static VanillaFeatureSchedule from(
            net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator,
            RegistryAccess registries) {
        try {
            generator.initializeIndexedFeaturesList();
            Object indexedSteps = readIndexedSteps(generator);
            if (!(indexedSteps instanceof List<?> rawSteps)) {
                throw new IllegalStateException("Vanilla feature index did not return a list");
            }

            Registry<PlacedFeature> featureRegistry =
                    registries.lookupOrThrow(Registries.PLACED_FEATURE);
            List<DecorationStep> result = new ArrayList<>(rawSteps.size());

            for (int stepIndex = 0; stepIndex < rawSteps.size(); stepIndex++) {
                Object indexedStep = rawSteps.get(stepIndex);
                Object rawFeatures = readFeatureList(indexedStep);
                if (!(rawFeatures instanceof List<?> featureList)) {
                    throw new IllegalStateException(
                            "Vanilla indexed decoration step " + stepIndex + " has no feature list");
                }

                List<FeatureEntry> entries = new ArrayList<>(featureList.size());
                for (int featureIndex = 0; featureIndex < featureList.size(); featureIndex++) {
                    Object value = featureList.get(featureIndex);
                    if (!(value instanceof PlacedFeature feature)) {
                        throw new IllegalStateException(
                                "Unexpected value in vanilla feature schedule: "
                                        + value.getClass().getName());
                    }
                    Identifier id = featureRegistry.getKey(feature).orElseThrow(
                            () -> new IllegalStateException("Unregistered vanilla placed feature"));
                    entries.add(new FeatureEntry(featureIndex, id, feature));
                }
                result.add(new DecorationStep(stepIndex, entries));
            }
            return new VanillaFeatureSchedule(result);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "Unable to read Minecraft 26.2's indexed placed-feature schedule", exception);
        }
    }

    private static Object readIndexedSteps(Object generator) throws ReflectiveOperationException {
        Field supplierField = null;
        for (Field field : generator.getClass().getSuperclass().getDeclaredFields()) {
            if (Supplier.class.isAssignableFrom(field.getType())) {
                if (supplierField != null) {
                    throw new IllegalStateException(
                            "Expected one Supplier field on ChunkGenerator, found multiple");
                }
                supplierField = field;
            }
        }
        if (supplierField == null) {
            throw new IllegalStateException("ChunkGenerator indexed-feature Supplier not found");
        }
        supplierField.setAccessible(true);
        Object supplier = supplierField.get(generator);
        if (!(supplier instanceof Supplier<?> value)) {
            throw new IllegalStateException("ChunkGenerator indexed-feature field is not a Supplier");
        }
        return value.get();
    }

    private static Object readFeatureList(Object indexedStep) throws ReflectiveOperationException {
        Field listField = null;
        for (Field field : indexedStep.getClass().getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                if (listField != null) {
                    throw new IllegalStateException(
                            "Expected one List field in indexed-feature record, found multiple");
                }
                listField = field;
            }
        }
        if (listField == null) {
            throw new IllegalStateException("Indexed-feature record's feature List was not found");
        }
        listField.setAccessible(true);
        return listField.get(indexedStep);
    }

    public List<DecorationStep> steps() {
        return steps;
    }

    /** Ordered features for one vanilla decoration step; index is the vanilla feature index. */
    public record DecorationStep(int step, List<FeatureEntry> features) {
        public DecorationStep {
            features = List.copyOf(features);
        }
    }

    public record FeatureEntry(int index, Identifier id, PlacedFeature feature) {}
}
