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
import org.yanbwe.modularperspective.Config;

/**
 * “幽灵轮廓”（v0.2 第二块）：方块级剔除会把相机-玩家视线上的墙挖出隧道，如果没有任何提示，
 * 玩家会以为区块没渲染好。这里在渲染层给被剔除方块画一层青色半透明线框（只画线，不改可见性），
 * 让玩家知道“这些方块是被主动隐藏的”。纯客户端、只读 {@link BlockCuller} 状态。
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final float RED = 0.35F;
    private static final float GREEN = 0.85F;
    private static final float BLUE = 1.0F;
    private static final float ALPHA = 0.35F;

    private CullingGhostRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!Config.CULLING_GHOST.get() || !BlockCuller.isCulling()) {
            return;
        }
        var culled = BlockCuller.culledBlocks();
        if (culled.isEmpty()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

        LongIterator iterator = culled.iterator();
        while (iterator.hasNext()) {
            BlockPos pos = BlockPos.of(iterator.nextLong());
            double dx = pos.getX() + 0.5D - camera.x;
            double dy = pos.getY() + 0.5D - camera.y;
            double dz = pos.getZ() + 0.5D - camera.z;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            LevelRenderer.renderLineBox(poseStack, consumer, new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D), RED, GREEN, BLUE, ALPHA);
            poseStack.popPose();
        }
        buffers.endBatch(RenderType.lines());
    }
}
