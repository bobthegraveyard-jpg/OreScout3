package dev.orescout.client;

/** Headless CI smoke test for the local seed-derived generation path. */
public final class SeedOreGenerationSmokeTest {
    private SeedOreGenerationSmokeTest() {}

    public static void main(String[] args) throws Exception {
        int chunkX = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        int chunkZ = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        try (SeedOreGenerationEngine engine = new SeedOreGenerationEngine()) {
            SeedOreGenerationEngine.GeneratedOreChunk result = engine.generate(chunkX, chunkZ);
            if (result.chunkX() != chunkX || result.chunkZ() != chunkZ) {
                throw new AssertionError("Generation returned the wrong chunk coordinates");
            }
            if (result.ores().isEmpty()) {
                throw new AssertionError("No ores were extracted from the generated chunk");
            }
            if (result.placedFeatureCalls() <= 0) {
                throw new AssertionError("No vanilla placed features were selected");
            }
            for (SeedWorldKnowledge.OrePosition ore : result.ores()) {
                if ((ore.x() >> 4) != chunkX || (ore.z() >> 4) != chunkZ) {
                    throw new AssertionError("Extracted ore escaped the requested chunk: " + ore);
                }
            }

            String canonical = result.ores().stream()
                    .sorted(java.util.Comparator.comparingInt(SeedWorldKnowledge.OrePosition::x)
                            .thenComparingInt(SeedWorldKnowledge.OrePosition::y)
                            .thenComparingInt(SeedWorldKnowledge.OrePosition::z)
                            .thenComparing(SeedWorldKnowledge.OrePosition::oreId))
                    .map(ore -> "%d,%d,%d,%s".formatted(
                            ore.x(), ore.y(), ore.z(), ore.oreId()))
                    .collect(java.util.stream.Collectors.joining("\n"));
            String hash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            java.nio.file.Path output = java.nio.file.Path.of(
                    "build", "ore-scout-smoke", "chunk-" + chunkX + "-" + chunkZ + ".csv");
            java.nio.file.Files.createDirectories(output.getParent());
            String csv = "x,y,z,oreId\\n" + result.ores().stream()
                    .sorted(java.util.Comparator.comparingInt(SeedWorldKnowledge.OrePosition::x)
                            .thenComparingInt(SeedWorldKnowledge.OrePosition::y)
                            .thenComparingInt(SeedWorldKnowledge.OrePosition::z)
                            .thenComparing(SeedWorldKnowledge.OrePosition::oreId))
                    .map(ore -> "%d,%d,%d,%s".formatted(
                            ore.x(), ore.y(), ore.z(), ore.oreId()))
                    .collect(java.util.stream.Collectors.joining("\\n"));
            java.nio.file.Files.writeString(output, csv, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("OreScout generated coordinate export: " + output);
            System.out.printf(
                    "OreScout local generation smoke test: chunk=%d,%d placedFeatures=%d ores=%d oreSha256=%s summary=%s%n",
                    chunkX, chunkZ, result.placedFeatureCalls(), result.ores().size(), hash,
                    result.terrainAndDecorationSummary());
        }
    }
}
