package org.yanbwe.modularperspective.client.cull;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 剔除判定只在这里回答一次（对齐原型 {@code TerrainCulling}）。
 *
 * <p>两个扫描器（{@link RoomScanner}、{@link SightlineScanner}）都是纯方块坐标数学，与渲染管线无关；
 * 这里把“某坐标是否从网格中移除”统一成一次查询，让区块重建线程与模型包装器走同一条结论：
 * <ol>
 *   <li>房间层：{@link RoomScanner#test(BlockPos)} 返回 CULL/KEEP/NO_OPINION；KEEP 是最终裁决。</li>
 *   <li>视线轮廓层：房间层无意见时问 {@link SightlineScanner}。</li>
 * </ol>
 * 所有查询均可从区块构建 worker 调用。
 */
public final class TerrainCulling {

    /** 客户端线程预热成功后为 true；在此之前所有查询都回答“不剔除”，避免 worker 首次加载类死锁。 */
    private static volatile boolean ready;

    /** 客户端线程每 tick 更新的总开关；worker 线程只读这个标志，不碰 Config/ClientPerspective。 */
    private static volatile boolean enabled;

    /** warmUp 时在客户端线程抓好的扫描器实例；worker 线程只读这两个引用，避免类初始化死锁。 */
    private static RoomScanner roomScanner;

    private static SightlineScanner sightlineScanner;

    private TerrainCulling() {
    }

    /** 在客户端 setup 时调用一次，把 worker 会碰到的类提前加载到客户端线程。 */
    public static void warmUp() {
        roomScanner = RoomScanner.get();
        sightlineScanner = SightlineScanner.get();
        roomScanner.isActive();
        sightlineScanner.isCulling();
        ready = true;
    }

    /** 客户端线程每 tick 调用一次（Config 与相机状态都只在客户端线程读）。 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** 总开关：剔除只在俯视正交视角下启用。 */
    public static boolean isEnabled() {
        return enabled;
    }

    /** 扫描器都没结果时，热路径直接短路。 */
    public static boolean idle() {
        if (!ready || !enabled || roomScanner == null || sightlineScanner == null) {
            return true;
        }
        return !roomScanner.isActive() && !sightlineScanner.isCulling();
    }

    /** 这些方块即使周围地形被剔除也保留（玩家需要看到/点到，或单独悬空会穿帮）。 */
    public static boolean isProtected(Block block) {
        return block instanceof VaultBlock
                || block instanceof SpawnerBlock
                || block instanceof TrialSpawnerBlock
                || block instanceof FaceAttachedHorizontalDirectionalBlock
                || block instanceof DoorBlock;
    }

    /** 剔除层对“该列是否移除”的意见（不考虑 protected 列表）。 */
    public static boolean isRemoved(int x, int y, int z) {
        if (!ready || !enabled || roomScanner == null || sightlineScanner == null) {
            return false;
        }
        int room = roomScanner.test(x, y, z);
        if (room == RoomScanner.CULL) {
            return true;
        }
        if (room == RoomScanner.KEEP) {
            return false;
        }
        return sightlineScanner.shouldCull(x, y, z);
    }

    /** 该方块是否从区块网格中移除；薄片类方块跟随它所依附/站立的方块。 */
    public static boolean shouldRemove(BlockState state, int x, int y, int z) {
        Block block = state.getBlock();
        if (isProtected(block)) {
            return false;
        }
        if (isRemoved(x, y, z)) {
            return true;
        }
        if (block instanceof SnowLayerBlock || block instanceof CarpetBlock) {
            return isRemoved(x, y - 1, z);
        }
        if (block instanceof LadderBlock) {
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return isRemoved(x - facing.getStepX(), y, z - facing.getStepZ());
        }
        if (block instanceof VineBlock) {
            return attachedToRemovedTerrain(state, x, y, z);
        }
        return false;
    }

    private static boolean attachedToRemovedTerrain(BlockState state, int x, int y, int z) {
        if (state.getValue(BlockStateProperties.UP) && isRemoved(x, y + 1, z)) {
            return true;
        }
        if (state.getValue(BlockStateProperties.NORTH) && isRemoved(x, y, z - 1)) {
            return true;
        }
        if (state.getValue(BlockStateProperties.SOUTH) && isRemoved(x, y, z + 1)) {
            return true;
        }
        if (state.getValue(BlockStateProperties.WEST) && isRemoved(x - 1, y, z)) {
            return true;
        }
        return state.getValue(BlockStateProperties.EAST) && isRemoved(x + 1, y, z);
    }
}
