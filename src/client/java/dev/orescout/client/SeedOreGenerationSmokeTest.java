package dev.orescout.client;

/** Headless CI smoke test for the local seed-derived generation path. */
public final class SeedOreGenerationSmokeTest {
    private SeedOreGenerationSmokeTest() {}

    public static void main(String[] args) throws Exception {
        int chunkX = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int chunkZ = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        try (SeedOreGenerationEngine engine = new SeedOreGenerationEngine()) {
            SeedOreGenerationEngine.GeneratedOreChunk result = engine.generate(chunkX, chunkZ);
            if (result.chunkX() != chunkX || result.chunkZ() != chunkZ) {
                throw new AssertionError("Generation returned the wrong chunk coordinates");
            }
            if (result.placedFeatureCalls() <= 0) {
                throw new AssertionError("No vanilla placed features were selected");
            }
            for (SeedWorldKnowledge.OrePosition ore : result.ores()) {
                if ((ore.x() >> 4) != chunkX || (ore.z() >> 4) != chunkZ) {
                    throw new AssertionError("Extracted ore escaped the requested chunk: " + ore);
                }
            }
            System.out.printf(
                    "OreScout local generation smoke test: chunk=%d,%d placedFeatures=%d ores=%d summary=%s%n",
                    chunkX, chunkZ, result.placedFeatureCalls(), result.ores().size(),
                    result.terrainAndDecorationSummary());
        }
    }
}
