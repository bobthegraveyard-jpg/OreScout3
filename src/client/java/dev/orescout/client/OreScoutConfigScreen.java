package dev.orescout.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OreScoutConfigScreen extends Screen {
    private final Screen parent;

    public OreScoutConfigScreen(Screen parent) {
        super(Component.literal("OreScout Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        addRenderableWidget(Button.builder(overlayLabel(), button -> {
            OreScoutConfig.setOverlayEnabled(!OreScoutConfig.isOverlayEnabled());
            button.setMessage(overlayLabel());
        }).bounds(center - 205, 38, 200, 20).build());

        addRenderableWidget(Button.builder(coordinatesLabel(), button -> {
            OreScoutConfig.setCoordinatesEnabled(!OreScoutConfig.isCoordinatesEnabled());
            button.setMessage(coordinatesLabel());
        }).bounds(center + 5, 38, 200, 20).build());

        List<String> ores = new ArrayList<>(OreScoutClient.getOreNames());
        int startY = 76;
        int rowHeight = 22;
        for (int i = 0; i < ores.size(); i++) {
            String ore = ores.get(i);
            int column = i / 10;
            int row = i % 10;
            int x = center - 205 + column * 210;
            int y = startY + row * rowHeight;
            addRenderableWidget(Button.builder(oreLabel(ore), button -> {
                OreScoutConfig.setOreEnabled(ore, !OreScoutConfig.isOreEnabled(ore));
                button.setMessage(oreLabel(ore));
            }).bounds(x, y, 200, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(center - 100, this.height - 30, 200, 20).build());
    }

    private static Component overlayLabel() {
        return Component.literal("Highlight boxes: " + (OreScoutConfig.isOverlayEnabled() ? "ON" : "OFF"));
    }

    private static Component coordinatesLabel() {
        return Component.literal("Coordinates list: " + (OreScoutConfig.isCoordinatesEnabled() ? "ON" : "OFF"));
    }

    private static Component oreLabel(String ore) {
        return Component.literal((OreScoutConfig.isOreEnabled(ore) ? "[ON] " : "[OFF] ") + ore);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xC0101018);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.drawCenteredString(font, Component.literal("Choose which ores to track and which displays to show"),
            width / 2, 24, 0xFFBBBBBB);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }
}
