package org.yanbwe.modularperspective.client.cull;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
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
    private static final int CARVE_RADIUS = 1;
    private static final double MAX_FLOOD_DISTANCE = 48.0D;
    /** 每条视线最多采样多少步，防止极端距离下过慢。 */
    private static final int MAX_STEPS_PER_RAY = 64;
    /** 最多同时剔除多少方块，防止实心世界里整屏挖空。 */
    private static final int MAX_CULLED_BLOCKS = 8192;
    /** 每次重算最多把多少个 section 标脏，避免一帧触发大量重建。 */
    private static final int MAX_DIRTY_PER_TICK = 16;
    /** v0.3 SHAPE tier 首片：每轮最多剔除多少方块（防止新 tier 把整屏挖空）。 */
    private static final int MAX_CONE_BLOCKS = 4096;

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
    /** 最近一轮 SHAPE tier 剔除的方块数（调试 HUD/自动验收用）。 */
    private static int shapeBlocks;

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

    /** 最近一轮 SHAPE tier 剔除的方块数。 */
    public static int shapeBlocks() {
        return shapeBlocks;
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
        boolean master = Config.CULLING_ENABLED.get();
        boolean rayEnabled = master && Config.CULLING_BLOCK_LEVEL.get();
        boolean shapeEnabled = master && Config.CULLING_SHAPE_ENABLED.get() && !RoomScanner.get().isIndoor();
        if ((!rayEnabled && !shapeEnabled) || level == null || minecraft.player == null
                || !ClientPerspective.isTopDown()) {
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
        int shape = 0;
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
        if (rayEnabled) {
            for (Vec3 target : targets) {
                if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                    break;
                }
                rays++;
                carveRay(level, camera, target, minY, RoomScanner.get().isIndoor() ? Long.MIN_VALUE : playerSection);
            }
        }
        if (shapeEnabled) {
            shape = carveCone(level, playerPos, camera, RoomScanner.get().isIndoor(), minY);
        }

        // 只把“状态变化”的方块所在 section 标脏：静止时不会反复重建。
        markChangedSections();
        CULLED.clear();
        CULLED.addAll(NEXT_CULLED);
        culledCount = CULLED.size();
        raysCast = rays;
        shapeBlocks = shape;
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
        shapeBlocks = 0;
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
            if (center.getY() < minY || SectionPos.asLong(center) == playerSection || RoomScanner.get().test(center) == RoomScanner.get().KEEP) {
                continue;
            }
            BlockState state = level.getBlockState(center);
            if (!state.isSolidRender(level, center)) {
                continue;
            }
            if (!floodCarveShape(level, center, minY, playerSection, visited, RoomScanner.get().isIndoor())) {
                carveTunnel(level, center);
            }
            if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                return;
            }
        }
    }

    /**
     * 从首个命中方块出发，沿 6 邻接 BFS（旧模组 SightlineMask 的“连通形状”近似）。
     * 室外（{@code shellOnly == false}）：把连通的实心方块整体剔除，超过 {@link #MAX_SHAPE_BLOCKS} 块时返回 false，
     * 调用方退回只挖视线隧道，避免把山体/屋顶这类超大连通地形挖掉一块。
     * 室内（{@code shellOnly == true}）：只把“与房间空气相邻”的外壳方块计入剔除集，避免把与房间相连的整座山体一起挖掉；
     * BFS 访问上限 {@code MAX_SHAPE_BLOCKS * 2}，超限同样返回 false 退回隧道。
     * 另外跳过玩家脚下以下的方块，避免挖穿地面。
     */
    private static boolean floodCarveShape(Level level, BlockPos start, int minY, long playerSection, LongOpenHashSet visited, boolean shellOnly) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        java.util.List<BlockPos> collected = new java.util.ArrayList<>();
        if (!shellOnly || RoomScanner.get().isShell(start)) {
            collected.add(start);
        }
        queue.add(start);
        double maxDistanceSq = MAX_FLOOD_DISTANCE * MAX_FLOOD_DISTANCE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int visitedCap = shellOnly ? MAX_SHAPE_BLOCKS * 2 : Integer.MAX_VALUE;
        int visitedCount = 0;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (++visitedCount > visitedCap) {
                return false;
            }
            if (pos.getY() < minY || SectionPos.asLong(pos) == playerSection || RoomScanner.get().test(pos) == RoomScanner.get().KEEP) {
                continue;
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
                    if (neighbor.getY() < minY || SectionPos.asLong(neighbor) == playerSection || RoomScanner.get().test(neighbor) == RoomScanner.get().KEEP) {
                        continue;
                    }
                    double dx = neighbor.getX() + 0.5D - lastCamera.x;
                    double dy = neighbor.getY() + 0.5D - lastCamera.y;
                    double dz = neighbor.getZ() + 0.5D - lastCamera.z;
                    if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                        continue;
                    }
                    BlockState state = level.getBlockState(neighbor);
                    if (!state.isSolidRender(level, neighbor)) {
                        continue;
                    }
                    boolean include = !shellOnly || RoomScanner.get().isShell(neighbor);
                    if (include) {
                        collected.add(neighbor);
                        if (collected.size() > MAX_SHAPE_BLOCKS) {
                            return false;
                        }
                    }
                    queue.add(neighbor);
                }
            }
        }
        for (BlockPos pos : collected) {
            if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                break;
            }
            NEXT_CULLED.add(pos.asLong());
        }
        return true;
    }

    /** 连通形状过大时的退路：只挖命中点周围 (2*CARVE_RADIUS+1)³ 的实心方块。 */
    private static void carveTunnel(Level level, BlockPos center) {
        for (int dx = -CARVE_RADIUS; dx <= CARVE_RADIUS; dx++) {
            for (int dy = -CARVE_RADIUS; dy <= CARVE_RADIUS; dy++) {
                for (int dz = -CARVE_RADIUS; dz <= CARVE_RADIUS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    long key = pos.asLong();
                    if (NEXT_CULLED.contains(key)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (!state.isSolidRender(level, pos)) {
                        continue;
                    }
                    NEXT_CULLED.add(key);
                    if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS) {
                        return;
                    }
                }
            }
        }
    }
    /**
     * v0.3 SHAPE tier 首片（旧模组 {@code GenericCuller3} 的锥/柱近似，docs/design.md §11.2）：
     * 遍历玩家脚部与相机构成的包围盒，把落在“相机→玩家轴线”锥/柱范围内的实心方块加入剔除集。
     * 圆柱半径、锥形半角来自 {@code culling.shapeRadius/shapeConeHalfAngle}；室内由房间扫描负责，不启用本 tier。
     *
     * @return 本轮新剔除的方块数
     */
    private static int carveCone(Level level, Vec3 feet, Vec3 camera, boolean roomIndoor, int minY) {
        double radius = Config.CULLING_SHAPE_RADIUS.get();
        double coneHalfAngle = Config.CULLING_SHAPE_CONE_HALF_ANGLE.get();
        int minX = Mth.floor(Math.min(feet.x, camera.x) - radius - 1.0D);
        int maxX = Mth.floor(Math.max(feet.x, camera.x) + radius + 1.0D);
        int minBound = Mth.floor(Math.min(feet.y, camera.y) - radius - 1.0D);
        int maxY = Mth.floor(Math.max(feet.y, camera.y) + radius + 1.0D);
        int minZ = Mth.floor(Math.min(feet.z, camera.z) - radius - 1.0D);
        int maxZ = Mth.floor(Math.max(feet.z, camera.z) + radius + 1.0D);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int carved = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = Math.max(minBound, level.getMinBuildHeight()); y <= maxY; y++) {
                    if (NEXT_CULLED.size() >= MAX_CULLED_BLOCKS || carved >= MAX_CONE_BLOCKS) {
                        return carved;
                    }
                    if (y < minY) {
                        continue;
                    }
                    if (!ShapeCuller.shouldCull(x + 0.5D, y + 0.5D, z + 0.5D, feet.x, feet.y, feet.z,
                            camera.x, camera.y, camera.z, radius, coneHalfAngle)) {
                        continue;
                    }
                    probe.set(x, y, z);
                    BlockState state = level.getBlockState(probe);
                    if (!state.isSolidRender(level, probe) || isIgnoredType(state)) {
                        continue;
                    }
                    if (!roomIndoor && RoomScanner.get().test(new BlockPos(x, y, z)) == RoomScanner.KEEP) {
                        continue;
                    }
                    if (NEXT_CULLED.add(probe.asLong())) {
                        carved++;
                    }
                }
            }
        }
        return carved;
    }

    /** 门/梯子/墙上装饰/刷怪箱/宝库不参与形状剔除（旧模组 GenericCuller3 的 isIgnoredType），避免挖出怪洞。 */
    private static boolean isIgnoredType(BlockState state) {
        return state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof LadderBlock
                || state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock
                || state.getBlock() instanceof VaultBlock
                || state.getBlock() instanceof SpawnerBlock
                || state.getBlock() instanceof TrialSpawnerBlock;
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
