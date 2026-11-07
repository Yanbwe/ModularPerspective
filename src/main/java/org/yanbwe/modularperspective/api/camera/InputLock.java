package org.yanbwe.modularperspective.api.camera;

/**
 * 输入锁位（docs/design.md §5.3）：锁只锁输入，不锁光标。
 *
 * <ul>
 *   <li>{@link #ROTATE}：玩家视角旋转（yaw/pitch）。</li>
 *   <li>{@link #ZOOM}：玩家缩放（滚轮 / 第三人称距离）。</li>
 *   <li>{@link #MOVE}：玩家水平移动（只锁水平，跳跃/潜行不锁，§9.2）。</li>
 * </ul>
 */
public enum InputLock {
    ROTATE,
    ZOOM,
    MOVE
}
