package dev.orescout;

import net.minecraft.resources.Identifier;

public final class OreScout {
    public static final String MOD_ID = "orescout";

    private OreScout() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
