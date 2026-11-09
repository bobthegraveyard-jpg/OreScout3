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

**The local placed-feature execution path is now implemented, but it is not yet validated as an exact world replica.** The current code adds:

1. `VanillaFeatureSchedule`: reads the vanilla 26.2 indexed placed-feature order.
2. `VanillaDecorationPlanner`: selects features using the surrounding 3×3 biome set and derives the vanilla per-feature random stream.
3. `LocalWorldGenLevel`: a restricted world-generation facade backed only by OreScout-owned chunks; it has no server-level or live-chunk fallback.
4. `SeedOreGenerationEngine`: generates a local 3×3 terrain/carver neighborhood, executes the selected placed features for the requested center chunk, and extracts ore block coordinates.
5. `SeedOreGenerationSmokeTest`: a headless CI test that exercises the local generation path.

**Important remaining correctness work:** structure starts/references are not yet simulated, and the local world adapter still needs to be checked against every operation vanilla features may call. The smoke test verifies that the code runs and produces internally consistent output; it does not prove exact equality with vanilla. The next required milestone is to compare multiple generated chunks against the independently validated vanilla ore coordinates, then close any differences before connecting this engine to the overlay.

The engine is not yet wired into the live client scheduler, and no generated ore coordinates are currently published to the overlay. Do not treat a successful compilation or smoke test as proof of exact ore positions.

## Coordinate publication contract

The eventual generator must publish each calculated chunk using `OreScoutClient.publishGeneratedChunk(chunkX, chunkZ, positions)`. Each `OrePosition.oreId` must match one of the display names exposed by `OreScoutClient.getOreNames()` (for example, `Diamond`, `Deepslate diamond`, or `Ancient debris`).

## Ores tracked

Diamond, emerald, coal, gold, iron, copper, lapis, redstone, nether gold, nether quartz, and ancient debris, including deepslate variants where applicable.

## Build

The project targets Java 25 and Fabric for Minecraft 26.2. GitHub Actions runs `./gradlew build worldgenSmokeTest` and uploads the resulting JAR as the `Artifacts` workflow artifact.

## License

CC0-1.0.

The local terrain pipeline is adapted from the MIT-licensed
[hzyhhzy/codex-minecraft-seed-methodology](https://github.com/hzyhhzy/codex-minecraft-seed-methodology)
project; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
