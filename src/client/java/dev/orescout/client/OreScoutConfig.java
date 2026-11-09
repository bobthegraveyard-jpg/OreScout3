package dev.orescout.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.fabricmc.loader.api.FabricLoader;

public final class OreScoutConfig {
    private static final Properties VALUES = new Properties();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orescout.properties");
    private static boolean overlayEnabled = true;
    private static boolean coordinatesEnabled = true;

    static {
        load();
    }

    private OreScoutConfig() {}

    private static void load() {
        if (Files.exists(FILE)) {
            try (InputStream input = Files.newInputStream(FILE)) {
                VALUES.load(input);
            } catch (IOException exception) {
                System.err.println("[OreScout] Could not load settings: " + exception.getMessage());
            }
        }
        overlayEnabled = Boolean.parseBoolean(VALUES.getProperty("overlay", "true"));
        coordinatesEnabled = Boolean.parseBoolean(VALUES.getProperty("coordinates", "true"));
    }

    public static synchronized boolean isOverlayEnabled() { return overlayEnabled; }
    public static synchronized boolean isCoordinatesEnabled() { return coordinatesEnabled; }
    public static synchronized boolean isOreEnabled(String ore) {
        return Boolean.parseBoolean(VALUES.getProperty("ore." + ore, "true"));
    }

    public static synchronized void setOverlayEnabled(boolean value) {
        overlayEnabled = value;
        save();
    }

    public static synchronized void setCoordinatesEnabled(boolean value) {
        coordinatesEnabled = value;
        save();
    }


    public static synchronized void setOreEnabled(String ore, boolean value) {
        VALUES.setProperty("ore." + ore, Boolean.toString(value));
        save();
    }

    public static synchronized Set<String> getOreNames() {
        Set<String> names = new TreeSet<>();
        for (String key : VALUES.stringPropertyNames()) {
            if (key.startsWith("ore.")) names.add(key.substring(4));
        }
        return names;
    }

    private static void save() {
        VALUES.setProperty("overlay", Boolean.toString(overlayEnabled));
        VALUES.setProperty("coordinates", Boolean.toString(coordinatesEnabled));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream output = Files.newOutputStream(FILE)) {
                VALUES.store(output, "OreScout settings");
            }
        } catch (IOException exception) {
            System.err.println("[OreScout] Could not save settings: " + exception.getMessage());
        }
    }
}
