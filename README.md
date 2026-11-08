# OreScout

A client-side Fabric mod for Minecraft Java 26.2 that scans already-loaded terrain for ore blocks and highlights confirmed locations through terrain.

## Features

- Scans a 75-block horizontal radius around the player.
- Checks loaded chunks only; it does not request or generate new chunks.
- Highlights discovered ore blocks through walls with distinct colours.
- Shows the nearest ore names and XYZ coordinates in a small HUD panel.
- Supports the Overworld and Nether.
- Press **O** to toggle the highlights and HUD.

## Ores tracked

Diamond, emerald, coal, gold, iron, copper, lapis lazuli, redstone, nether gold, nether quartz, and ancient debris, including their deepslate variants where they exist.

## Important: confirmed finds vs seed predictions

This initial build reports **confirmed blocks from chunks currently loaded by Minecraft**. It does not yet display seed-based predictions for unexplored chunks. Your world was created on an earlier, currently unknown world-generation version, so predictions made with 26.2 world generation could be misleading. Prediction support should be added as a separate, explicitly labelled estimate mode once the original generation version/settings are known.

## Build

The GitHub Actions workflow builds the mod with the Fabric 26.2 toolchain and uploads the JAR in the `Artifacts` workflow artifact.

## License

CC0-1.0.
