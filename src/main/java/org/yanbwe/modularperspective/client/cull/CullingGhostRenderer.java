package org.yanbwe.modularperspective.client.cull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

import java.util.ArrayList;
import java.util.List;

/**
 * “幽灵方块”：把被剔除的方块按原型模组 {@code GhostRenderer} 的规则补回半透明体。
 *
 * <p>与旧版的区别（2026-11-08 实机反馈 R2，研读原型后对齐）：
 * <ul>
 *   <li>透明度按<b>屏幕空间</b>到角色投影点的距离计算，而不是世界空间的轴线垂距：
 *       正交/等距下世界的圆柱会投影成椭圆，世界距离近不代表画面上离角色近；</li>
 *   <li>角色投影点周围 {@code ghostClearScreen} 内完全透明（并丢弃任何与这个屏幕口袋相交的
 *       面，避免大面片盖住角色）；随后 smoothstep 到 {@code ghostOpaqueScreen} 处的
 *       {@code ghostMaxAlpha}，两端平滑无硬边；</li>
 *   <li>只补“原本可见的外表面”：邻居是幽灵或原本不透明遮挡的面跳过；背向相机的面丢弃，
 *       避免同一个壳叠两层；</li>
 *   <li>画面上完全在屏幕外的面直接丢弃。</li>
 * </ul>
 * 被剔除方块在区块网格里按空气烘焙，因此邻位会重新生成剖切面；剖切面由
 * {@code ModelBlockRendererMixin} 统一抑制（m07847 的“③ 面”修复）。
 */
public final class CullingGhostRenderer {
    private static final double MAX_RENDER_DISTANCE = 64.0D;
    private static final int MAX_BOXES = 4096;
    private static final float MIN_ALPHA = 0.02F;
    private static final float TINT_RED = 0.82F;
    private static final float TINT_GREEN = 0.93F;
    private static final float TINT_BLUE = 1.0F;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** BakedQuad 每个顶点 8 个 int（pos x/y/z 的 float bits、color、u/v 的 float bits、light、…）。 */
    private static final int VERTEX_INTS = 8;

    private CullingGhostRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!Config.CULLING_GHOST.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }
        boolean roomCulling = RoomScanner.get().isIndoor();
        boolean sightlineCulling = SightlineScanner.get().isCulling() && SightlineScanner.get().culledCount() > 0;
        if (!roomCulling && !sightlineCulling) {
            return;
        }

        Vec3 cameraPos = event.getCamera().getPosition();
        double maxDistanceSq = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

        List<BlockPos> boxes = new ArrayList<>();
        if (sightlineCulling) {
            SightlineScanner.get().forEachCulledBlock(pos -> {
                if (boxes.size() < MAX_BOXES && inRange(pos, cameraPos, maxDistanceSq)) {
                    boxes.add(pos.immutable());
                }
            });
        }
        if (roomCulling && boxes.size() < MAX_BOXES) {
            RoomScanner.get().forEachCulledBlock(pos -> {
                if (boxes.size() < MAX_BOXES && inRange(pos, cameraPos, maxDistanceSq)) {
                    boxes.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
                }
            });
        }        if (boxes.isEmpty()) {
            return;
        }

        CaperaBasis basis = CaperaBasis.create(event, minecraft, cameraPos);
        float[] focus = new float[2];
        if (!basis.project(minecraft.player.getBoundingBox().getCenter(), cameraPos, focus)) {
            return;
        }
        float clearAt = Math.max(0.0F, Config.GHOST_CLEAR_SCREEN.get().floatValue());
        float opaqueAt = Math.max(clearAt + 0.01F, Config.GHOST_OPAQUE_SCREEN.get().floatValue());
        float maxAlpha = Config.GHOST_MAX_ALPHA.get().floatValue();
        if (maxAlpha <= MIN_ALPHA) {
            return;
        }
        float halfWidth = basis.aspect();

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
        float[] cornerX = new float[4];
        float[] cornerY = new float[4];
        try {
            for (BlockPos pos : boxes) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) {
                    continue;
                }
                BakedModel model = dispatcher.getBlockModel(state);
                poseStack.pushPose();
                poseStack.translate(pos.getX() - cameraPos.x, pos.getY() - cameraPos.y, pos.getZ() - cameraPos.z);
                PoseStack.Pose pose = poseStack.last();
                for (Direction direction : DIRECTIONS) {
                    neighbour.setWithOffset(pos, direction);
                    if (boxSet.contains(neighbour.asLong())) {
                        continue;
                    }
                    if (isOccluder(level, neighbour)) {
                        continue;
                    }
                    for (BakedQuad quad : model.getQuads(state, direction, random)) {
                        drawQuad(quad, pos, direction, cameraPos, pose, textured, basis, focus,
                                clearAt, opaqueAt, maxAlpha, halfWidth, cornerX, cornerY);
                    }
                }
                for (BakedQuad quad : model.getQuads(state, null, random)) {
                    drawQuad(quad, pos, quad.getDirection(), cameraPos, pose, textured, basis, focus,
                            clearAt, opaqueAt, maxAlpha, halfWidth, cornerX, cornerY);
                }
                poseStack.popPose();
            }
        } finally {
            buffers.endBatch(RenderType.translucent());
        }
    }

    private static void drawQuad(BakedQuad quad, BlockPos blockPos, Direction direction, Vec3 cameraPos,
            PoseStack.Pose pose, VertexConsumer consumer, CaperaBasis basis, float[] focus,
            float clearAt, float opaqueAt, float maxAlpha, float halfWidth,
            float[] cornerX, float[] cornerY) {
        int[] vertices = quad.getVertices();
        if (vertices.length < VERTEX_INTS * 4) {
            return;
        }
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float sumX = 0.0F, sumY = 0.0F;
        int count = 0;
        for (int i = 0; i < 4; i++) {
            int base = i * VERTEX_INTS;
            float lx = Float.intBitsToFloat(vertices[base]);
            float ly = Float.intBitsToFloat(vertices[base + 1]);
            float lz = Float.intBitsToFloat(vertices[base + 2]);
            float[] out = new float[2];
            if (!basis.project(new Vec3(blockPos.getX() + lx, blockPos.getY() + ly, blockPos.getZ() + lz),
                    cameraPos, out)) {
                return;
            }
            cornerX[i] = out[0];
            cornerY[i] = out[1];
            minX = Math.min(minX, out[0]);
            maxX = Math.max(maxX, out[0]);
            minY = Math.min(minY, out[1]);
            maxY = Math.max(maxY, out[1]);
            sumX += out[0];
            sumY += out[1];
            count++;
        }
        if (count != 4) {
            return;
        }
        // 完全在屏幕外
        if (minX > halfWidth || maxX < -halfWidth || minY > 1.0F || maxY < -1.0F) {
            return;
        }
        // 屏幕口袋：与角色投影点附近相交的面一律不画，避免大面片盖住角色
        if (maxX >= focus[0] - clearAt && minX <= focus[0] + clearAt
                && maxY >= focus[1] - clearAt && minY <= focus[1] + clearAt) {
            return;
        }
        // 背面：方块中心到相机方向与面法线同向则跳过（壳只画一层）
        Vec3 normal = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        if (normal.dot(new Vec3(blockPos.getX() + 0.5D, blockPos.getY() + 0.5D, blockPos.getZ() + 0.5D)
                .subtract(cameraPos)) > 0.0D) {
            return;
        }
        float distX = sumX / count - focus[0];
        float distY = sumY / count - focus[1];
        float alpha = curve((float) Math.sqrt(distX * distX + distY * distY), clearAt, opaqueAt, maxAlpha);
        if (alpha <= MIN_ALPHA) {
            return;
        }
        consumer.putBulkData(pose, quad, TINT_RED, TINT_GREEN, TINT_BLUE, alpha,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }

    /** 屏幕空间：x 轴到 ±aspect、y 轴到 ±1；正交时用俯视半高作为 scale，透视时用 tan(fov/2)。 */
    private record CaperaBasis(Vec3 forward, Vec3 right, Vec3 up, boolean ortho, double scale, float aspect) {
        static CaperaBasis create(RenderLevelStageEvent event, Minecraft minecraft, Vec3 cameraPos) {
            Camera camera = event.getCamera();
            Vec3 forward = new Vec3(camera.getLookVector());
            Vec3 right = new Vec3(camera.getLeftVector()).scale(-1.0D);
            Vec3 up = new Vec3(camera.getUpVector());
            boolean ortho = ClientPerspective.isOrtho();
            double scale;
            if (ortho) {
                var state = ClientPerspective.current();
                scale = state != null && state.distance() != null
                        ? state.distance() : ClientPerspective.DEFAULT_TOP_DOWN_DISTANCE;
            } else {
                double fov = minecraft.options.fov().get();
                scale = Math.tan(Math.toRadians(fov) / 2.0D);
            }
            scale = Math.max(1.0E-4D, scale);
            var window = minecraft.getWindow();
            float aspect = window.getHeight() <= 0 ? 1.0F
                    : (float) window.getWidth() / (float) window.getHeight();
            return new CaperaBasis(forward, right, up, ortho, scale, aspect);
        }

        boolean project(Vec3 world, Vec3 cameraPos, float[] out) {
            double dx = world.x - cameraPos.x;
            double dy = world.y - cameraPos.y;
            double dz = world.z - cameraPos.z;
            double sx = dx * right.x + dy * right.y + dz * right.z;
            double sy = dx * up.x + dy * up.y + dz * up.z;
            if (ortho) {
                out[0] = (float) (sx / scale);
                out[1] = (float) (sy / scale);
                return true;
            }
            double depth = dx * forward.x + dy * forward.y + dz * forward.z;
            if (depth <= 1.0E-4D) {
                return false;
            }
            out[0] = (float) (sx / (depth * scale));
            out[1] = (float) (sy / (depth * scale));
            return true;
        }
    }

    private static float curve(float dist, float clearAt, float opaqueAt, float maxAlpha) {
        if (dist <= clearAt) {
            return 0.0F;
        }
        float t = Math.min(1.0F, (dist - clearAt) / (opaqueAt - clearAt));
        return maxAlpha * t * t * (3.0F - 2.0F * t);
    }

    /** 实心方块或树类遮挡体都算“原本不可见/不可穿”的邻居，跳过对应面。 */
    private static boolean isOccluder(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isSolidRender(level, pos) || OccluderClass.classify(state) == OccluderClass.TREE;
    }
    private static boolean inRange(BlockPos pos, Vec3 cameraPos, double maxDistanceSq) {
        double dx = pos.getX() + 0.5D - cameraPos.x;
        double dy = pos.getY() + 0.5D - cameraPos.y;
        double dz = pos.getZ() + 0.5D - cameraPos.z;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSq;
    }
}
