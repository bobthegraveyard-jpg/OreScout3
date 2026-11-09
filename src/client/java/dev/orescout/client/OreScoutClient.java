package dev.orescout.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.orescout.OreScout;

/**
 * Client-side presentation for the seed-derived ore map.
 *
 * <p>This class intentionally does not inspect block states, request chunks, access an
 * integrated server, or consume server chunk contents. The generation engine publishes
 * coordinates into SeedWorldKnowledge; this class only projects those coordinates into
 * the existing HUD and through-wall highlight renderer.</p>
 */
public final class OreScoutClient implements ClientModInitializer {
    private static final int RENDER_RADIUS_BLOCKS = 75;
    private static final int DISPLAY_LIMIT = 8;

    private static final Map<String, OreInfo> ORE_TYPES = new LinkedHashMap<>();
    private static final SeedWorldKnowledge KNOWLEDGE = new SeedWorldKnowledge();
    private static volatile List<OreHit> renderHits = List.of();

    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(OreScout.id("pipeline/ore_highlight"))
            .withDepthStencilState(Optional.empty())
            .build()
    );

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER =
        new StagedVertexBuffer(() -> "OreScout highlight buffer", RenderType.SMALL_BUFFER_SIZE);

    private static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("orescout", "main"));
    private static KeyMapping toggleOverlayKey;
    private static KeyMapping openSettingsKey;

    static {
        add("Diamond", 0.15f, 0.85f, 1.00f);
        add("Deepslate diamond", 0.15f, 0.85f, 1.00f);
        add("Emerald", 0.10f, 1.00f, 0.35f);
        add("Deepslate emerald", 0.10f, 1.00f, 0.35f);
        add("Coal", 0.35f, 0.35f, 0.35f);
        add("Deepslate coal", 0.35f, 0.35f, 0.35f);
        add("Gold", 1.00f, 0.75f, 0.05f);
        add("Deepslate gold", 1.00f, 0.75f, 0.05f);
        add("Iron", 0.85f, 0.75f, 0.65f);
        add("Deepslate iron", 0.85f, 0.75f, 0.65f);
        add("Copper", 0.90f, 0.40f, 0.20f);
        add("Deepslate copper", 0.90f, 0.40f, 0.20f);
        add("Lapis", 0.15f, 0.30f, 1.00f);
        add("Deepslate lapis", 0.15f, 0.30f, 1.00f);
        add("Redstone", 1.00f, 0.10f, 0.10f);
        add("Deepslate redstone", 1.00f, 0.10f, 0.10f);
        add("Nether gold", 1.00f, 0.65f, 0.05f);
        add("Nether quartz", 0.95f, 0.85f, 0.80f);
        add("Ancient debris", 0.65f, 0.25f, 0.15f);
    }

    @Override
    public void onInitializeClient() {
        toggleOverlayKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.orescout.toggle_overlay", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KEY_CATEGORY
        ));
        openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.orescout.open_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, KEY_CATEGORY
        ));
        ClientTickEvents.END_CLIENT_TICK.register(OreScoutClient::onClientTick);
        LevelExtractionEvents.END_EXTRACTION.register(context -> refreshRenderSnapshot());
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(OreScoutClient::renderHighlights);
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT, OreScout.id("ore_status"), OreScoutClient::renderHud
        );
    }

    private static void add(String name, float r, float g, float b) {
        ORE_TYPES.put(name, new OreInfo(name, r, g, b));
    }

    public static List<String> getOreNames() {
        return List.copyOf(ORE_TYPES.keySet());
    }

    /** Chunks are tracked separately from their ore lists so an empty result is not regenerated forever. */
    public static boolean isChunkGenerated(int chunkX, int chunkZ) {
        return KNOWLEDGE.isChunkGenerated(chunkX, chunkZ);
    }

    /** Missing chunk coordinates for an engine scheduler; contains no world/chunk reads. */
    public static List<SeedWorldKnowledge.ChunkCoordinate> missingChunksInSquare(
            int centerChunkX, int centerChunkZ, int radiusChunks) {
        return KNOWLEDGE.missingChunksInSquare(centerChunkX, centerChunkZ, radiusChunks);
    }

    /** Called by the independent generation engine after it has calculated one chunk. */
    public static void publishGeneratedChunk(int chunkX, int chunkZ,
            java.util.Collection<SeedWorldKnowledge.OrePosition> positions) {
        KNOWLEDGE.replaceChunk(chunkX, chunkZ, positions);
    }

    /** Called only after the local client confirms its own block-break action succeeded. */
    public static void markMined(BlockPos pos) {
        KNOWLEDGE.markMined(pos.getX(), pos.getY(), pos.getZ());
        refreshRenderSnapshot();
    }

    private static void onClientTick(Minecraft client) {
        boolean togglePressed = toggleOverlayKey != null && toggleOverlayKey.consumeClick();
        boolean settingsPressed = openSettingsKey != null && openSettingsKey.consumeClick();
        if (client.player != null && client.gui.screen() == null) {
            if (togglePressed) {
                OreScoutConfig.setOverlayEnabled(!OreScoutConfig.isOverlayEnabled());
            }
            if (settingsPressed) {
                client.gui.setScreen(new OreScoutConfigScreen(client.gui.screen()));
            }
        }

        if (client.level == null || client.player == null) {
            renderHits = List.of();
            return;
        }

        // The seed map is dimension-specific. Dimension is used only to avoid drawing an
        // Overworld map in another dimension; no chunk or block data is consulted.
        if (!client.level.dimension().equals(Level.OVERWORLD)) {
            renderHits = List.of();
            return;
        }
        refreshRenderSnapshot();
    }

    private static void refreshRenderSnapshot() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !client.level.dimension().equals(Level.OVERWORLD)) {
            renderHits = List.of();
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        int radiusChunks = (RENDER_RADIUS_BLOCKS + 15) >> 4;
        List<OreHit> next = new ArrayList<>();
        for (SeedWorldKnowledge.OrePosition position :
                KNOWLEDGE.positionsInChunkSquare(playerPos.getX() >> 4, playerPos.getZ() >> 4, radiusChunks)) {
            OreInfo ore = ORE_TYPES.get(position.oreId());
            if (ore == null || !OreScoutConfig.isOreEnabled(ore.name())) continue;
            int dx = position.x() - playerPos.getX();
            int dy = position.y() - playerPos.getY();
            int dz = position.z() - playerPos.getZ();
            if ((long) dx * dx + (long) dy * dy + (long) dz * dz
                    > (long) RENDER_RADIUS_BLOCKS * RENDER_RADIUS_BLOCKS) continue;
            next.add(new OreHit(position.x(), position.y(), position.z(), ore));
        }
        renderHits = List.copyOf(next);
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!OreScoutConfig.isCoordinatesEnabled()) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        List<OreHit> nearby = nearestHits(client.player.blockPosition());
        int rows = Math.min(DISPLAY_LIMIT, nearby.size());
        graphics.fill(8, 8, 220, 34 + rows * 12, 0x99000000);
        graphics.text(client.font, "OreScout  [P: settings] [O: boxes]", 14, 13, 0xFFFFFFFF, false);
        for (int i = 0; i < rows; i++) {
            OreHit hit = nearby.get(i);
            String line = hit.ore().name() + "  " + hit.x() + " " + hit.y() + " " + hit.z();
            graphics.text(client.font, line, 14, 27 + i * 12, hit.ore().argb(), false);
        }
        if (nearby.isEmpty()) {
            graphics.text(client.font, "Waiting for seed map...", 14, 27, 0xFFCCCCCC, false);
        }
    }

    private static List<OreHit> nearestHits(BlockPos playerPos) {
        List<OreHit> result = new ArrayList<>(renderHits);
        result.sort(Comparator.comparingLong(hit -> {
            long dx = hit.x() - playerPos.getX();
            long dy = hit.y() - playerPos.getY();
            long dz = hit.z() - playerPos.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return result;
    }

    private static void renderHighlights(LevelRenderContext context) {
        if (!OreScoutConfig.isOverlayEnabled() || renderHits.isEmpty()) return;
        RenderPipeline pipeline = THROUGH_WALLS;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) return;

        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(
            format, topology,
            topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
        );

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer builder = BUFFER.getVertexBuilder(draw);
        for (OreHit hit : renderHits) {
            OreInfo ore = hit.ore();
            renderFilledBox(matrices.last().pose(), builder,
                hit.x(), hit.y(), hit.z(), hit.x() + 1, hit.y() + 1, hit.z() + 1,
                ore.r(), ore.g(), ore.b(), 0.28f);
        }
        matrices.popPose();

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) draw(Minecraft.getInstance(), info, pipeline);
        BUFFER.endFrame();
    }

    private static void renderFilledBox(Matrix4fc matrix, VertexConsumer buffer,
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
            float red, float green, float blue, float alpha) {
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, maxY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, minZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, maxX, minY, maxZ).setColor(red, green, blue, alpha);
        buffer.addVertex(matrix, minX, minY, maxZ).setColor(red, green, blue, alpha);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
            RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = target.getColorTextureView();
        if (colorTexture == null) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "OreScout ore highlights", colorTexture, Optional.empty(),
            target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public static void closeRenderer() {
        BUFFER.close();
    }

    private record OreInfo(String name, float r, float g, float b) {
        int argb() {
            int red = Math.round(r * 255), green = Math.round(g * 255), blue = Math.round(b * 255);
            return 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    private record OreHit(int x, int y, int z, OreInfo ore) {}
}
