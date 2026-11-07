package org.yanbwe.modularperspective.api.camera;

import java.util.EnumSet;
import org.jetbrains.annotations.Nullable;

/**
 * 一个通道的完整目标值（docs/design.md §4.2、§5.1）。
 *
 * <p>字段为 null 表示“该通道没有目标/不指定”，不做字段级继承（v0.3 定稿：每个通道写完整目标，
 * 未指定的字段保持当前输出）。{@code distance} 在正交下同时是可视半高（§8.2）；
 * {@code fov} 只在透视下有可见意义（正交时 {@code ViewportEvent.ComputeFov} 会被忽略）。
 */
public record CameraTarget(
        @Nullable Position3 position,
        @Nullable Float yaw,
        @Nullable Float pitch,
        @Nullable Float distance,
        @Nullable Float fov,
        @Nullable Projection projection,
        @Nullable Boolean hudVisible,
        @Nullable EnumSet<InputLock> locks) {

    public static final CameraTarget EMPTY =
            new CameraTarget(null, null, null, null, null, null, null, null);

    public CameraTarget {
        if (locks != null) {
            locks = locks.isEmpty() ? EnumSet.noneOf(InputLock.class) : EnumSet.copyOf(locks);
        }
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

    public boolean hasProjection() {
        return projection != null;
    }

    public boolean hasHudVisible() {
        return hudVisible != null;
    }

    public boolean hasLocks() {
        return locks != null;
    }
}
