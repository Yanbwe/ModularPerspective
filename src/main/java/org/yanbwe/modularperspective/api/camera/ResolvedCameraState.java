package org.yanbwe.modularperspective.api.camera;

import java.util.EnumSet;
import org.jetbrains.annotations.Nullable;

/**
 * 仲裁后的相机输出（docs/design.md §5.2、§5.6）：每帧只算一次，供各 mixin 读取，禁止各 mixin 各自仲裁。
 *
 * <p>null 字段表示该通道没有赢家 → 保持原版行为（例如没有 ANGLE 赢家就不覆盖视角）。
 * {@link #locks} 是 INPUT_LOCK 通道的并集（不看 priority，§5.3）。
 */
public record ResolvedCameraState(
        @Nullable Position3 position,
        @Nullable Float yaw,
        @Nullable Float pitch,
        @Nullable Float distance,
        @Nullable Float fov,
        @Nullable Projection projection,
        @Nullable Boolean hudVisible,
        EnumSet<InputLock> locks) {

    public ResolvedCameraState {
        locks = locks == null ? EnumSet.noneOf(InputLock.class) : EnumSet.copyOf(locks);
    }

    public boolean orthographic() {
        return projection == Projection.ORTHOGRAPHIC;
    }

    public boolean hasPosition() {
        return position != null;
    }

    public boolean hasAngle() {
        return yaw != null || pitch != null;
    }

    public boolean hasDistance() {
        return distance != null;
    }

    public boolean hasFov() {
        return fov != null;
    }

    public boolean hasHudVisible() {
        return hudVisible != null;
    }

    /** 当前生效的输入锁（只读视图）。 */
    public boolean locks(InputLock lock) {
        return locks.contains(lock);
    }
}
