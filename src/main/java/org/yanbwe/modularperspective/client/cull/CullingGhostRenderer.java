package org.yanbwe.modularperspective.client.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.yanbwe.modularperspective.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * “幽灵方块”（v0.4，2026-11-08 实机反馈 R1）：被剔除的方块以方块贴图的半透明体积显示。
 *
 * <p>视觉规则：
 * <ul>
 *   <li>不画线框/边框；</li>
 *   <li>不透明度按“方块到相机-角色连线（{@link VolumeCuller} 的轴）的垂直距离”计算：
 *       3 格以内完全透明、随后快速上升，8 格外达到上限；连线首尾用平滑包络收口；</li>
 *   <li>只画朝向空气/透明邻居的面：邻居也是幽灵或被遮挡判定为实心时跳过，避免内部面重叠；</li>
 *   <li>房间剔除块（屋顶 span）按到角色的距离给一个稳定的低透明度，避免整片屋顶直接消失。</li>
 * </ul>
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final int MAX_BOXES = 4096;
    private static final float MIN_ALPHA = 0.02F;
    private static final float ROOM_ALPHA = 0.30F;
    private static final double ROOM_FADE_DISTANCE = 40.0D;
    private static final float TINT_RED = 0.80F;
    private static final float TINT_GREEN = 0.92F;
    private static final float TINT_BLUE = 1.0F;
    private static final Direction[] DIRECTIONS = Direction.values();

    private CullingGhostRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!Config.CULLING_GHOST.get() || !Config.CULLING_GHOST_FILL.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        boolean volumeCulling = VolumeCuller.get().isActive();
        boolean roomCulling = RoomScanner.get().isIndoor();
        boolean legacyCulling = (BlockCuller.isCulling() && !BlockCuller.culledBlocks().isEmpty())
                || (SightlineScanner.get().isCulling() && SightlineScanner.get().culledCount() > 0);
        if (!volumeCulling && !roomCulling && !legacyCulling) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        Vec3 axisStart = VolumeCuller.get().cameraPos();
        Vec3 axisEnd = VolumeCuller.get().targetPos();
        if (axisStart.equals(Vec3.ZERO) || axisEnd.equals(Vec3.ZERO)) {
            axisStart = camera;
            axisEnd = minecraft.player.getEyePosition(1.0F);
        }
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
        double inner = Config.CULLING_VOLUME_INNER_RADIUS.get();
        double outer = Config.CULLING_VOLUME_OUTER_RADIUS.get();
        double maxAlpha = Config.CULLING_VOLUME_MAX_ALPHA.get();
        Vec3 player = minecraft.player.position();

        List<BlockPos> boxes = new ArrayList<>();
        if (volumeCulling) {
            VolumeCuller.get().forEachCulledBlock(pos -> {
                if (boxes.size() < MAX_BOXES && inRange(pos, camera, maxDistanceSq)) {
                    boxes.add(pos.immutable());
                }
            });
        }
        if (legacyCulling && boxes.size() < MAX_BOXES) {
            if (BlockCuller.isCulling()) {
                LongIterator iterator = BlockCuller.culledBlocks().iterator();
                while (iterator.hasNext() && boxes.size() < MAX_BOXES) {
                    BlockPos pos = BlockPos.of(iterator.nextLong());
                    if (inRange(pos, camera, maxDistanceSq)) {
                        boxes.add(pos);
                    }
                }
            }
            if (boxes.size() < MAX_BOXES) {
                SightlineScanner.get().forEachCulledBlock(pos -> {
                    if (boxes.size() < MAX_BOXES && inRange(pos, camera, maxDistanceSq)) {
                        boxes.add(pos.immutable());
                    }
                });
            }
        }
        if (roomCulling && boxes.size() < MAX_BOXES) {
            RoomScanner.get().forEachCulledBlock(pos -> {
                if (boxes.size() < MAX_BOXES && inRange(pos, camera, maxDistanceSq)) {
                    boxes.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
                }
            });
        }
        if (boxes.isEmpty()) {
            return;
        }

        LongOpenHashSet boxSet = new LongOpenHashSet();
        for (BlockPos pos : boxes) {
            boxSet.add(pos.asLong());
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        PoseStack poseStack = event.getPoseStack();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        RandomSource random = RandomSource.create(42L);
        VertexConsumer textured = buffers.getBuffer(RenderType.translucent());
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        double[] tBuffer = new double[1];
        for (BlockPos pos : boxes) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            double cx = pos.getX() + 0.5D;
            double cy = pos.getY() + 0.5D;
            double cz = pos.getZ() + 0.5D;
            float alpha;
            if (volumeCulling && VolumeCuller.get().isCulled(pos)) {
                double distance = SightlineVolume.perpendicularDistance(
                        cx, cy, cz, axisStart.x, axisStart.y, axisStart.z,
                        axisEnd.x, axisEnd.y, axisEnd.z, tBuffer);
                alpha = (float) SightlineVolume.alpha(distance, tBuffer[0], inner, outer, maxAlpha);
            } else {
                double dx = cx - player.x;
                double dz = cz - player.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                alpha = (float) (ROOM_ALPHA * Math.max(0.0D, 1.0D - horizontal / ROOM_FADE_DISTANCE));
            }
            if (alpha < MIN_ALPHA) {
                continue;
            }
            BakedModel model = dispatcher.getBlockModel(state);
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            PoseStack.Pose pose = poseStack.last();
            int light = LightTexture.FULL_BRIGHT;
            for (Direction direction : DIRECTIONS) {
                neighbour.setWithOffset(pos, direction);
                if (boxSet.contains(neighbour.asLong())) {
                    continue;
                }
                if (CullingOccluders.isOccluder(level, neighbour)) {
                    continue;
                }
                for (var quad : model.getQuads(state, direction, random)) {
                    textured.putBulkData(pose, quad, TINT_RED, TINT_GREEN, TINT_BLUE, alpha, light,
                            OverlayTexture.NO_OVERLAY);
                }
            }
            for (var quad : model.getQuads(state, null, random)) {
                textured.putBulkData(pose, quad, TINT_RED, TINT_GREEN, TINT_BLUE, alpha, light,
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
