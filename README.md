# OreScout

OreScout is a client-side Fabric mod for Minecraft Java 26.2 targeting the vanilla Overworld generated from seed `6608149111735331168`.

## Required architecture

OreScout is intended to be a proactive, seed-derived ore map. Its ore-coordinate database is authoritative for highlights. It must not discover ores by:

- requesting or generating chunks from an integrated or remote server;
- scanning loaded block states;
- using server chunk contents or block updates to correct its calculated ore map.

The client renderer projects the calculated coordinates through walls, independently of whether the corresponding server chunks have been sent to the client. The existing **O** overlay toggle, **P** settings screen, ore colours, and coordinate HUD are retained.

## Current integration status

- The old integrated-server chunk-generation path has been removed.
- The old loaded-block-state ore scanner and reconciliation path have been removed.
- The renderer now consumes the client-owned `SeedWorldKnowledge` coordinate database.
- Successful local block-break actions remove matching coordinates from that database without inspecting the resulting block state.
- The independent 26.2 seed-generation producer is **not yet integrated**. Until that producer publishes calculated chunks through `OreScoutClient.publishGeneratedChunk`, the overlay has no generated coordinates to display. This is an explicit incomplete stage, not a working ore-finding release.

## Coordinate publication contract

The generation engine must publish each calculated chunk using `OreScoutClient.publishGeneratedChunk(chunkX, chunkZ, positions)`. Each `OrePosition.oreId` must match one of the display names exposed by `OreScoutClient.getOreNames()` (for example, `Diamond`, `Deepslate diamond`, or `Ancient debris`).

The generator must run entirely from the fixed seed and vanilla 26.2 generation data. It must not read client or server chunk block states, ask a server to generate chunks, or use server-supplied ore information.

## Ores tracked

Diamond, emerald, coal, gold, iron, copper, lapis, redstone, nether gold, nether quartz, and ancient debris, including deepslate variants where applicable.

## Build

The current work is being validated against the Java 25 / Fabric 26.2 CI toolchain.

The GitHub Actions workflow builds the mod with Java 25 and the Fabric 26.2 toolchain. Successful builds upload the JAR as the `Artifacts` workflow artifact.

## License

CC0-1.0.
