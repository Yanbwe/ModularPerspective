package org.yanbwe.modularperspective.api.hud;

/**
 * HUD 控件的九宫格锚点（docs/design.md §10.2）。
 *
 * <p>坐标是原版 GUI 缩放坐标：{@link #horizontal()} / {@link #vertical()} 为 -1（左/上）、
 * 0（中）、1（右/下），配合控件自身的 x/y 偏移量计算实际位置。
 */
public enum Anchor {
    TOP_LEFT(-1, -1),
    TOP_CENTER(0, -1),
    TOP_RIGHT(1, -1),
    CENTER_LEFT(-1, 0),
    CENTER(0, 0),
    CENTER_RIGHT(1, 0),
    BOTTOM_LEFT(-1, 1),
    BOTTOM_CENTER(0, 1),
    BOTTOM_RIGHT(1, 1);

    private final int horizontal;
    private final int vertical;

    Anchor(int horizontal, int vertical) {
        this.horizontal = horizontal;
        this.vertical = vertical;
    }

    public int horizontal() {
        return horizontal;
    }

    public int vertical() {
        return vertical;
    }
}
