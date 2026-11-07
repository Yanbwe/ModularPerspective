package org.yanbwe.modularperspective.api.camera;

/**
 * 过渡缓动（docs/design.md §5.1、§5.6）。离散通道（PROJECTION/HUD）忽略 fade。
 */
public enum Easing {
    LINEAR,
    EASE_IN_OUT,
    SMOOTHSTEP
}
