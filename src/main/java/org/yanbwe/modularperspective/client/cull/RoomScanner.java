package org.yanbwe.modularperspective.client.cull;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * 房间扫描（RoomScanner 语义）的 v0.3 首版近似（§11.2 / §16 M6）。
 *
 * <p>从玩家脚下向上取一个空气采样点，沿 6 邻接泛洪玩家可呼吸的空气体积。若在
 * {@link #MAX_AIR_BLOCKS} 与 {@link #MAX_DISTANCE} 内收敛（没有溢出），且玩家头顶存在天花板、
 * 四个水平方向中有至少三个方向在 {@link #HORIZONTAL_PROBE} 格内被实心方块挡住，则判定为
 * {@link Type#INDOOR}，否则 {@link Type#OUTDOOR}。
 *
 * <p>房间扫描只决定“方块级剔除怎么处理连通形状”：室内时只剔除房间外壳（与房间空气相邻的
 * 实心方块）组成的连通片，避免把与房间相连的整座山体一起挖掉；室外沿用“小形状整体剔除、
 * 大形状退回视线隧道”的规则。
 *
 * <p>v0.3 首版不建立房间图（旧模组 RoomScanner/RoomGraph 语义），不做房间合并与门洞识别，
 * 这些留到后续里程碑。
 */
public final class RoomScanner {

    /** 房间类型。 */
    public enum Type {
        /** 室外/开放空间：连通形状可能无限大，按形状大小决定剔除策略。 */
        OUTDOOR,
        /** 室内：连通形状只保留房间外壳部分。 */
        INDOOR
    }

    /** 空气泛洪上限（个方块）。 */
    private static final int MAX_AIR_BLOCKS = 4096;
    /** 水平/垂直泛洪半径（格）。 */
    private static final int MAX_DISTANCE = 24;
    /** 外壳方块上限（个）。 */
    private static final int MAX_SHELL_BLOCKS = 2048;
    /** 判定室内时，水平四个方向探针的格数。 */
    private static final int HORIZONTAL_PROBE = 12;
    /** 重新扫描的最小间隔（tick）。 */
    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** 玩家移动超过该距离（平方）时立即重扫。 */
    private static final double RESCAN_MOVE_SQ = 0.25D;
    /** 天花板探针顶部（相对玩家脚上方格数）。 */
    private static final int CEILING_PROBE = 8;

    private static final LongOpenHashSet AIR = new LongOpenHashSet();
    private static final LongOpenHashSet SHELL = new LongOpenHashSet();

    private static Type type = Type.OUTDOOR;
    private static int airCount;
    private static int shellCount;
    private static long lastScanTick = Long.MIN_VALUE;
    private static BlockPos lastOrigin;
    private static boolean overflowed;

    private RoomScanner() {
    }

    /** 每 tick 兜底刷新（节流：每 {@link #RESCAN_INTERVAL_TICKS} tick 或玩家移动 0.5 格）。 */
    public static void tick(Minecraft minecraft) {
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            reset();
            return;
        }
        BlockPos origin = minecraft.player.blockPosition().above();
        long gameTime = level.getGameTime();
        if (lastScanTick != Long.MIN_VALUE
                && gameTime - lastScanTick < RESCAN_INTERVAL_TICKS
                && lastOrigin != null
                && origin.distSqr(lastOrigin) < RESCAN_MOVE_SQ) {
            return;
        }
        lastScanTick = gameTime;
        lastOrigin = origin;
        scan(level, origin);
    }

    private static void scan(Level level, BlockPos origin) {
        AIR.clear();
        SHELL.clear();
        overflowed = false;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        if (!canFlood(level, origin)) {
            type = Type.OUTDOOR;
            airCount = 0;
            shellCount = 0;
            return;
        }
        AIR.add(origin.asLong());
        queue.add(origin);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int limitSq = MAX_DISTANCE * MAX_DISTANCE;
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
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
                    BlockPos next = cursor.immutable();
                    if (next.distSqr(origin) > limitSq) {
                        continue;
                    }
                    long key = next.asLong();
                    if (AIR.contains(key) || SHELL.contains(key)) {
                        continue;
                    }
                    if (canFlood(level, next)) {
                        AIR.add(key);
                        if (AIR.size() > MAX_AIR_BLOCKS) {
                            overflowed = true;
                            break;
                        }
                        queue.add(next);
                    } else {
                        SHELL.add(key);
                        if (SHELL.size() > MAX_SHELL_BLOCKS) {
                            overflowed = true;
                            break;
                        }
                    }
                }
                if (overflowed) {
                    break;
                }
            }
            if (overflowed) {
                break;
            }
        }
        airCount = AIR.size();
        shellCount = SHELL.size();
        if (overflowed) {
            type = Type.OUTDOOR;
            return;
        }
        boolean ceiling = hasCeiling(level, origin);
        int enclosedSides = 0;
        for (int[] dir : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (sideBlocked(level, origin, dir[0], dir[1])) {
                enclosedSides++;
            }
        }
        type = ceiling && enclosedSides >= 3 ? Type.INDOOR : Type.OUTDOOR;
    }

    private static boolean canFlood(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() && !state.isSolidRender(level, pos);
    }

    private static boolean hasCeiling(Level level, BlockPos origin) {
        for (int dy = 2; dy <= CEILING_PROBE; dy++) {
            BlockPos pos = origin.above(dy);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.isSolidRender(level, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sideBlocked(Level level, BlockPos origin, int dx, int dz) {
        for (int step = 2; step <= HORIZONTAL_PROBE; step++) {
            BlockPos pos = origin.offset(dx * step, 0, dz * step);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.isSolidRender(level, pos)) {
                return true;
            }
        }
        return false;
    }

    /** 玩家当前是否处于被判定为室内的空间。 */
    public static boolean isIndoor() {
        return type == Type.INDOOR;
    }

    public static Type type() {
        return type;
    }

    public static int airCount() {
        return airCount;
    }

    public static int shellCount() {
        return shellCount;
    }

    /** 该方块是否属于房间外壳（与房间空气相邻的实心方块）。 */
    public static boolean isShell(BlockPos pos) {
        return SHELL.contains(pos.asLong());
    }

    /** 该方块是否属于玩家可呼吸的房间空气。 */
    public static boolean isRoomAir(BlockPos pos) {
        return AIR.contains(pos.asLong());
    }

    /** 立即重扫（配置/场景切换后调用）。 */
    public static void invalidate() {
        lastScanTick = Long.MIN_VALUE;
        lastOrigin = null;
    }

    public static void reset() {
        AIR.clear();
        SHELL.clear();
        type = Type.OUTDOOR;
        airCount = 0;
        shellCount = 0;
        overflowed = false;
        lastScanTick = Long.MIN_VALUE;
        lastOrigin = null;
    }

    /** 调试用：外壳集合的只读视图。 */
    public static it.unimi.dsi.fastutil.longs.LongSet shellBlocks() {
        return it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(SHELL);
    }

    /** 调试用：房间空气集合的只读视图。 */
    public static it.unimi.dsi.fastutil.longs.LongSet roomAirBlocks() {
        return it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(AIR);
    }
}
