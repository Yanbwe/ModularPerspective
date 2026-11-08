package org.yanbwe.modularperspective.client.cull;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

import java.util.function.Consumer;

/**
 * v0.4 剔除主策略（docs/design.md §11.2 调整，2026-11-08 实机反馈）：
 * 以“相机 → 角色眼睛”的连线为轴，剔除轴附近（outer 半径内）的遮挡方块。
 *
 * <p>首尾有平滑包络；离角色脚下及以下的地面不剔除，避免角色悬空。渲染层不再生成这些方块的网格，
 * 由 {@link CullingGhostRenderer} 按到轴线的距离绘制半透明方块体积。</p>
 */
public final class VolumeCuller {

    private static final VolumeCuller INSTANCE = new VolumeCuller();

    /** 单次最多剔除的方块数，避免极端场景卡顿。 */
    public static final int MAX_BLOCKS = 4096;
    /** 每次 rescan 最多标脏的 section 数。 */
    public static final int MAX_DIRTY_SECTIONS = 48;

    private final LongOpenHashSet culled = new LongOpenHashSet();
    private final LongOpenHashSet previous = new LongOpenHashSet();
    private final LongOpenHashSet next = new LongOpenHashSet();
    private final LongOpenHashSet dirtySections = new LongOpenHashSet();

    private int lastTick = Integer.MIN_VALUE;
    private boolean lastValid;
    private Vec3 lastCamera = Vec3.ZERO;
    private Vec3 lastTarget = Vec3.ZERO;
    private Vec3 cameraPos = Vec3.ZERO;
    private Vec3 targetPos = Vec3.ZERO;
    private int culledCount;

    private VolumeCuller() {
    }

    public static VolumeCuller get() {
        return INSTANCE;
    }

    public static void tick(Minecraft minecraft) {
        INSTANCE.update(minecraft);
    }

    public boolean isCulled(BlockPos pos) {
        return !culled.isEmpty() && culled.contains(pos.asLong());
    }

    public int culledCount() {
        return culledCount;
    }

    public void forEachCulledBlock(Consumer<BlockPos> consumer) {
        if (culled.isEmpty()) {
            return;
        }
        var iterator = culled.iterator();
        while (iterator.hasNext()) {
            consumer.accept(BlockPos.of(iterator.nextLong()));
        }
    }

    /** 当前相机 → 目标连线的端点，供幽灵渲染计算透明度。 */
    public Vec3 cameraPos() {
        return cameraPos;
    }

    public Vec3 targetPos() {
        return targetPos;
    }

    public boolean isActive() {
        return !culled.isEmpty();
    }

    public void invalidate() {
        lastTick = Integer.MIN_VALUE;
    }

    public void reset() {
        previous.clear();
        next.clear();
        culled.clear();
        dirtySections.clear();
        culledCount = 0;
        lastTick = Integer.MIN_VALUE;
        lastValid = false;
        lastCamera = Vec3.ZERO;
        lastTarget = Vec3.ZERO;
        cameraPos = Vec3.ZERO;
        targetPos = Vec3.ZERO;
    }

    private void update(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null
                || !Config.CULLING_ENABLED.get() || !Config.CULLING_VOLUME.get()
                || !ClientPerspective.isTopDown()) {
            if (!culled.isEmpty()) {
                markAllDirty(minecraft);
                culled.clear();
                culledCount = 0;
            }
            lastValid = false;
            return;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Vec3 target = minecraft.player.getEyePosition(1.0F);
        cameraPos = cam;
        targetPos = target;

        int now = minecraft.player.tickCount;
        boolean moved = !lastValid
                || cam.distanceToSqr(lastCamera) > 0.02D
                || target.distanceToSqr(lastTarget) > 0.02D;
        if (lastValid && !moved && now - lastTick < 10) {
            flushDirty(minecraft);
            return;
        }
        lastTick = now;
        lastCamera = cam;
        lastTarget = target;
        lastValid = true;

        double inner = Config.CULLING_VOLUME_INNER_RADIUS.get();
        double outer = Config.CULLING_VOLUME_OUTER_RADIUS.get();
        double margin = outer + 1.0D;
        int minX = (int) Math.floor(Math.min(cam.x, target.x) - margin);
        int maxX = (int) Math.floor(Math.max(cam.x, target.x) + margin);
        int minY = (int) Math.floor(Math.min(cam.y, target.y) - margin);
        int maxY = (int) Math.floor(Math.max(cam.y, target.y) + margin);
        int minZ = (int) Math.floor(Math.min(cam.z, target.z) - margin);
        int maxZ = (int) Math.floor(Math.max(cam.z, target.z) + margin);
        int feetY = minecraft.player.blockPosition().getY();

        next.clear();
        double[] tBuffer = new double[1];
        boolean full = false;
        outerLoop:
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = Math.max(minY, feetY + 2); y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!minecraft.level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = minecraft.level.getBlockState(pos);
                    if (!CullingOccluders.isOccluder(minecraft.level, pos)) {
                        continue;
                    }
                    double d = SightlineVolume.perpendicularDistance(
                            x + 0.5D, y + 0.5D, z + 0.5D,
                            cam.x, cam.y, cam.z,
                            target.x, target.y, target.z,
                            tBuffer);
                    if (d > outer || SightlineVolume.envelope(tBuffer[0]) <= 0.02D) {
                        continue;
                    }
                    next.add(pos.asLong());
                    if (next.size() >= MAX_BLOCKS) {
                        full = true;
                        break outerLoop;
                    }
                }
            }
        }
        if (full) {
            // 超上限时保留上一帧结果，下一次移动再尝试，避免半成品闪烁。
            flushDirty(minecraft);
            return;
        }

        previous.clear();
        previous.addAll(culled);
        culled.clear();
        culled.addAll(next);
        culledCount = culled.size();
        // 新增与移除的方块都需要重建所在 section 及邻居（相邻面随之消失/恢复）。
        for (long packed : culled) {
            if (!previous.contains(packed)) {
                markDirtyAround(packed);
            }
        }
        for (long packed : previous) {
            if (!culled.contains(packed)) {
                markDirtyAround(packed);
            }
        }
        flushDirty(minecraft);
    }

    private void markAllDirty(Minecraft minecraft) {
        for (long packed : culled) {
            markDirtyAround(packed);
        }
        flushDirty(minecraft);
    }

    private void markDirtyAround(long packed) {
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        int sx = x >> 4;
        int sy = y >> 4;
        int sz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    dirtySections.add(SectionPos.asLong(sx + dx, sy + dy, sz + dz));
                }
            }
        }
    }

    private void flushDirty(Minecraft minecraft) {
        if (dirtySections.isEmpty() || minecraft.level == null) {
            return;
        }
        LevelRenderer levelRenderer = minecraft.levelRenderer;
        int budget = 0;
        var iterator = dirtySections.iterator();
        while (iterator.hasNext() && budget < MAX_DIRTY_SECTIONS) {
            long section = iterator.nextLong();
            levelRenderer.setSectionDirty(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            iterator.remove();
            budget++;
        }
    }
}
