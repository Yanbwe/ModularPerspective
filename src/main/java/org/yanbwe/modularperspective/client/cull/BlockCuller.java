package org.yanbwe.modularperspective.client.cull;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * v0.2 方块级剔除（docs/design.md §11.2、§16 M6）。
 *
 * <p>v0.1 的 {@link SectionCuller} 只能整段隐藏 16³ section，墙体和地面会成片消失；这里改为
 * “只把相机→玩家视线上的实心方块从区块 mesh 里剔除”：沿每条视线按小立方体采样，
 * 把命中的实心方块位置记入 {@link #CULLED}，区块构建时由
 * {@code RenderChunkRegionMixin} 把这些方块返回成空气，再从主线程把受影响的 section
 * 标记为需要重建（{@link LevelRenderer#setSectionDirty(int, int, int)}）。
 *
 * <p>只影响客户端渲染，不改世界数据、不改碰撞/挖掘。相机-玩家视线变化时按 tick 重算，
 * 且每次重算只把“状态真正变化的方块所在 section”标脏，静止时不会反复重建。
 */
public final class BlockCuller {
    /** 沿视线采样的步长（格）。 */
    private static final double RAY_STEP = 0.25D;
    /** 视线采样点周围剔除的半径（格）：1 表示 3×3×3 的隧道截面。 */
    private static final int MAX_SHAPE_BLOCKS = 1024;
    private static final double MAX_FLOOD_DISTANCE = 48.0D;
    /** 每条视线最多采样多少步，防止极端距离下过慢。 */
    private static final int MAX_STEPS_PER_RAY = 64;
    /** 最多同时剔除多少方块，防止实心世界里整屏挖空。 */
    private static final int MAX_CULLED_BLOCKS = 8192;
    /** 每次重算最多把多少个 section 标脏，避免一帧触发大量重建。 */
    private static final int MAX_DIRTY_PER_TICK = 16;

    private static final LongOpenHashSet CULLED = new LongOpenHashSet();
    private static final LongOpenHashSet NEXT_CULLED = new LongOpenHashSet();
    private static final LongOpenHashSet DIRTY_SECTIONS = new LongOpenHashSet();

    private static int culledCount;
    private static int raysCast;
    private static boolean culling;
    private static long lastRefreshTick = Long.MIN_VALUE;
    private static Vec3 lastCamera = Vec3.ZERO;
    private static Vec3 lastPlayerPos = Vec3.ZERO;
    private static long lastPlayerSection = Long.MIN_VALUE;
    private static int dirtyPending;

    private BlockCuller() {
    }

    public static boolean isCulling() {
        return culling;
    }

    public static int culledCount() {
        return culledCount;
    }

    public static int raysCast() {
        return raysCast;
    }

    public static int dirtyPending() {
        return dirtyPending;
    }

    /** 渲染期查询：某个方块是否应从区块 mesh 中剔除。 */
    public static boolean isCulled(BlockPos blockPos) {
        return culling && !CULLED.isEmpty() && CULLED.contains(blockPos.asLong());
    }

    /** 只读视图：当前被剔除的方块（LongSet 元素是 BlockPos.asLong）。 */
    public static it.unimi.dsi.fastutil.longs.LongSet culledBlocks() {
        return it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(CULLED);
    }

    /** 配置/状态变化后强制下一次 tick 重算。 */
    public static void invalidate() {
        lastRefreshTick = Long.MIN_VALUE;
    }

    /** 每客户端 tick 调用（在相机状态更新之后）。 */
    public static void tick(Minecraft minecraft) {
        Level level = minecraft.level;
        boolean enabled = Config.CULLING_ENABLED.get() && Config.CULLING_BLOCK_LEVEL.get();
        if (!enabled || level == null || minecraft.player == null || !ClientPerspective.isTopDown()) {
            disable(level);
            return;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 playerPos = minecraft.player.position();
        long gameTime = level.getGameTime();
        long playerSection = SectionPos.asLong(minecraft.player.blockPosition());

        boolean rescan = lastRefreshTick == Long.MIN_VALUE
                || gameTime - lastRefreshTick >= 2
                || camera.distanceToSqr(lastCamera) > 0.04D
                || playerPos.distanceToSqr(lastPlayerPos) > 0.25D
                || playerSection != lastPlayerSection;
        if (!rescan) {
            flushDirty(level);
            return;
        }
        lastRefreshTick = gameTime;
        lastCamera = camera;
        lastPlayerPos = playerPos;
        lastPlayerSection = playerSection;

        NEXT_CULLED.clear();
        int rays = 0;
        int minY = minecraft.player.blockPosition().getY();
        AABB box = minecraft.player.getBoundingBox();
        Vec3 center = box.getCenter();
        Vec3[] targets = new Vec3[] {
                new Vec3(box.minX, box.minY, box.minZ),
                new Vec3(box.minX, box.minY, box.maxZ),
                new Vec3(box.minX, box.maxY, box.minZ),
                new Vec3(box.minX, box.maxY, box.maxZ),
                new Vec3(box.maxX, box.minY, box.minZ),
                new Vec3(box.maxX, box.minY, box.maxZ),
                new Vec3(box.maxX, box.maxY, box.minZ),
                new Vec3(box.maxX, box.maxY, box.maxZ),
                center
        };
        for (Vec3 target : targets) {
            if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                break;
            }
            rays++;
            carveRay(level, camera, target, minY, playerSection);
        }

        // 只把“状态变化”的方块所在 section 标脏：静止时不会反复重建。
        markChangedSections();
        CULLED.clear();
        CULLED.addAll(NEXT_CULLED);
        culledCount = CULLED.size();
        raysCast = rays;
        culling = true;
        flushDirty(level);
    }

    private static void disable(Level level) {
        if (!culling && CULLED.isEmpty()) {
            return;
        }
        for (long block : CULLED) {
            DIRTY_SECTIONS.add(SectionPos.asLong(BlockPos.of(block)));
        }
        CULLED.clear();
        culledCount = 0;
        raysCast = 0;
        culling = false;
        if (level != null) {
            flushDirty(level);
        } else {
            DIRTY_SECTIONS.clear();
            dirtyPending = 0;
        }
    }

    /** 从相机向目标点流式采样；命中实心方块时按“连通形状”整体剔除（v0.2 连通形状首版）。 */
    private static void carveRay(Level level, Vec3 from, Vec3 to, int minY, long playerSection) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4D) {
            return;
        }
        Vec3 step = delta.scale(RAY_STEP / length);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos lastCenter = null;
        LongOpenHashSet visited = new LongOpenHashSet();
        for (int i = 0; i <= MAX_STEPS_PER_RAY && i * RAY_STEP <= length; i++) {
            Vec3 sample = from.add(step.scale(i));
            cursor.set(sample.x, sample.y, sample.z);
            BlockPos center = cursor.immutable();
            if (center.equals(lastCenter)) {
                continue;
            }
            lastCenter = center;
            long key = center.asLong();
            if (NEXT_CULLED.contains(key) || !visited.add(key)) {
                continue;
            }
            if (center.getY() < minY || SectionPos.asLong(center) == playerSection) {
                continue;
            }
            BlockState state = level.getBlockState(center);
            if (!state.isSolidRender(level, center)) {
                continue;
            }
            floodCarveShape(level, center, minY, playerSection, visited);
            if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                return;
            }
        }
    }

    /**
     * 从首个命中方块出发，沿 6 邻接把连通的实心方块整体剔除（旧模组 SightlineMask 的“连通形状”近似）。
     * 限制单形状块数与相机距离，并跳过玩家脚下以下的方块，避免把地面/大块地形整片挖掉。
     */
    private static void floodCarveShape(Level level, BlockPos start, int minY, long playerSection, LongOpenHashSet visited) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        int added = 0;
        double maxDistanceSq = MAX_FLOOD_DISTANCE * MAX_FLOOD_DISTANCE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && added < MAX_SHAPE_BLOCKS && NEXT_CULLED.size() < MAX_CULLED_BLOCKS) {
            BlockPos pos = queue.poll();
            if (pos.getY() < minY || SectionPos.asLong(pos) == playerSection) {
                continue;
            }
            long key = pos.asLong();
            if (!NEXT_CULLED.contains(key)) {
                NEXT_CULLED.add(key);
                added++;
            }
            for (int axis = 0; axis < 3; axis++) {
                for (int direction = -1; direction <= 1; direction += 2) {
                    cursor.set(pos.getX(), pos.getY(), pos.getZ());
                    if (axis == 0) {
                        cursor.setX(pos.getX() + direction);
                    } else if (axis == 1) {
                        cursor.setY(pos.getY() + direction);
                    } else {
                        cursor.setZ(pos.getZ() + direction);
                    }
                    BlockPos neighbor = cursor.immutable();
                    long neighborKey = neighbor.asLong();
                    if (!visited.add(neighborKey)) {
                        continue;
                    }
                    if (neighbor.getY() < minY || SectionPos.asLong(neighbor) == playerSection) {
                        continue;
                    }
                    double dx = neighbor.getX() + 0.5D - lastCamera.x;
                    double dy = neighbor.getY() + 0.5D - lastCamera.y;
                    double dz = neighbor.getZ() + 0.5D - lastCamera.z;
                    if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                        continue;
                    }
                    BlockState state = level.getBlockState(neighbor);
                    if (state.isSolidRender(level, neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
    }

    /** 对比 CULLED 与 NEXT_CULLED，把状态变化的方块所在 section 加入待重建队列。 */
    private static void markChangedSections() {
        for (LongIterator it = CULLED.iterator(); it.hasNext(); ) {
            long block = it.nextLong();
            if (!NEXT_CULLED.contains(block)) {
                DIRTY_SECTIONS.add(SectionPos.asLong(BlockPos.of(block)));
            }
        }
        for (LongIterator it = NEXT_CULLED.iterator(); it.hasNext(); ) {
            long block = it.nextLong();
            if (!CULLED.contains(block)) {
                DIRTY_SECTIONS.add(SectionPos.asLong(BlockPos.of(block)));
            }
        }
    }

    /** 按预算把待重建 section 标脏（客户端主线程；vanilla 会在之后异步重算 mesh）。 */
    private static void flushDirty(Level level) {
        if (DIRTY_SECTIONS.isEmpty()) {
            dirtyPending = 0;
            return;
        }
        LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
        int budget = MAX_DIRTY_PER_TICK;
        LongIterator it = DIRTY_SECTIONS.iterator();
        while (it.hasNext() && budget-- > 0) {
            long section = it.nextLong();
            it.remove();
            levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
        dirtyPending = DIRTY_SECTIONS.size();
    }
}
