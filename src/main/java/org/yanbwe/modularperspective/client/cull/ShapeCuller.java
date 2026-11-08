package org.yanbwe.modularperspective.client.cull;

/**
 * 形状/视线剔除的纯几何部分（旧模组 {@code GenericCuller3} 的锥/柱近似，docs/design.md §11.2 的 SHAPE tier 首片）。
 *
 * <p>候选方块需同时满足：
 * <ul>
 *   <li>方块中心高于玩家脚部上方 2 格（避免挖掉脚下的地面）；</li>
 *   <li>距相机 5 格内一律剔除（避免相机贴进地形里）；</li>
 *   <li>否则其到“玩家脚部 → 相机”轴线的垂直距离不超过 min(投影距离 × tan(半角), 圆柱半径)。</li>
 * </ul>
 *
 * <p>纯数学实现，便于 JUnit 单测（不依赖 Minecraft 类）。
 */
public final class ShapeCuller {
    /** 相机周围“无条件剔除”的半径（格），对应旧模组 GenericCuller3 的 5 格。 */
    public static final double MIN_PROJECTION = 1.5D;

    private ShapeCuller() {}

    /**
     * 判断一个方块中心是否在相机→玩家的锥/柱剔除范围内。
     *
     * @param blockX 方块中心 X
     * @param blockY 方块中心 Y
     * @param blockZ 方块中心 Z
     * @param feetX  玩家脚部 X（轴线起点）
     * @param feetY  玩家脚部 Y
     * @param feetZ  玩家脚部 Z
     * @param cameraX 相机 X（轴线终点）
     * @param cameraY 相机 Y
     * @param cameraZ 相机 Z
     * @param radius 圆柱半径上限（格）
     * @param coneHalfAngleDegrees 近玩家处的锥形半角（度）
     * @return 是否应剔除
     */
    public static boolean shouldCull(double blockX, double blockY, double blockZ,
                                     double feetX, double feetY, double feetZ,
                                     double cameraX, double cameraY, double cameraZ,
                                     double radius, double coneHalfAngleDegrees) {
        if (blockY <= feetY + 1.0D) {
            return false;
        }
        double axisX = cameraX - feetX;
        double axisY = cameraY - feetY;
        double axisZ = cameraZ - feetZ;
        double axisLenSq = axisX * axisX + axisY * axisY + axisZ * axisZ;
        if (axisLenSq < 1.0E-6D) {
            return false;
        }
        double relX = blockX - feetX;
        double relY = blockY - feetY;
        double relZ = blockZ - feetZ;
        double projection = (relX * axisX + relY * axisY + relZ * axisZ) / Math.sqrt(axisLenSq);
        if (projection <= 0.0D) {
            return false;
        }
        if (projection > Math.sqrt(axisLenSq) - MIN_PROJECTION) {
            return false;
        }
        double crossX = relY * axisZ - relZ * axisY;
        double crossY = relZ * axisX - relX * axisZ;
        double crossZ = relX * axisY - relY * axisX;
        double perpendicularSq = (crossX * crossX + crossY * crossY + crossZ * crossZ) / axisLenSq;
        double coneRadius = projection * Math.tan(Math.toRadians(coneHalfAngleDegrees));
        double effectiveRadius = Math.min(coneRadius, radius);
        return perpendicularSq <= effectiveRadius * effectiveRadius;
    }
}
