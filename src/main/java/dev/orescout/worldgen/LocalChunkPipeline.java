package dev.orescout.worldgen;

import java.lang.reflect.Field;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Version-pinned local terrain substrate for OreScout's independent ore model.
 *
 * <p>Uses Minecraft 26.2's own registry bootstrap and generation implementation to
 * create an in-memory ProtoChunk through CARVERS. It never requests a live world
 * chunk, reads a live Level, or connects to an integrated/dedicated server.</p>
 *
 * <p>This is deliberately not described as a complete ore calculator: placed-feature
 * decoration (including ores), cross-chunk feature ordering, structures, and blending
 * are not yet implemented here. OreScout must not publish coordinates based on this
 * class alone.</p>
 */
public final class LocalChunkPipeline implements AutoCloseable {
    private final AutoCloseable resources;
    private final RegistryAccess registries;
    private final NoiseBasedChunkGenerator generator;
    private final NoiseGeneratorSettings settings;
    private final Aquifer.FluidPicker fluidPicker;
    private final DensityFunctions.BeardifierOrMarker emptyBeardifier;
    private final LevelHeightAccessor height;
    private final PalettedContainerFactory palettes;

    @SuppressWarnings("unchecked")
    public LocalChunkPipeline() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Loaded loaded = loadRegistries();
        resources = loaded.resources;
        registries = loaded.registries.compositeAccess();
        LevelStem overworld = loaded.complete.dimensions().getValueOrThrow(LevelStem.OVERWORLD);
        generator = (NoiseBasedChunkGenerator) overworld.generator();
        settings = generator.generatorSettings().value();
        DimensionType dimensionType = overworld.type().value();
        height = LevelHeightAccessor.create(dimensionType.minY(), dimensionType.height());
        palettes = PalettedContainerFactory.create(registries);

        try {
            Field pickerField = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
            pickerField.setAccessible(true);
            fluidPicker = ((Supplier<Aquifer.FluidPicker>) pickerField.get(generator)).get();

            Class<?> markerClass = Class.forName(
                    "net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker");
            Field instance = markerClass.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            emptyBeardifier = (DensityFunctions.BeardifierOrMarker) instance.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Expected Minecraft Java 26.2 classes", exception);
        }
    }

    /** Generate one fresh local chunk through the end of CARVERS. */
    public ProtoChunk generate(long worldSeed, int chunkX, int chunkZ) {
        RandomState randomState = randomState(worldSeed);
        return generate(worldSeed, randomState, chunkX, chunkZ);
    }

    /** Reuse the seed-derived random state while generating several local chunks. */
    public ProtoChunk generate(long worldSeed, RandomState randomState, int chunkX, int chunkZ) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        ProtoChunk chunk = new ProtoChunk(chunkPos, UpgradeData.EMPTY, height, palettes, null);

        NoiseChunk noiseChunk = NoiseChunk.forChunk(
                chunk, randomState, emptyBeardifier, settings, fluidPicker, Blender.empty());
        chunk.getOrCreateNoiseChunk(ignored -> noiseChunk);

        generator.createBiomes(randomState, Blender.empty(), null, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        generator.fillFromNoise(Blender.empty(), randomState, null, chunk).join();
        chunk.setPersistedStatus(ChunkStatus.NOISE);

        BiomeManager biomeManager = biomeManager(worldSeed, randomState);
        generator.buildSurface(
                chunk,
                new WorldGenerationContext(generator, height),
                randomState,
                null,
                biomeManager,
                Blender.empty(),
                null);
        chunk.setPersistedStatus(ChunkStatus.SURFACE);

        applyCarvers(worldSeed, randomState, biomeManager, chunk, noiseChunk);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        return chunk;
    }

    public RandomState randomState(long worldSeed) {
        return RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, worldSeed);
    }

    public RegistryAccess registries() {
        return registries;
    }

    public NoiseBasedChunkGenerator generator() {
        return generator;
    }

    public LevelHeightAccessor heightAccessor() {
        return height;
    }

    public BiomeManager biomeManager(long worldSeed, RandomState randomState) {
        return new BiomeManager(
                (quartX, quartY, quartZ) -> generator.getBiomeSource().getNoiseBiome(
                        quartX, quartY, quartZ, randomState.sampler()),
                BiomeManager.obfuscateSeed(worldSeed));
    }

    /** Exact vanilla 17x17 origin-chunk carver sweep, operating only on this local ProtoChunk. */
    private void applyCarvers(
            long worldSeed,
            RandomState randomState,
            BiomeManager biomeManager,
            ProtoChunk target,
            NoiseChunk noiseChunk) {
        BiomeManager carverBiomeManager = biomeManager.withDifferentSource(
                (quartX, quartY, quartZ) -> generator.getBiomeSource().getNoiseBiome(
                        quartX, quartY, quartZ, randomState.sampler()));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        ChunkPos targetPos = target.getPos();
        Aquifer aquifer = noiseChunk.aquifer();
        CarvingContext context = new CarvingContext(
                generator,
                registries,
                target.getHeightAccessorForGeneration(),
                noiseChunk,
                randomState,
                settings.surfaceRule());
        CarvingMask mask = target.getOrCreateCarvingMask();

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                ChunkPos origin = new ChunkPos(targetPos.x() + dx, targetPos.z() + dz);
                Holder<Biome> originBiome = generator.getBiomeSource().getNoiseBiome(
                        QuartPos.fromBlock(origin.getMinBlockX()),
                        0,
                        QuartPos.fromBlock(origin.getMinBlockZ()),
                        randomState.sampler());
                BiomeGenerationSettings generation = generator.getBiomeGenerationSettings(originBiome);
                int carverIndex = 0;
                for (Holder<ConfiguredWorldCarver<?>> holder : generation.getCarvers()) {
                    ConfiguredWorldCarver<?> carver = holder.value();
                    random.setLargeFeatureSeed(worldSeed + carverIndex, origin.x(), origin.z());
                    if (carver.isStartChunk(random)) {
                        carver.carve(
                                context,
                                target,
                                carverBiomeManager::getBiome,
                                random,
                                aquifer,
                                origin,
                                mask);
                    }
                    carverIndex++;
                }
            }
        }
    }

    /** Compact deterministic diagnostic for local-pipeline regression tests. */
    public String summarize(ChunkAccess chunk) {
        Registry<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        long hash = 0xcbf29ce484222325L;
        int nonAir = 0;
        int fluids = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkPos chunkPos = chunk.getPos();
        for (int y = height.getMinY(); y <= height.getMaxY(); y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = chunk.getBlockState(pos.set(
                            chunkPos.getMinBlockX() + x, y, chunkPos.getMinBlockZ() + z));
                    hash ^= blocks.getId(state.getBlock());
                    hash *= 0x100000001b3L;
                    hash ^= state.hashCode();
                    hash *= 0x100000001b3L;
                    if (!state.isAir()) nonAir++;
                    if (!state.getFluidState().isEmpty()) fluids++;
                }
            }
        }
        long carved = 0;
        if (chunk instanceof ProtoChunk proto) {
            for (long word : proto.getCarvingMask().toArray()) carved += Long.bitCount(word);
        }
        return "hash=%016x nonAir=%d fluids=%d carved=%d".formatted(hash, nonAir, fluids, carved);
    }

    @Override
    public void close() throws Exception {
        resources.close();
    }

    private static Loaded loadRegistries() {
        PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(
                packs, WorldDataConfiguration.DEFAULT, false, true);
        WorldLoader.InitConfig init = new WorldLoader.InitConfig(
                packConfig, Commands.CommandSelection.DEDICATED, PermissionSet.NO_PERMISSIONS);
        Executor direct = Runnable::run;

        return WorldLoader.load(
                init,
                context -> {
                    Registry<LevelStem> stems = context.datapackDimensions()
                            .lookupOrThrow(Registries.LEVEL_STEM);
                    WorldDimensions.Complete complete =
                            WorldPresets.createNormalWorldDimensions(context.datapackWorldgen())
                                    .bake(stems);
                    return new WorldLoader.DataLoadOutput<>(complete, complete.dimensionsRegistryAccess());
                },
                (resources, reloadable, registries, complete) ->
                        new Loaded(resources, registries, complete),
                direct,
                direct).join();
    }

    private record Loaded(
            AutoCloseable resources,
            LayeredRegistryAccess<RegistryLayer> registries,
            WorldDimensions.Complete complete) {}
}
