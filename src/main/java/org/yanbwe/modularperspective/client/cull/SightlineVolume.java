package org.yanbwe.modularperspective.client.cull;


/**
 * 纯数学部分的相机-角色“视线体积”定义（不依赖 Minecraft 类，便于单测）。
 *
 * <p>设计（docs/design.md §11.2 调整，2026-11-08 实机反馈）：以相机位置到角色眼睛的连线为轴，
 * 半径 {@code inner} 以内完全透明、{@code inner}..{@code outer} 之间快速提升不透明度，
 * 且线段首尾用平滑包络收口，避免突然出现/消失的边界。</p>
 */
public final class SightlineVolume {

    private SightlineVolume() {
    }

    /** 两端包络的平滑区间（按线段参数 t 计）。 */
    public static final double END_FADE = 0.18D;

    /**
     * 点到线段（a→b）的垂直距离，并把投影参数写入 {@code tOut[0]}（若不为 null）。
     */
    public static double perpendicularDistance(double px, double py, double pz,
                                               double ax, double ay, double az,
                                               double bx, double by, double bz,
                                               double[] tOut) {
        double abx = bx - ax;
        double aby = by - ay;
        double abz = bz - az;
        double lengthSq = abx * abx + aby * aby + abz * abz;
        double t = 0.0D;
        if (lengthSq > 1.0E-9D) {
            t = ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / lengthSq;
        }
        t = clamp(t, 0.0D, 1.0D);
        if (tOut != null && tOut.length > 0) {
            tOut[0] = t;
        }
        double dx = px - (ax + abx * t);
        double dy = py - (ay + aby * t);
        double dz = pz - (az + abz * t);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 线段两端的平滑包络：中间为 1，靠近相机/角色两端时平滑降到 0。 */
    public static double envelope(double t) {
        double clamped = clamp(t, 0.0D, 1.0D);
        return smoothstep(0.0D, END_FADE, clamped) * smoothstep(0.0D, END_FADE, 1.0D - clamped);
    }

    /**
     * 按垂直距离与线段位置计算不透明度：inner 内为 0（完全透明），随后快速上升，outer 处达到 {@code maxAlpha}。
     */
    public static double alpha(double perpendicularDistance, double t, double inner, double outer, double maxAlpha) {
        double env = envelope(t);
        if (env <= 0.02D) {
            return 0.0D;
        }
        double x = clamp((perpendicularDistance - inner) / Math.max(1.0E-4D, outer - inner), 0.0D, 1.0D);
        // 先快速上升（变化率大），再用 smoothstep 收口，避免端点折线。
        double ramp = 1.0D - (1.0D - x) * (1.0D - x);
        double shaped = smoothstep(0.0D, 1.0D, ramp);
        double a = maxAlpha * shaped * env;
        return a < 0.02D ? 0.0D : a;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    static double smoothstep(double edge0, double edge1, double x) {
        if (edge0 == edge1) {
            return x < edge0 ? 0.0D : 1.0D;
        }
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
