package org.yanbwe.modularperspective.client.cull;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 剔除判定里的“遮挡物”口径（m07629 真实场景修复）。
 *
 * <p>原版 {@code BlockState#isSolidRender} 只认完整实心方块，树叶/原木这类
 * {@code canOcclude == false} 的自然方块会被跳过，于是站在树干后视角里
 * 树叶完全不剔除、树干半透明了树叶还挡着。旧模组对这些方块按 TREE 类整棵树处理，
 * 这里给 BlockCuller 提供一个统一判定：完整实心方块，或 {@link OccluderClass} 判为 TREE 的方块。
 *
 * <p>只影响“射线命中/连通形状泛洪”的口径；SHAPE 锥形通道仍只剔实心方块，
 * 避免锥形范围把整片树叶削掉。
 */
public final class CullingOccluders {
    private CullingOccluders() {
    }

    /** 该方块是否会挡住相机→玩家视线。 */
    public static boolean isOccluder(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isSolidRender(level, pos)) {
            return true;
        }
        return OccluderClass.classify(state) == OccluderClass.TREE;
    }
}
