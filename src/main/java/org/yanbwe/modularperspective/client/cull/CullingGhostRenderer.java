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
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.yanbwe.modularperspective.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * “幽灵方块”（m07629 重做）：被剔除的方块以方块模型贴图的半透明体积显示，
 * 让玩家知道这里不是区块没渲染。
 *
 * <p>视觉规则（用户现场反馈）：
 * <ul>
 *   <li>不再画蓝色线框（很丑，删除）；</li>
 *   <li>透明度随“离玩家（屏幕中心）的水平距离”变化：近 1.5 格完全透明，
 *       远到 12 格时最不透明（0.45），中间线性渐变；</li>
 *   <li>相邻方块也在幽灵集合里时跳过该方向的面，避免体积内部发黑/重叠。</li>
 * </ul>
 *
 * <p>纯客户端、只读 {@link BlockCuller} / {@link RoomScanner} / {@link SightlineScanner} 状态。
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final int MAX_BOXES = 4096;
    private static final double NEAR_DISTANCE = 1.5D;
    private static final double FAR_DISTANCE = 12.0D;
    private static final float MIN_ALPHA = 0.02F;
    private static final float MAX_FILL_ALPHA = 0.45F;
    private static final float TINT_RED = 0.75F;
    private static final float TINT_GREEN = 0.9F;
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
        boolean blockCulling = BlockCuller.isCulling() && !BlockCuller.culledBlocks().isEmpty();
        boolean roomCulling = RoomScanner.get().isIndoor();
        boolean sightlineCulling = SightlineScanner.get().isCulling() && SightlineScanner.get().culledCount() > 0;
        if (!blockCulling && !roomCulling && !sightlineCulling) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        Vec3 player = minecraft.player.position();
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
        for (BlockPos pos : boxes) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            double dx = pos.getX() + 0.5D - player.x;
            double dz = pos.getZ() + 0.5D - player.z;
            float alpha = alphaFor(Math.sqrt(dx * dx + dz * dz));
            if (alpha < MIN_ALPHA) {
                continue;
            }
            BakedModel model = dispatcher.getBlockModel(state);
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            PoseStack.Pose pose = poseStack.last();
            // 被剔除方块的世界光照已失效（方块被当作空气），按蓝图风格全亮绘制。
            int light = LightTexture.FULL_BRIGHT;
            for (Direction direction : DIRECTIONS) {
                neighbour.setWithOffset(pos, direction);
                if (boxSet.contains(neighbour.asLong())) {
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

    private static float alphaFor(double distance) {
        if (distance <= NEAR_DISTANCE) {
            return 0.0F;
        }
        double t = Mth.clamp((distance - NEAR_DISTANCE) / (FAR_DISTANCE - NEAR_DISTANCE), 0.0D, 1.0D);
        return (float) (t * MAX_FILL_ALPHA);
    }

    private static boolean inRange(BlockPos pos, Vec3 camera, double maxDistanceSq) {
        double dx = pos.getX() + 0.5D - camera.x;
        double dy = pos.getY() + 0.5D - camera.y;
        double dz = pos.getZ() + 0.5D - camera.z;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }
}
