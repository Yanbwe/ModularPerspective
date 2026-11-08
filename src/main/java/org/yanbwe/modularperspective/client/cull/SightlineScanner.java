package org.yanbwe.modularperspective.client.cull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * Sightline (silhouette) culling tier, v0.3 parity of the prototype mod's
 * {@code room/SightlineScanner} (docs/design.md §11.2/§11.3, §16 M6).
 *
 * <p>Where the section/block/cone tiers remove everything between the camera and the player, this
 * tier works per <em>shape</em>: the 27 rays from the camera to the player hitbox are cast through
 * the world; every connected solid shape they touch is scored by the fraction of rays it blocks.
 *
 * <ul>
 *   <li>a shape covering a large part of the silhouette (a wall directly in front of the player)
 *       is removed as a whole — the cheap geometry tiers already remove it anyway;</li>
 *   <li>a shape covering a small part (trim, posts, a bit of roof) is <em>protected</em> so the
 *       other tiers carve around it instead of punching holes through furniture;</li>
 *   <li>a shape that cannot be bounded (terrain, huge connected rock) falls back to the silhouette
 *       of the hit voxels grown by one block, which is what the prototype calls
 *       {@code buildSilhouette}.</li>
 * </ul>
 *
 * <p>Scanning runs on the client thread with a hard time budget (1.5 ms) because it is tiny: at most
 * 27 rays × 256 steps plus a few thousand flood visits. Anything over the budget leaves the previous
 * result in place and retries on the next rescan, exactly like {@link RoomScanner}.
 */
public final class SightlineScanner {
    private static final SightlineScanner INSTANCE = new SightlineScanner();

    /** rays per axis of the camera→player hitbox bundle (27 rays total). */
    private static final int SAMPLES_PER_AXIS = 3;
    private static final double HITBOX_INSET = 0.05D;
    private static final double TARGET_BACKOFF = 0.3D;
    private static final double RAY_STEP = 0.25D;
    private static final int MAX_RAY_STEPS = 256;

    private static final int MAX_SHAPES = 96;
    private static final int MAX_SHAPE_BLOCKS = 512;
    private static final int MAX_SHAPE_SPAN = 24;
    private static final int MAX_SHAPE_HEIGHT = 48;
    private static final int MAX_CULLED_BLOCKS = 8192;
    private static final int MAX_PROTECTED_BLOCKS = 8192;
    private static final int MAX_HIT_BLOCKS = 1024;
    private static final int MAX_DIRTY_PER_TICK = 32;
    private static final long RESCAN_INTERVAL_TICKS = 4L;
    private static final double RESCAN_CAMERA_MOVE_SQ = 0.04D;
    private static final double RESCAN_PLAYER_MOVE_SQ = 0.25D;
    private static final int RAY_MISS = 0;
    private static final int RAY_HIT = 1;
    private static final int RAY_BUDGET = 2;
    private static final int MAX_SILHOUETTE_BLOCKS = 256;
    private static final long SCAN_BUDGET_NANOS = 1_500_000L;

    /** 26-neighbour offsets, used for tree/canopy shapes. */
    private static final int[] DIAGONAL_DX = new int[26];
    private static final int[] DIAGONAL_DY = new int[26];
    private static final int[] DIAGONAL_DZ = new int[26];

    static {
        int index = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    DIAGONAL_DX[index] = dx;
                    DIAGONAL_DY[index] = dy;
                    DIAGONAL_DZ[index] = dz;
                    index++;
                }
            }
        }
    }

    private final LongOpenHashSet culled = new LongOpenHashSet();
    private final LongOpenHashSet protectedBlocks = new LongOpenHashSet();
    private final LongOpenHashSet dirtySections = new LongOpenHashSet();

    private boolean active;
    private int shapeCount;
    private int culledCount;
    private int protectedCount;
    private int incompleteShapes;
    private int arrivedRays;
    private long lastScanMicros;
    private long maxScanMicros;
    private long lastTick = Long.MIN_VALUE;
    private Vec3 lastCamera = Vec3.ZERO;
    private Vec3 lastPlayer = Vec3.ZERO;
    private String lastResult = "ok";

    private SightlineScanner() {
    }

    public static SightlineScanner get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ read side

    public boolean isCulled(BlockPos pos) {
        return active && !culled.isEmpty() && culled.contains(pos.asLong());
    }

    public boolean isProtected(BlockPos pos) {
        return active && !protectedBlocks.isEmpty() && protectedBlocks.contains(pos.asLong());
    }

    public boolean isCulling() {
        return active;
    }

    public int shapeCount() {
        return shapeCount;
    }

    public int culledCount() {
        return culledCount;
    }

    public int protectedCount() {
        return protectedCount;
    }

    public int incompleteShapes() {
        return incompleteShapes;
    }

    public int arrivedRays() {
        return arrivedRays;
    }

    public long lastScanMicros() {
        return lastScanMicros;
    }

    public long maxScanMicros() {
        return maxScanMicros;
    }

    public String lastResult() {
        return lastResult;
    }

    /** Iterates every block currently culled by this tier (used by the ghost renderer). */
    public void forEachCulledBlock(java.util.function.Consumer<BlockPos> consumer) {
        if (!active) {
            return;
        }
        for (long block : culled) {
            consumer.accept(BlockPos.of(block));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void invalidate() {
        lastTick = Long.MIN_VALUE;
        lastResult = "invalidated";
    }

    public void reset() {
        culled.clear();
        protectedBlocks.clear();
        dirtySections.clear();
        active = false;
        shapeCount = 0;
        culledCount = 0;
        protectedCount = 0;
        incompleteShapes = 0;
        arrivedRays = 0;
        lastTick = Long.MIN_VALUE;
        lastResult = "reset";
    }

    public void tick(Minecraft minecraft) {
        Level level = minecraft.level;
        boolean enabled = Config.CULLING_ENABLED.get() && Config.CULLING_SIGHTLINE_ENABLED.get()
                && !Config.CULLING_VOLUME.get();
        if (!enabled || level == null || minecraft.player == null || !ClientPerspective.isTopDown()) {
            if (active || !culled.isEmpty() || !protectedBlocks.isEmpty()) {
                reset();
            }
            return;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        Vec3 player = minecraft.player.position();
        long gameTime = level.getGameTime();
        boolean rescan = lastTick == Long.MIN_VALUE
                || gameTime - lastTick >= RESCAN_INTERVAL_TICKS
                || camera.distanceToSqr(lastCamera) > RESCAN_CAMERA_MOVE_SQ
                || player.distanceToSqr(lastPlayer) > RESCAN_PLAYER_MOVE_SQ;
        if (rescan) {
            lastTick = gameTime;
            lastCamera = camera;
            lastPlayer = player;
            scan(level, minecraft.player, camera);
        }
        flushDirty(level);
    }

    // ------------------------------------------------------------------ scanning

    private void scan(Level level, net.minecraft.world.entity.player.Player player, Vec3 camera) {
        long startNanos = System.nanoTime();
        AABB box = player.getBoundingBox();
        Long2IntOpenHashMap hitMasks = new Long2IntOpenHashMap();
        hitMasks.defaultReturnValue(0);

        int totalRays = SAMPLES_PER_AXIS * SAMPLES_PER_AXIS * SAMPLES_PER_AXIS;
        int arrived = 0;
        int rayIndex = 0;
        boolean budgetExceeded = false;
        outer:
        for (int ix = 0; ix < SAMPLES_PER_AXIS; ix++) {
            for (int iy = 0; iy < SAMPLES_PER_AXIS; iy++) {
                for (int iz = 0; iz < SAMPLES_PER_AXIS; iz++) {
                    if (System.nanoTime() - startNanos > SCAN_BUDGET_NANOS) {
                        budgetExceeded = true;
                        break outer;
                    }
                    double fx = (ix + 0.5D) / SAMPLES_PER_AXIS;
                    double fy = (iy + 0.5D) / SAMPLES_PER_AXIS;
                    double fz = (iz + 0.5D) / SAMPLES_PER_AXIS;
                    double x = Mth.lerp(fx, box.minX + HITBOX_INSET, box.maxX - HITBOX_INSET);
                    double y = Mth.lerp(fy, box.minY + HITBOX_INSET, box.maxY - HITBOX_INSET);
                    double z = Mth.lerp(fz, box.minZ + HITBOX_INSET, box.maxZ - HITBOX_INSET);
                    Vec3 target = new Vec3(x, y, z);
                    Vec3 toCamera = target.subtract(camera);
                    double length = toCamera.length();
                    if (length < 1.0E-3D) {
                        rayIndex++;
                        arrived++;
                        continue;
                    }
                    Vec3 direction = toCamera.scale(1.0D / length);
                    double endLength = Math.max(0.1D, length - TARGET_BACKOFF);
                    Vec3 end = camera.add(direction.scale(endLength));
                    int rayResult = castRay(level, camera, end, rayIndex, hitMasks, startNanos);
                    if (rayResult == RAY_BUDGET) {
                        // budget inside the ray: keep the old result, retry next time
                        lastResult = "budget";
                        return;
                    }
                    if (rayResult == RAY_MISS) {
                        arrived++;
                    }
                    rayIndex++;
                    if (hitMasks.size() > MAX_HIT_BLOCKS) {
                        break outer;
                    }
                }
            }
        }
        if (budgetExceeded) {
            lastResult = "budget";
            return;
        }
        arrivedRays = arrived;

        buildShapes(level, hitMasks, totalRays, startNanos);
        if ("budget".equals(lastResult)) {
            return;
        }

        long elapsed = System.nanoTime() - startNanos;
        lastScanMicros = elapsed / 1000L;
        maxScanMicros = Math.max(maxScanMicros, lastScanMicros);
        lastResult = "shapes=" + shapeCount + " culled=" + culledCount + " protected=" + protectedCount
                + " incomplete=" + incompleteShapes + " rays=" + arrivedRays + "/" + totalRays;
    }

    /** @return {@link #RAY_BUDGET} when the budget ran out inside the ray, otherwise {@link #RAY_HIT} or {@link #RAY_MISS}. */
    private int castRay(Level level, Vec3 from, Vec3 to, int rayIndex,
            Long2IntOpenHashMap hitMasks, long startNanos) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4D) {
            return RAY_MISS;
        }
        boolean hit = false;
        Vec3 direction = delta.scale(1.0D / length);
        int steps = Math.min(MAX_RAY_STEPS, (int) Math.ceil(length / RAY_STEP));
        long lastKey = Long.MIN_VALUE;
        for (int i = 0; i <= steps; i++) {
            double distance = Math.min(length, i * RAY_STEP);
            BlockPos pos = BlockPos.containing(
                    from.x + direction.x * distance,
                    from.y + direction.y * distance,
                    from.z + direction.z * distance);
            long key = pos.asLong();
            if (key == lastKey) {
                continue;
            }
            lastKey = key;
            if (!level.isLoaded(pos)) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isSolidRender(level, pos)) {
                hitMasks.merge(key, 1 << rayIndex, (first, second) -> first | second);
                hit = true;
            }
            if (startNanos != 0L && (i & 7) == 7 && System.nanoTime() - startNanos > SCAN_BUDGET_NANOS) {
                return RAY_BUDGET;
            }
        }
        return hit ? RAY_HIT : RAY_MISS;
    }

    private void buildShapes(Level level, Long2IntOpenHashMap hitMasks, int totalRays, long startNanos) {
        LongOpenHashSet visited = new LongOpenHashSet();
        LongOpenHashSet nextCulled = new LongOpenHashSet();
        LongOpenHashSet nextProtected = new LongOpenHashSet();
        int shapes = 0;
        int incomplete = 0;

        for (long seedLong : hitMasks.keySet()) {
            if (System.nanoTime() - startNanos > SCAN_BUDGET_NANOS) {
                lastResult = "budget";
                return;
            }
            if (visited.contains(seedLong) || shapes >= MAX_SHAPES) {
                continue;
            }
            BlockPos seed = BlockPos.of(seedLong);
            if (!level.isLoaded(seed)) {
                continue;
            }
            OccluderClass occluderClass = OccluderClass.classify(level.getBlockState(seed));
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            List<BlockPos> blocks = new ArrayList<>();
            visited.add(seedLong);
            queue.add(seed);

            int rayMask = 0;
            boolean bounded = true;
            int minX = seed.getX();
            int maxX = seed.getX();
            int minY = seed.getY();
            int maxY = seed.getY();
            int minZ = seed.getZ();
            int maxZ = seed.getZ();

            while (!queue.isEmpty()) {
                if (blocks.size() > MAX_SHAPE_BLOCKS) {
                    bounded = false;
                    break;
                }
                if (System.nanoTime() - startNanos > SCAN_BUDGET_NANOS) {
                    lastResult = "budget";
                    return;
                }
                BlockPos pos = queue.poll();
                blocks.add(pos);
                rayMask |= hitMasks.get(pos.asLong());
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY());
                maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
                if (maxX - minX > MAX_SHAPE_SPAN || maxY - minY > MAX_SHAPE_HEIGHT
                        || maxZ - minZ > MAX_SHAPE_SPAN) {
                    bounded = false;
                    break;
                }
                if (occluderClass.diagonalFlood()) {
                    for (int i = 0; i < DIAGONAL_DX.length; i++) {
                        BlockPos neighbour = new BlockPos(pos.getX() + DIAGONAL_DX[i],
                                pos.getY() + DIAGONAL_DY[i], pos.getZ() + DIAGONAL_DZ[i]);
                        enqueueSameClass(level, neighbour, occluderClass, visited, queue);
                    }
                } else {
                    for (Direction direction : OccluderClass.FACE_NEIGHBOURS) {
                        enqueueSameClass(level, pos.relative(direction), occluderClass, visited, queue);
                    }
                }
            }

            shapes++;
            if (!bounded) {
                incomplete++;
                // Terrain-style fallback (prototype buildSilhouette): only the voxels the rays
                // actually hit plus their solid same-class face-neighbours (one-block dilation),
                // capped so a mountainside cannot flood the whole world.
                for (long hitLong : hitMasks.keySet()) {
                    if (nextCulled.size() >= MAX_SILHOUETTE_BLOCKS) {
                        break;
                    }
                    nextCulled.add(hitLong);
                    BlockPos hitPos = BlockPos.of(hitLong);
                    if (!level.isLoaded(hitPos)) {
                        continue;
                    }
                    OccluderClass hitClass = OccluderClass.classify(level.getBlockState(hitPos));
                    for (Direction direction : OccluderClass.FACE_NEIGHBOURS) {
                        if (nextCulled.size() >= MAX_SILHOUETTE_BLOCKS) {
                            break;
                        }
                        BlockPos neighbour = hitPos.relative(direction);
                        if (!level.isLoaded(neighbour)) {
                            continue;
                        }
                        BlockState neighbourState = level.getBlockState(neighbour);
                        if (neighbourState.isSolidRender(level, neighbour)
                                && OccluderClass.classify(neighbourState) == hitClass) {
                            nextCulled.add(neighbour.asLong());
                        }
                    }
                }
                continue;
            }

            double score = (double) Integer.bitCount(rayMask) / (double) totalRays;
            boolean cull = score >= Config.CULLING_SIGHTLINE_OCCLUDE_THRESHOLD.get();
            LongOpenHashSet target = cull ? nextCulled : nextProtected;
            for (BlockPos pos : blocks) {
                if (target.size() >= (cull ? MAX_CULLED_BLOCKS : MAX_PROTECTED_BLOCKS)) {
                    break;
                }
                target.add(pos.asLong());
            }
        }
        applyResult(nextCulled, nextProtected, shapes, incomplete);
    }

    private void enqueueSameClass(Level level, BlockPos neighbour, OccluderClass occluderClass,
            LongOpenHashSet visited, ArrayDeque<BlockPos> queue) {
        if (!visited.add(neighbour.asLong())) {
            return;
        }
        if (!level.isLoaded(neighbour)) {
            return;
        }
        BlockState state = level.getBlockState(neighbour);
        if (!state.isSolidRender(level, neighbour) || OccluderClass.classify(state) != occluderClass) {
            return;
        }
        queue.add(neighbour);
    }

    private void applyResult(LongOpenHashSet nextCulled, LongOpenHashSet nextProtected,
            int shapes, int incomplete) {
        for (long block : culled) {
            if (!nextCulled.contains(block)) {
                markDirtyAround(block);
            }
        }
        for (long block : nextCulled) {
            if (!culled.contains(block)) {
                markDirtyAround(block);
            }
        }
        culled.clear();
        culled.addAll(nextCulled);
        protectedBlocks.clear();
        protectedBlocks.addAll(nextProtected);
        active = true;
        shapeCount = shapes;
        culledCount = culled.size();
        protectedCount = protectedBlocks.size();
        incompleteShapes = incomplete;
    }

    private static long sectionKey(long blockLong) {
        BlockPos pos = BlockPos.of(blockLong);
        return SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
    }

    private void markDirtyAround(long blockLong) {
        dirtySections.add(sectionKey(blockLong));
        BlockPos pos = BlockPos.of(blockLong);
        for (Direction direction : Direction.values()) {
            dirtySections.add(sectionKey(pos.relative(direction).asLong()));
        }
    }

    private void flushDirty(Level level) {
        if (dirtySections.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer == null) {
            dirtySections.clear();
            return;
        }
        int budget = MAX_DIRTY_PER_TICK;
        var iterator = dirtySections.iterator();
        while (iterator.hasNext() && budget-- > 0) {
            long section = iterator.nextLong();
            minecraft.levelRenderer.setSectionDirty(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
            iterator.remove();
        }
    }
}
