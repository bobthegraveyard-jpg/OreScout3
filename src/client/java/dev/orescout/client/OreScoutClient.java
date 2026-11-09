package dev.orescout.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

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

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import dev.orescout.OreScout;

public final class OreScoutClient implements ClientModInitializer {
    private static final int SCAN_RADIUS = 75;
    private static final int Y_LAYERS_PER_TICK = 4;
    private static final int MAX_TRACKED_ORES = 1800;
    private static final int DISPLAY_LIMIT = 8;

    // Vanilla 26.2 world generation is deterministic for this seed.
    private static final long TARGET_SEED = 6608149111735331168L;
    private static final int PREDICTION_RADIUS_CHUNKS = 6;
    private static final int TICKS_BETWEEN_CHUNK_REQUESTS = 8;
    private static final ArrayDeque<Long> PREDICTION_QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> GENERATED_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<OreHit> PREDICTION_RESULTS = new ConcurrentLinkedQueue<>();
    private static int ticksUntilNextChunk;
    private static int worldEpoch;

    private static final Map<Block, OreInfo> ORE_TYPES = new LinkedHashMap<>();
    private static final Map<Long, OreHit> FOUND = new LinkedHashMap<>();
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
    private static Level lastLevel = null;
    private static int scanY = Integer.MIN_VALUE;

    static {
        add(Blocks.DIAMOND_ORE, "Diamond", 0.15f, 0.85f, 1.00f);
        add(Blocks.DEEPSLATE_DIAMOND_ORE, "Deepslate diamond", 0.15f, 0.85f, 1.00f);
        add(Blocks.EMERALD_ORE, "Emerald", 0.10f, 1.00f, 0.35f);
        add(Blocks.DEEPSLATE_EMERALD_ORE, "Deepslate emerald", 0.10f, 1.00f, 0.35f);
        add(Blocks.COAL_ORE, "Coal", 0.35f, 0.35f, 0.35f);
        add(Blocks.DEEPSLATE_COAL_ORE, "Deepslate coal", 0.35f, 0.35f, 0.35f);
        add(Blocks.GOLD_ORE, "Gold", 1.00f, 0.75f, 0.05f);
        add(Blocks.DEEPSLATE_GOLD_ORE, "Deepslate gold", 1.00f, 0.75f, 0.05f);
        add(Blocks.IRON_ORE, "Iron", 0.85f, 0.75f, 0.65f);
        add(Blocks.DEEPSLATE_IRON_ORE, "Deepslate iron", 0.85f, 0.75f, 0.65f);
        add(Blocks.COPPER_ORE, "Copper", 0.90f, 0.40f, 0.20f);
        add(Blocks.DEEPSLATE_COPPER_ORE, "Deepslate copper", 0.90f, 0.40f, 0.20f);
        add(Blocks.LAPIS_ORE, "Lapis", 0.15f, 0.30f, 1.00f);
        add(Blocks.DEEPSLATE_LAPIS_ORE, "Deepslate lapis", 0.15f, 0.30f, 1.00f);
        add(Blocks.REDSTONE_ORE, "Redstone", 1.00f, 0.10f, 0.10f);
        add(Blocks.DEEPSLATE_REDSTONE_ORE, "Deepslate redstone", 1.00f, 0.10f, 0.10f);
        add(Blocks.NETHER_GOLD_ORE, "Nether gold", 1.00f, 0.65f, 0.05f);
        add(Blocks.NETHER_QUARTZ_ORE, "Nether quartz", 0.95f, 0.85f, 0.80f);
        add(Blocks.ANCIENT_DEBRIS, "Ancient debris", 0.65f, 0.25f, 0.15f);
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
        LevelExtractionEvents.END_EXTRACTION.register(context -> renderHits = List.copyOf(FOUND.values()));
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(OreScoutClient::renderHighlights);
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT, OreScout.id("ore_status"), OreScoutClient::renderHud
        );
    }

    private static void add(Block block, String name, float r, float g, float b) {
        ORE_TYPES.put(block, new OreInfo(block, name, r, g, b));
    }

    public static List<String> getOreNames() {
        return ORE_TYPES.values().stream().map(OreInfo::name).distinct().toList();
    }

    private static void onClientTick(Minecraft client) {
        boolean togglePressed = toggleOverlayKey != null && toggleOverlayKey.consumeClick();
        boolean settingsPressed = openSettingsKey != null && openSettingsKey.consumeClick();
        // Drain key clicks while a screen is open, but only act during gameplay.
        if (client.player != null && client.gui.screen() == null) {
            if (togglePressed) {
                OreScoutConfig.setOverlayEnabled(!OreScoutConfig.isOverlayEnabled());
            }
            if (settingsPressed) {
                client.gui.setScreen(new OreScoutConfigScreen(client.gui.screen()));
            }
        }

        if (client.level == null || client.player == null) {
            FOUND.clear();
            renderHits = List.of();
            lastLevel = null;
            scanY = Integer.MIN_VALUE;
            return;
        }

        Level level = client.level;
        if (level != lastLevel) {
            FOUND.clear();
            PREDICTION_QUEUE.clear();
            QUEUED_CHUNKS.clear();
            GENERATED_CHUNKS.clear();
            PREDICTION_RESULTS.clear();
            worldEpoch++;
            lastLevel = level;
            scanY = level.getMinY();
        }

        drainPredictionResults();
        tickSeedPrediction(client);

        int minY = level.getMinY();
        int maxY = level.getMaxY();
        if (scanY < minY || scanY >= maxY) {
            scanY = minY;
        }

        int endY = Math.min(maxY, scanY + Y_LAYERS_PER_TICK);
        int centerX = client.player.blockPosition().getX();
        int centerZ = client.player.blockPosition().getZ();
        int radiusSquared = SCAN_RADIUS * SCAN_RADIUS;

        for (int x = centerX - SCAN_RADIUS; x <= centerX + SCAN_RADIUS; x++) {
            int dx = x - centerX;
            for (int z = centerZ - SCAN_RADIUS; z <= centerZ + SCAN_RADIUS; z++) {
                int dz = z - centerZ;
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }

                // hasChunkAt prevents the scanner from requesting or generating new chunks.
                if (!level.hasChunkAt(new BlockPos(x, scanY, z))) {
                    continue;
                }

                for (int y = scanY; y < endY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    OreInfo ore = ORE_TYPES.get(state.getBlock());
                    if (ore != null && OreScoutConfig.isOreEnabled(ore.name())) {
                        FOUND.put(pos.asLong(), new OreHit(x, y, z, ore, false));
                    }
                }
            }
        }

        scanY = endY >= maxY ? minY : endY;
        reconcileTrackedOres(level);
        pruneFarAway(client.player.blockPosition());
    }

    private static void reconcileTrackedOres(Level level) {
        FOUND.entrySet().removeIf(entry -> {
            OreHit hit = entry.getValue();
            BlockPos pos = new BlockPos(hit.x(), hit.y(), hit.z());
            if (!level.hasChunkAt(pos)) return !hit.predicted();
            if (level.getBlockState(pos).getBlock() != hit.ore().block()) return true;
            if (hit.predicted()) entry.setValue(new OreHit(hit.x(), hit.y(), hit.z(), hit.ore(), false));
            return false;
        });
    }

    private static void drainPredictionResults() {
        OreHit hit;
        while ((hit = PREDICTION_RESULTS.poll()) != null) {
            FOUND.putIfAbsent(BlockPos.asLong(hit.x(), hit.y(), hit.z()), hit);
        }
    }

    private static void tickSeedPrediction(Minecraft client) {
        if (!OreScoutConfig.isSeedPredictionEnabled() || client.level == null
                || !client.level.dimension().equals(Level.OVERWORLD)) return;

        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return;
        ServerLevel serverLevel = server.getLevel(Level.OVERWORLD);
        if (serverLevel == null || serverLevel.getSeed() != TARGET_SEED) return;

        int centerX = client.player.blockPosition().getX() >> 4;
        int centerZ = client.player.blockPosition().getZ() >> 4;
        for (int ring = 0; ring <= PREDICTION_RADIUS_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = chunkKey(centerX + dx, centerZ + dz);
                    if (!GENERATED_CHUNKS.contains(key) && QUEUED_CHUNKS.add(key)) {
                        PREDICTION_QUEUE.addLast(key);
                    }
                }
            }
        }

        if (ticksUntilNextChunk > 0) {
            ticksUntilNextChunk--;
            return;
        }
        Long key = PREDICTION_QUEUE.pollFirst();
        if (key == null) return;
        ticksUntilNextChunk = TICKS_BETWEEN_CHUNK_REQUESTS;
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        int requestEpoch = worldEpoch;

        // Ask the integrated vanilla server to run the real 26.2 chunk generator.
        server.execute(() -> {
            try {
                ChunkAccess chunk = serverLevel.getChunkSource().getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, true
                );
                if (chunk == null) return;
                List<OreHit> found = new ArrayList<>();
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int x = chunkX << 4; x < (chunkX << 4) + 16; x++) {
                    for (int z = chunkZ << 4; z < (chunkZ << 4) + 16; z++) {
                        for (int y = serverLevel.getMinY(); y < serverLevel.getMaxY(); y++) {
                            pos.set(x, y, z);
                            OreInfo ore = ORE_TYPES.get(chunk.getBlockState(pos).getBlock());
                            if (ore != null && OreScoutConfig.isOreEnabled(ore.name())) {
                                found.add(new OreHit(x, y, z, ore, true));
                            }
                        }
                    }
                }
                if (requestEpoch == worldEpoch) {
                    PREDICTION_RESULTS.addAll(found);
                    GENERATED_CHUNKS.add(key);
                }
            } catch (Throwable error) {
                System.err.println("[OreScout] Seed scan failed for chunk " + chunkX + "," + chunkZ
                    + ": " + error.getMessage());
            } finally {
                QUEUED_CHUNKS.remove(key);
            }
        });
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static void pruneFarAway(BlockPos playerPos) {
        int maxDistance = SCAN_RADIUS + 24;
        int maxDistanceSquared = maxDistance * maxDistance;
        FOUND.entrySet().removeIf(entry -> {
            OreHit hit = entry.getValue();
            if (!OreScoutConfig.isOreEnabled(hit.ore().name())) return true;
            int dx = hit.x() - playerPos.getX();
            int dy = hit.y() - playerPos.getY();
            int dz = hit.z() - playerPos.getZ();
            return dx * dx + dy * dy + dz * dz > maxDistanceSquared;
        });

        while (FOUND.size() > MAX_TRACKED_ORES) {
            Long first = FOUND.keySet().iterator().next();
            FOUND.remove(first);
        }
    }

    private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (!OreScoutConfig.isCoordinatesEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        List<OreHit> nearby = nearestHits(client.player.blockPosition());
        int rows = Math.min(DISPLAY_LIMIT, nearby.size());
        graphics.fill(8, 8, 220, 34 + rows * 12, 0x99000000);
        graphics.text(client.font, "OreScout  [P: settings] [O: boxes]", 14, 13, 0xFFFFFFFF, false);

        for (int i = 0; i < rows; i++) {
            OreHit hit = nearby.get(i);
            String line = (hit.predicted() ? "~ " : "") + hit.ore().name() + "  " + hit.x() + " " + hit.y() + " " + hit.z();
            graphics.text(client.font, line, 14, 27 + i * 12, hit.ore().argb(), false);
        }

        if (nearby.isEmpty()) {
            graphics.text(client.font, "Scanning loaded terrain...", 14, 27, 0xFFCCCCCC, false);
        }
    }

    private static List<OreHit> nearestHits(BlockPos playerPos) {
        List<OreHit> result = new ArrayList<>(FOUND.values());
        result.sort(Comparator.comparingLong(hit -> {
            long dx = hit.x() - playerPos.getX();
            long dy = hit.y() - playerPos.getY();
            long dz = hit.z() - playerPos.getZ();
            return dx * dx + dy * dy + dz * dz;
        }));
        return result;
    }

    private static void renderHighlights(LevelRenderContext context) {
        if (!OreScoutConfig.isOverlayEnabled() || renderHits.isEmpty()) {
            return;
        }

        RenderPipeline pipeline = THROUGH_WALLS;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) {
            return;
        }

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
            renderFilledBox(
                matrices.last().pose(), builder,
                hit.x(), hit.y(), hit.z(),
                hit.x() + 1, hit.y() + 1, hit.z() + 1,
                ore.r(), ore.g(), ore.b(), 0.28f
            );
        }
        matrices.popPose();

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) {
            draw(Minecraft.getInstance(), info, pipeline);
        }
        BUFFER.endFrame();
    }

    private static void renderFilledBox(
        Matrix4fc matrix, VertexConsumer buffer,
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ,
        float red, float green, float blue, float alpha
    ) {
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
            RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX
        );
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = target.getColorTextureView();
        if (colorTexture == null) {
            return;
        }

        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
            () -> "OreScout ore highlights",
            colorTexture, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty()
        )) {
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

    private record OreInfo(Block block, String name, float r, float g, float b) {
        int argb() {
            int red = Math.round(r * 255);
            int green = Math.round(g * 255);
            int blue = Math.round(b * 255);
            return 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    private record OreHit(int x, int y, int z, OreInfo ore, boolean predicted) {}
}
