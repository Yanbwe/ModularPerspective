package org.yanbwe.modularperspective.api.camera;

/**
 * 投影方式（docs/design.md §4.1、§8.2）。
 *
 * <p>{@link #ORTHOGRAPHIC} 必须自行提供投影矩阵（不能依赖原版 {@code orthographic} 路径），
 * 并且必须替换深度排序，见 §8.2。
 */
public enum Projection {
    PERSPECTIVE,
    ORTHOGRAPHIC
}
