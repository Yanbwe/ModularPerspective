package org.yanbwe.modularperspective.api.camera;

/**
 * 相机/输入通道（docs/design.md §4.2、§5）。
 *
 * <p>每条指令在提交时给出"每个通道的完整目标值"（{@link CameraTarget}），不存在的通道表示"本指令不管"，
 * 不做字段级继承。
 */
public enum CameraChannel {
    /** 相机位置（three-dimensional，世界坐标或实体相对，见 {@link CameraTarget}）。 */
    POSITION,
    /** 朝向 yaw/pitch（连续通道，插值走最短弧）。 */
    ANGLE,
    /** 第三人称距离（连续通道）。 */
    ZOOM,
    /** 投影方式（离散通道，忽略 fade）。 */
    PROJECTION,
    /** 视场角（连续通道，单位度）。 */
    FOV,
    /** HUD 显隐与挂件参数（离散通道）。 */
    HUD,
    /** 输入锁位掩码（按轴并集，不按 priority 覆盖，§5.3）。 */
    INPUT_LOCK
}
