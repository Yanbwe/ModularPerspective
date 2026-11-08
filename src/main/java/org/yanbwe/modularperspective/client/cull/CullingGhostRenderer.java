package org.yanbwe.modularperspective.client.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.yanbwe.modularperspective.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * “幽灵方块”（v0.2 第二块 → v0.3 升级）：方块级剔除会把相机-玩家视线上的墙挖出隧道，
 * 如果没有任何提示，玩家会以为区块没渲染好。这里给被剔除方块先画青色半透明线框，
 * 再画一层半透明体积（debugQuads，按面剔除内部面），让玩家看到“这些方块是被主动隐藏的”。
 * 纯客户端、只读 {@link BlockCuller} / {@link RoomScanner} 状态。
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final int MAX_BOXES = 4096;
    private static final float RED = 0.35F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    private static final float LINE_ALPHA = 0.35F;
    private static final float FILL_ALPHA = 0.16F;

    private CullingGhostRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        boolean blockCulling = BlockCuller.isCulling() && !BlockCuller.culledBlocks().isEmpty();
        boolean roomCulling = RoomScanner.get().isIndoor();
        if (!Config.CULLING_GHOST.get() || (!blockCulling && !roomCulling)) {
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

        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos pos : boxes) {
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            LevelRenderer.renderLineBox(poseStack, lines, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), RED, GREEN, BLUE, LINE_ALPHA);
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.lines());

        if (!Config.CULLING_GHOST_FILL.get()) {
            return;
        }
        VertexConsumer fills = buffers.getBuffer(RenderType.debugQuads());
        for (BlockPos pos : boxes) {
            drawGhostCube(poseStack, fills, camera, pos);
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    private static boolean inRange(BlockPos pos, Vec3 camera, double maxDistanceSq) {
        double dx = pos.getX() + 0.5D - camera.x;
        double dy = pos.getY() + 0.5D - camera.y;
        double dz = pos.getZ() + 0.5D - camera.z;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }

    private static boolean isGhost(BlockPos pos) {
        if (BlockCuller.isCulled(pos)) {
            return true;
        }
        return RoomScanner.get().test(pos) == RoomScanner.CULL;
    }

    private static void drawGhostCube(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, BlockPos pos) {
        boolean up = !isGhost(pos.offset(0, 1, 0));
        boolean down = !isGhost(pos.offset(0, -1, 0));
        boolean north = !isGhost(pos.offset(0, 0, -1));
        boolean south = !isGhost(pos.offset(0, 0, 1));
        boolean west = !isGhost(pos.offset(-1, 0, 0));
        boolean east = !isGhost(pos.offset(1, 0, 0));
        if (!up && !down && !north && !south && !west && !east) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        Matrix4f pose = poseStack.last().pose();
        if (up) {
            quad(consumer, pose, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F);
        }
        if (down) {
            quad(consumer, pose, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        if (north) {
            quad(consumer, pose, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        }
        if (south) {
            quad(consumer, pose, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F);
        }
        if (west) {
            quad(consumer, pose, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.0F);
        }
        if (east) {
            quad(consumer, pose, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        }
        poseStack.popPose();
    }

    private static void quad(VertexConsumer consumer, Matrix4f pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        vertex(consumer, pose, x1, y1, z1);
        vertex(consumer, pose, x2, y2, z2);
        vertex(consumer, pose, x3, y3, z3);
        vertex(consumer, pose, x4, y4, z4);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, float x, float y, float z) {
        consumer.addVertex(pose, x, y, z).setColor(RED, GREEN, BLUE, FILL_ALPHA);
    }
}