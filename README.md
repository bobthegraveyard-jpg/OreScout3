# OreScout

OreScout is a client-side Fabric mod for Minecraft Java 26.2 targeting the vanilla Overworld generated from seed `6608149111735331168`.

## Required architecture

OreScout is intended to be a proactive, seed-derived ore map. Its ore-coordinate database is authoritative for highlights. It must not discover ores by:

- requesting or generating chunks from an integrated or remote server;
- scanning loaded block states;
- using server chunk contents or block updates to correct its calculated ore map.

The client renderer projects calculated coordinates through walls, independently of whether corresponding server chunks have been sent to the client.

## Ore-generation engine status

The generation work is deliberately separated from the overlay. The new `dev.orescout.worldgen.LocalChunkPipeline` bootstraps the pinned 26.2 vanilla registries and runs Minecraft's own local in-memory terrain implementation through:

`BIOMES → NOISE → SURFACE → CARVERS`

It creates `ProtoChunk` instances locally. It does not request live chunks, access a live `Level`, inspect server-supplied block states, or connect to an integrated server. Its deterministic summary method is intended for regression checks.

**This is the terrain substrate, not the completed ore calculator.** The following work remains before OreScout can claim to know exact ore coordinates:

1. **Feature ordering substrate added:** `VanillaFeatureSchedule` asks the 26.2 generator to initialise its own memoized `PlacedFeatureIndexer` result and reads the vanilla per-decoration-step order. It fails closed if the expected internal layout is not present. This avoids inventing a separate sort order.
2. Reproduce the exact per-chunk/per-feature random seeding and the 3×3 biome-union filtering used by vanilla decoration.
3. Reproduce the complete placement-modifier chains for every Overworld ore feature.
4. Execute configured ore features against the local simulated chunk state, including the required neighboring source-chunk halo and correct feature ordering.
5. Extract every resulting ore block coordinate from the locally generated state.
6. Compare the output against independently generated vanilla chunks and record exact-match regressions.

Until those stages are implemented, no generated ore coordinates are published and the overlay should not be expected to show ores. Do not treat the current terrain pipeline as proof of ore positions.

## Coordinate publication contract

The eventual generator must publish each calculated chunk using `OreScoutClient.publishGeneratedChunk(chunkX, chunkZ, positions)`. Each `OrePosition.oreId` must match one of the display names exposed by `OreScoutClient.getOreNames()` (for example, `Diamond`, `Deepslate diamond`, or `Ancient debris`).

## Ores tracked

Diamond, emerald, coal, gold, iron, copper, lapis, redstone, nether gold, nether quartz, and ancient debris, including deepslate variants where applicable.

## Build

The project targets Java 25 and Fabric for Minecraft 26.2. GitHub Actions runs `./gradlew build` and uploads the resulting JAR as the `Artifacts` workflow artifact.

## License

CC0-1.0.

The local terrain pipeline is adapted from the MIT-licensed
[hzyhhzy/codex-minecraft-seed-methodology](https://github.com/hzyhhzy/codex-minecraft-seed-methodology)
project; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
