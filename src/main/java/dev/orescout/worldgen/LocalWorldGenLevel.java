package dev.orescout.worldgen;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Random;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * A deliberately small WorldGenLevel facade backed only by OreScout-owned
 * in-memory chunks. It has no ServerLevel, ChunkSource, or live-world fallback.
 *
 * <p>Only the world operations needed by vanilla placed features are supplied.
 * Unknown non-default operations fail loudly rather than reaching a real world
 * or returning fabricated data.</p>
 */
public final class LocalWorldGenLevel implements InvocationHandler {
    private final Map<ChunkPos, ? extends ChunkAccess> chunks;
    private final java.util.Set<ChunkPos> writableChunks;
    private final RegistryAccess registries;
    private final BiomeManager biomeManager;
    private final NoiseBasedChunkGenerator generator;
    private final long seed;
    private final int minY;
    private final int height;
    private final WorldGenLevel view;

    public LocalWorldGenLevel(
            Map<ChunkPos, ? extends ChunkAccess> chunks,
            java.util.Set<ChunkPos> writableChunks,
            RegistryAccess registries,
            BiomeManager biomeManager,
            NoiseBasedChunkGenerator generator,
            long seed,
            int minY,
            int height) {
        this.chunks = Map.copyOf(chunks);
        this.writableChunks = java.util.Set.copyOf(writableChunks);
        this.registries = registries;
        this.biomeManager = biomeManager;
        this.generator = generator;
        this.seed = seed;
        this.minY = minY;
        this.height = height;
        this.view = (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(),
                new Class<?>[] {WorldGenLevel.class},
                this);
    }

    public WorldGenLevel view() {
        return view;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        Object[] a = args == null ? new Object[0] : args;

        return switch (name) {
            case "getSeed" -> seed;
            case "registryAccess" -> registries;
            case "getMinY", "getMinBuildHeight" -> minY;
            case "getHeight" -> {
                if (a.length == 0) yield height;
                if (a.length == 2 && a[1] instanceof BlockPos pos) {
                    yield heightAt((Heightmap.Types) a[0], pos.getX(), pos.getZ());
                }
                if (a.length == 3 && a[0] instanceof Heightmap.Types type) {
                    yield heightAt(type, (int) a[1], (int) a[2]);
                }
                throw new UnsupportedOperationException("Unsupported local getHeight overload");
            }
            case "getGenDepth", "getWorldHeight" -> height;
            case "getMaxY", "getMaxBuildHeight" -> minY + height;
            case "getSeaLevel" -> generator.getSeaLevel();
            case "isClientSide" -> false;
            case "getBiomeManager" -> biomeManager;
            case "getBiome" -> biomeManager.getBiome((BlockPos) a[0]);
            case "getUncachedNoiseBiome", "getNoiseBiome" ->
                    chunkAtQuart((int) a[0], (int) a[2]).getNoiseBiome(
                            (int) a[0], (int) a[1], (int) a[2]);
            case "getBlockState" -> blockState((BlockPos) a[0]);
            case "isStateAtPosition" ->
                    ((java.util.function.Predicate<Object>) a[1]).test(blockState((BlockPos) a[0]));
            case "isFluidAtPosition" ->
                    ((java.util.function.Predicate<Object>) a[1]).test(blockState((BlockPos) a[0]).getFluidState());
            case "getFluidState" -> blockState((BlockPos) a[0]).getFluidState();
            case "getFluidTicks", "getBlockTicks" -> emptyTickAccess(method.getReturnType());
            case "scheduleTick" -> false;
            case "setBlock" -> setBlock((BlockPos) a[0], (BlockState) a[1], (int) a[2]);
            case "getChunk" -> chunkArgument(a);
            case "hasChunk", "isChunkLoaded" -> chunks.containsKey(new ChunkPos((int) a[0], (int) a[1]));
            case "ensureCanWrite", "isValidForSetBlock" -> writable((BlockPos) a[0]);
            case "getRandom" -> new LegacyRandomSource(seed);
            case "getGameTime", "getDayTime", "getTime" -> 0L;
            case "getDifficulty" -> net.minecraft.world.Difficulty.NORMAL;
            case "getLevel", "getServer", "getChunkSource", "getLevelData",
                    "getLightEngine", "getBlockEntity", "getWorldBorder" -> null;
            case "setCurrentlyGenerating", "setCurrentlyGeneratingStructureName" -> null;
            case "toString" -> "OreScoutLocalWorldGenLevel[" + writableChunks + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == a[0];
            default -> {
                if (method.isDefault()) {
                    yield InvocationHandler.invokeDefault(proxy, method, a);
                }
                throw new UnsupportedOperationException(
                        "Local OreScout world does not implement WorldGenLevel." + name
                                + " " + method.toGenericString());
            }
        };
    }

    /**
     * Scheduled ticks are queued by vanilla during feature placement but are not
     * executed until later world ticks. A local generation snapshot has no ticking
     * simulation, so expose an empty, side-effect-free tick queue.
     */
    private static Object emptyTickAccess(Class<?> type) {
        if (!type.isInterface()) {
            throw new IllegalStateException("Expected tick-access interface, got " + type.getName());
        }
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Class<?> result = method.getReturnType();
            if (result == boolean.class || result == Boolean.class) return false;
            if (result == int.class || result == Integer.class) return 0;
            if (result == long.class || result == Long.class) return 0L;
            if (java.util.List.class.isAssignableFrom(result)) return java.util.List.of();
            if (java.util.Set.class.isAssignableFrom(result)) return java.util.Set.of();
            if (java.util.Collection.class.isAssignableFrom(result)) return java.util.List.of();
            if (java.util.Optional.class.isAssignableFrom(result)) return java.util.Optional.empty();
            if (java.util.stream.Stream.class.isAssignableFrom(result)) return java.util.stream.Stream.empty();
            if (method.getName().equals("toString")) return "OreScoutEmptyTickAccess";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }

    private ChunkAccess chunkArgument(Object[] args) {
        ChunkAccess chunk;
        if (args.length > 0 && args[0] instanceof BlockPos pos) {
            chunk = chunkAt(pos);
        } else if (args.length > 0 && args[0] instanceof ChunkPos pos) {
            chunk = chunks.get(pos);
        } else {
            int x = (int) args[0];
            int z = (int) args[1];
            chunk = chunks.get(new ChunkPos(x, z));
            if (chunk == null) {
                throw new IllegalStateException(
                        "Feature attempted to access an unsimulated chunk " + x + "," + z);
            }
        }
        if (chunk == null) {
            throw new IllegalStateException("Feature attempted to access an unsimulated chunk");
        }
        for (Object argument : args) {
            if (argument instanceof ChunkStatus requested
                    && !chunk.getPersistedStatus().isOrAfter(requested)) {
                throw new IllegalStateException(
                        "Local chunk " + chunk.getPos() + " is below requested status " + requested);
            }
        }
        return chunk;
    }

    private ChunkAccess chunkAt(BlockPos pos) {
        ChunkPos chunkPos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkAccess chunk = chunks.get(chunkPos);
        if (chunk == null) {
            throw new IllegalStateException("Feature accessed outside local chunk halo: " + pos);
        }
        return chunk;
    }

    private ChunkAccess chunkAtQuart(int quartX, int quartZ) {
        int blockX = quartX << 2;
        int blockZ = quartZ << 2;
        ChunkAccess chunk = chunks.get(new ChunkPos(blockX >> 4, blockZ >> 4));
        if (chunk == null) {
            throw new IllegalStateException(
                    "Feature queried biome outside local chunk halo at quart " + quartX + "," + quartZ);
        }
        return chunk;
    }

    private BlockState blockState(BlockPos pos) {
        if (pos.getY() < minY || pos.getY() >= minY + height) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunkAt(pos).getBlockState(pos);
    }

    private boolean writable(BlockPos pos) {
        return pos.getY() >= minY
                && pos.getY() < minY + height
                && writableChunks.contains(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private boolean setBlock(BlockPos pos, BlockState state, int flags) {
        if (!writable(pos)) {
            return false;
        }
        ChunkAccess chunk = chunkAt(pos);
        BlockState previous = chunk.setBlockState(pos, state, flags);
        return previous != null;
    }

    private int heightAt(Heightmap.Types type, int x, int z) {
        ChunkAccess chunk = chunks.get(new ChunkPos(x >> 4, z >> 4));
        if (chunk == null) {
            throw new IllegalStateException("Height query outside local chunk halo at " + x + "," + z);
        }
        try {
            Method method = chunk.getClass().getMethod("getHeight", Heightmap.Types.class, int.class, int.class);
            return (int) method.invoke(chunk, type, x & 15, z & 15);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Minecraft 26.2 chunk heightmap API changed", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Local heightmap query failed", exception.getCause());
        }
    }
}
