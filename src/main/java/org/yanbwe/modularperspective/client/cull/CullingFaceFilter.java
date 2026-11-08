package org.yanbwe.modularperspective.client.cull;

import net.minecraft.core.BlockPos;

/** 统一查询“这个方块当前是否被任一剔除 tier 隐藏”（M6 收尾 facades 用，m07629）。 */
public final class CullingFaceFilter {
    private CullingFaceFilter() {
    }

    public static boolean isCulled(BlockPos pos) {
        if (VolumeCuller.get().isCulled(pos)) {
            return true;
        }
        if (BlockCuller.isCulled(pos)) {
            return true;
        }
        if (RoomScanner.get().test(pos) == RoomScanner.CULL) {
            return true;
        }
        return SightlineScanner.get().isCulled(pos);
    }
}
