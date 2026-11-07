package org.yanbwe.modularperspective.client.cull;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * v0.1 section 级近似剔除（docs/design.md §11.2–§11.4）。
 *
 * <p>做的是旧模组 {@code SightlineScanner} 的粗糙版本：从相机向玩家 hitbox 采样点连线，
 * 沿每条线做体素步进，把挡在线上的实心方块所属的 <b>section</b> 标记为隐藏；渲染期由
 * {@code LevelRendererMixin} 过滤 {@code visibleSections}，整段跳过。玩家所在 section 永远
 * 保留，未加载的 section 不参与。
 *
 * <p>与旧模组的差别（v0.1 已知近似）：
 * <ul>
 *   <li>旧模组按连通形状做方块级 mesh 剔除并生成幽灵轮廓；这里直接隐藏整个 16³ section，
 *       会出现空洞/漏光，属于设计 §11.2 明确接受的 v0.1 近似。</li>
 *   <li>没有独立的 RoomScanner；房间语义由“玩家 section 保留 + 视线上的遮挡 section 移除”
 *       近似表达。</li>
 * </ul>
 */
public final class SectionCuller {
    /** 每 0.2 格体素采样一次，足以避免穿墙漏检。 */
    private static final double RAY_STEP = 0.2D;
    /** 命中实心方块后，额外把其 section 相邻的 1 圈 section 一起隐藏（覆盖薄墙/地面边缘）。 */
    private static final int EXPAND_RADIUS_SECTIONS = 0;
    /** 每次最多收集多少条射线（8 个 hitbox 角点 + 中心）。 */
    private static final int MAX_RAYS = 9;
    /** 最多隐藏多少个 section，防止实心世界里一帧隐藏过多导致画面全空。 */
    private static final int MAX_HIDDEN_SECTIONS = 256;

    private static final LongOpenHashSet HIDDEN = new LongOpenHashSet();
    private static int hiddenCount;
    private static int raysCast;
    private static boolean culling;
    private static long lastRefreshTick = Long.MIN_VALUE;
    private static Vec3 lastCamera = Vec3.ZERO;
    private static long lastPlayerSection = Long.MIN_VALUE;
    /** Last full rescan cost in microseconds and its observed max (docs §16 M5: scan <= 2 ms/frame). */
    private static long lastScanMicros;
    private static long maxScanMicros;
    /** Last render-list filter cost in microseconds and its observed max (docs §16 M5: filter <= 0.2 ms/frame). */
    private static long lastFilterMicros;
    private static long maxFilterMicros;

    private SectionCuller() {
    }

    public static boolean isCulling() {
        return culling;
    }

    public static int hiddenCount() {
        return hiddenCount;
    }

    public static int raysCast() {
        return raysCast;
    }

    /** 渲染期查询：某个 section 是否应整段跳过。 */
    public static boolean isHidden(BlockPos sectionOrigin) {
        return culling && !HIDDEN.isEmpty() && HIDDEN.contains(SectionPos.asLong(sectionOrigin));
    }

    /** 配置/状态变化后强制下一次 tick 重扫。 */
    public static void invalidate() {
        lastRefreshTick = Long.MIN_VALUE;
    }

    /** 每客户端 tick 调用（在相机状态更新之后）。 */
    public static void tick(Minecraft minecraft) {
        Level level = minecraft.level;
        if (!Config.CULLING_ENABLED.get() || Config.CULLING_BLOCK_LEVEL.get()
                || level == null || minecraft.player == null || !ClientPerspective.isTopDown()) {
            if (culling || hiddenCount != 0 || raysCast != 0) {
                HIDDEN.clear();
                hiddenCount = 0;
                raysCast = 0;
                culling = false;
            }
            return;
        }

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        AABB box = minecraft.player.getBoundingBox();
        Vec3 center = box.getCenter();
        long gameTime = level.getGameTime();
        long playerKey = SectionPos.asLong(minecraft.player.blockPosition());

        boolean rescan = lastRefreshTick == Long.MIN_VALUE
                || gameTime - lastRefreshTick >= 2
                || camera.distanceToSqr(lastCamera) > 0.04D
                || playerKey != lastPlayerSection;
        if (!rescan) {
            return;
        }
        lastRefreshTick = gameTime;
        lastCamera = camera;
        lastPlayerSection = playerKey;
        long scanStart = System.nanoTime();

        SectionPos playerSection = SectionPos.of(minecraft.player.blockPosition());
        long playerSectionKey = playerSection.asLong();
        HIDDEN.clear();

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

        int rays = 0;
        for (Vec3 target : targets) {
            if (rays >= MAX_RAYS || HIDDEN.size() >= MAX_HIDDEN_SECTIONS) {
                break;
            }
            rays++;
            castRay(level, camera, target, playerSectionKey, HIDDEN);
        }

        expand(HIDDEN, playerSectionKey);
        hiddenCount = HIDDEN.size();
        raysCast = rays;
        culling = true;
        lastScanMicros = (System.nanoTime() - scanStart) / 1_000L;
        if (lastScanMicros > maxScanMicros) {
            maxScanMicros = lastScanMicros;
        }
    }

    public static long lastScanMicros() {
        return lastScanMicros;
    }

    public static long maxScanMicros() {
        return maxScanMicros;
    }

    public static long lastFilterMicros() {
        return lastFilterMicros;
    }

    public static long maxFilterMicros() {
        return maxFilterMicros;
    }

    /** Reported by LevelRendererMixin for the per-frame visibleSections filter. */
    public static void recordFilterNanos(long nanos) {
        lastFilterMicros = nanos / 1_000L;
        if (lastFilterMicros > maxFilterMicros) {
            maxFilterMicros = lastFilterMicros;
        }
    }

    private static void castRay(Level level, Vec3 from, Vec3 to, long playerSectionKey, LongOpenHashSet hidden) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4D) {
            return;
        }
        Vec3 step = delta.scale(1.0D / length);
        BlockPos previous = null;
        for (double travelled = 0.0D; travelled <= length; travelled += RAY_STEP) {
            Vec3 sample = from.add(step.scale(travelled));
            BlockPos blockPos = BlockPos.containing(sample);
            if (blockPos.equals(previous)) {
                continue;
            }
            previous = blockPos;
            long sectionKey = SectionPos.asLong(blockPos);
            if (sectionKey == playerSectionKey) {
                continue;
            }
            BlockState state = level.getBlockState(blockPos);
            if (!state.isSolidRender(level, blockPos)) {
                continue;
            }
            hidden.add(sectionKey);
            if (hidden.size() >= MAX_HIDDEN_SECTIONS) {
                return;
            }
        }
    }

    /** 命中方块所属 section 的相邻扩展（当前 {@link #EXPAND_RADIUS_SECTIONS}=0，即不扩展）。 */
    private static void expand(LongOpenHashSet hidden, long playerSectionKey) {
        if (EXPAND_RADIUS_SECTIONS <= 0 || hidden.isEmpty()) {
            return;
        }
        LongOpenHashSet copy = new LongOpenHashSet(hidden);
        for (long key : copy) {
            SectionPos pos = SectionPos.of(key);
            for (int dx = -EXPAND_RADIUS_SECTIONS; dx <= EXPAND_RADIUS_SECTIONS; dx++) {
                for (int dy = -EXPAND_RADIUS_SECTIONS; dy <= EXPAND_RADIUS_SECTIONS; dy++) {
                    for (int dz = -EXPAND_RADIUS_SECTIONS; dz <= EXPAND_RADIUS_SECTIONS; dz++) {
                        long expanded = SectionPos.asLong(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                        if (expanded != playerSectionKey) {
                            hidden.add(expanded);
                        }
                    }
                }
            }
        }
    }
}
