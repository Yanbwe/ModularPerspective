package org.yanbwe.modularperspective.client.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.yanbwe.modularperspective.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * “幽灵方块”（v0.2 第二块 → v0.3 贴图版）：方块级剔除会把相机-玩家视线上的墙挖出隧道，
 * 如果没有任何提示，玩家会以为区块没渲染好。
 *
 * <p>这里分两层：先画青色半透明线框（{@code culling.ghostOutline}），在
 * {@code culling.ghostFill} 开启时再按<b>方块模型本身</b>绘制一层半透明贴图体积
 * （{@code RenderType.translucent()} + {@link BakedModel} 的 {@link com.mojang.blaze3d.vertex.BakedQuad}，
 * 与原型模组 GhostRenderer 的“贴图幽灵”等价）。内部面（相邻方块也是幽灵）不画，避免体积内部发黑。
 * 纯客户端、只读 {@link BlockCuller} / {@link RoomScanner} / {@link SightlineScanner} 状态。
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final int MAX_BOXES = 4096;
    private static final float RED = 0.35F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    private static final float LINE_ALPHA = 0.35F;
    private static final float FILL_ALPHA = 0.3F;
    private static final float TINT_RED = 0.75F;
    private static final float TINT_GREEN = 0.9F;
    private static final float TINT_BLUE = 1.0F;

    private CullingGhostRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        boolean blockCulling = BlockCuller.isCulling() && !BlockCuller.culledBlocks().isEmpty();
        boolean roomCulling = RoomScanner.get().isIndoor();
        boolean sightlineCulling = SightlineScanner.get().isCulling() && SightlineScanner.get().culledCount() > 0;
        if (!Config.CULLING_GHOST.get() || (!blockCulling && !roomCulling && !sightlineCulling)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

        List<BlockPos> boxes = new ArrayList<>();
        if (blockCulling) {
            LongIterator iterator = BlockCuller.culledBlocks().iterator();
            while (iterator.hasNext() && boxes.size() < MAX_BOXES) {
                BlockPos pos = BlockPos.of(iterator.nextLong());
                if (inRange(pos, camera, maxDistanceSq)) {
                    boxes.add(pos.immutable());
                }
            }
        }
        if (sightlineCulling && boxes.size() < MAX_BOXES) {
            SightlineScanner.get().forEachCulledBlock(pos -> {
                if (boxes.size() >= MAX_BOXES || !inRange(pos, camera, maxDistanceSq)) {
                    return;
                }
                boxes.add(pos.immutable());
            });
        }
        if (roomCulling && boxes.size() < MAX_BOXES) {
            RoomScanner.get().forEachCulledBlock(pos -> {
                if (boxes.size() >= MAX_BOXES || !inRange(pos, camera, maxDistanceSq)) {
                    return;
                }
                if (blockCulling && BlockCuller.isCulled(pos)) {
                    return;
                }
                boxes.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
            });
        }
        if (boxes.isEmpty()) {
            return;
        }

        if (Config.CULLING_GHOST.get()) {
            VertexConsumer lines = buffers.getBuffer(RenderType.lines());
            for (BlockPos pos : boxes) {
                poseStack.pushPose();
                poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
                LevelRenderer.renderLineBox(poseStack, lines, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                        RED, GREEN, BLUE, LINE_ALPHA);
                poseStack.popPose();
            }
            buffers.endBatch(RenderType.lines());
        }

        if (!Config.CULLING_GHOST_FILL.get()) {
            return;
        }
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create(42L);
        VertexConsumer textured = buffers.getBuffer(RenderType.translucent());
        for (BlockPos pos : boxes) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            BakedModel model = dispatcher.getBlockModel(state);
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            PoseStack.Pose pose = poseStack.last();
            // Ghost blocks are already removed from the mesh, so their sampled light is stale/dark;
            // draw them fully bright like a blueprint instead.
            int light = LightTexture.FULL_BRIGHT;
            for (Direction direction : Direction.values()) {
                for (var quad : model.getQuads(state, direction, random)) {
                    textured.putBulkData(pose, quad, TINT_RED, TINT_GREEN, TINT_BLUE, FILL_ALPHA, light,
                            OverlayTexture.NO_OVERLAY);
                }
            }
            for (var quad : model.getQuads(state, null, random)) {
                textured.putBulkData(pose, quad, TINT_RED, TINT_GREEN, TINT_BLUE, FILL_ALPHA, light,
                        OverlayTexture.NO_OVERLAY);
            }
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.translucent());
    }

    private static boolean inRange(BlockPos pos, Vec3 camera, double maxDistanceSq) {
        double dx = pos.getX() + 0.5D - camera.x;
        double dy = pos.getY() + 0.5D - camera.y;
        double dz = pos.getZ() + 0.5D - camera.z;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }
}
