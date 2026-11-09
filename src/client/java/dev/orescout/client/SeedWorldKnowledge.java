package dev.orescout.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-owned model of the ore-bearing world.
 *
 * <p>This class deliberately has no dependency on Level, ServerLevel, ChunkAccess,
 * block states, or chunk loading. The generation adapter supplies coordinates;
 * the renderer consumes this model. Mining only mutates the local mined-coordinate
 * ledger and never asks the world for replacement ore information.</p>
 */
public final class SeedWorldKnowledge {
    public static final long WORLD_SEED = 6608149111735331168L;
    public static final String WORLD_VERSION = "26.2";
    public static final String DIMENSION = "minecraft:overworld";

    private final Map<Long, List<OrePosition>> positionsByChunk = new HashMap<>();
    private final Set<Long> minedPositions = new HashSet<>();

    public synchronized void replaceChunk(int chunkX, int chunkZ, Collection<OrePosition> positions) {
        long key = chunkKey(chunkX, chunkZ);
        List<OrePosition> copy = new ArrayList<>(positions.size());
        for (OrePosition position : positions) {
            if ((position.x() >> 4) != chunkX || (position.z() >> 4) != chunkZ) {
                throw new IllegalArgumentException("Ore coordinate does not belong to supplied chunk");
            }
            if (!minedPositions.contains(position.blockKey())) {
                copy.add(position);
            }
        }
        positionsByChunk.put(key, List.copyOf(copy));
    }

    public synchronized List<OrePosition> positionsInChunk(int chunkX, int chunkZ) {
        List<OrePosition> positions = positionsByChunk.get(chunkKey(chunkX, chunkZ));
        return positions == null ? List.of() : positions;
    }

    public synchronized List<OrePosition> positionsInChunkSquare(
            int centerChunkX, int centerChunkZ, int radiusChunks
    ) {
        List<OrePosition> result = new ArrayList<>();
        for (int x = centerChunkX - radiusChunks; x <= centerChunkX + radiusChunks; x++) {
            for (int z = centerChunkZ - radiusChunks; z <= centerChunkZ + radiusChunks; z++) {
                result.addAll(positionsInChunk(x, z));
            }
        }
        return List.copyOf(result);
    }

    public synchronized boolean markMined(int x, int y, int z) {
        long key = blockKey(x, y, z);
        boolean added = minedPositions.add(key);
        if (added) {
            positionsByChunk.computeIfPresent(chunkKey(x >> 4, z >> 4), (ignored, old) ->
                    old.stream().filter(position -> position.blockKey() != key).toList());
        }
        return added;
    }

    public synchronized boolean isMined(int x, int y, int z) {
        return minedPositions.contains(blockKey(x, y, z));
    }

    public synchronized Set<Long> minedSnapshot() {
        return Set.copyOf(minedPositions);
    }

    public synchronized void restoreMined(Collection<Long> positions) {
        minedPositions.addAll(positions);
        positionsByChunk.replaceAll((ignored, old) ->
                old.stream().filter(position -> !minedPositions.contains(position.blockKey())).toList());
    }

    public synchronized void clearGeneratedCache() {
        positionsByChunk.clear();
    }

    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    public static long blockKey(int x, int y, int z) {
        return (((long) x & 0x3ffffffL) << 38)
                | (((long) z & 0x3ffffffL) << 12)
                | ((long) y & 0xfffL);
    }

    public record OrePosition(int x, int y, int z, String oreId) {
        public OrePosition {
            if (oreId == null || oreId.isBlank()) {
                throw new IllegalArgumentException("oreId must not be blank");
            }
        }

        public long blockKey() {
            return SeedWorldKnowledge.blockKey(x, y, z);
        }
    }
}
