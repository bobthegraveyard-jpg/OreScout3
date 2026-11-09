package dev.orescout.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class OreScoutConfigScreen extends Screen {
    private final Screen parent;
    private ScrollableLayout optionsPanel;

    public OreScoutConfigScreen(Screen parent) {
        super(Component.literal("OreScout Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int doneWidth = 76;
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
            .bounds(this.width - doneWidth - 12, 10, doneWidth, 22).build());

        int panelWidth = Math.max(180, this.width - 36);
        int buttonWidth = Math.max(150, Math.min(420, panelWidth - 28));
        LinearLayout content = LinearLayout.vertical().spacing(6);

        content.addChild(new StringWidget(buttonWidth, 18, Component.literal("GENERAL DISPLAY"), font));
        content.addChild(toggleButton("Highlight boxes", OreScoutConfig.isOverlayEnabled(),
            value -> OreScoutConfig.setOverlayEnabled(value), buttonWidth));
        content.addChild(toggleButton("Coordinates list", OreScoutConfig.isCoordinatesEnabled(),
            value -> OreScoutConfig.setCoordinatesEnabled(value), buttonWidth));

        content.addChild(new StringWidget(buttonWidth, 18, Component.literal("ORE TYPES"), font));
        List<String> ores = new ArrayList<>(OreScoutClient.getOreNames());
        for (String ore : ores) {
            content.addChild(Button.builder(oreLabel(ore), button -> {
                OreScoutConfig.setOreEnabled(ore, !OreScoutConfig.isOreEnabled(ore));
                button.setMessage(oreLabel(ore));
            }).width(buttonWidth).build());
        }

        optionsPanel = new ScrollableLayout(Minecraft.getInstance(), content, Math.max(60, this.height - 76));
        optionsPanel.setMinWidth(panelWidth);
        optionsPanel.setX((this.width - panelWidth) / 2);
        optionsPanel.setY(48);
        optionsPanel.arrangeElements();
        optionsPanel.visitChildren(element -> {
            if (element instanceof AbstractWidget widget) {
                addRenderableWidget(widget);
            }
        });
    }

    private static Button toggleButton(String name, boolean enabled,
                                       java.util.function.Consumer<Boolean> setter, int width) {
        return Button.builder(toggleLabel(name, enabled), button -> {
            boolean next = !enabledState(button.getMessage());
            setter.accept(next);
            button.setMessage(toggleLabel(name, next));
        }).width(width).build();
    }

    private static boolean enabledState(Component message) {
        return message.getString().endsWith(": ON");
    }

    private static Component toggleLabel(String name, boolean enabled) {
        return Component.literal(name + ": " + (enabled ? "ON" : "OFF"));
    }

    private static Component oreLabel(String ore) {
        return Component.literal((OreScoutConfig.isOreEnabled(ore) ? "ON  " : "OFF  ") + ore);
    }

    @Override
    public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                   int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xC0101018);
        graphics.centeredText(font, title, width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(font,
            Component.literal("Choose what OreScout tracks and displays. Scroll for more options."),
            width / 2, 32, 0xFFBBBBBB);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    protected void repositionElements() {
        if (optionsPanel != null) {
            optionsPanel.setMinWidth(Math.max(180, this.width - 36));
            optionsPanel.setMaxHeight(Math.max(60, this.height - 76));
            optionsPanel.setX(18);
            optionsPanel.setY(48);
            optionsPanel.arrangeElements();
        }
        super.repositionElements();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.gui.setScreen(parent);
    }
}
