package org.yanbwe.modularperspective.api.hud;

/**
 * HUD 控件的纯坐标计算（docs/design.md §10.2）：与 Minecraft 类型无关，方便 JUnit 覆盖。
 *
 * <p>锚点决定控件矩形贴在屏幕的哪一侧/角；{@code offsetX/offsetY} 是相对锚点的偏移。
 * 例如 {@code TOP_RIGHT} 且 {@code offsetX = -4} 表示距右边缘 4 像素。
 */
public final class HudLayout {
    private HudLayout() {}

    public static int x(Anchor anchor, int offsetX, int width, int screenWidth) {
        return switch (anchor.horizontal()) {
            case -1 -> offsetX;
            case 1 -> screenWidth - width + offsetX;
            default -> (screenWidth - width) / 2 + offsetX;
        };
    }

    public static int y(Anchor anchor, int offsetY, int height, int screenHeight) {
        return switch (anchor.vertical()) {
            case -1 -> offsetY;
            case 1 -> screenHeight - height + offsetY;
            default -> (screenHeight - height) / 2 + offsetY;
        };
    }

    public static Rect rect(Anchor anchor, int offsetX, int offsetY, int width, int height,
                            int screenWidth, int screenHeight) {
        return new Rect(x(anchor, offsetX, width, screenWidth), y(anchor, offsetY, height, screenHeight),
                width, height);
    }

    /** 屏幕坐标矩形；{@code contains} 用左下开放式区间，避免相邻控件重叠命中。 */
    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public int centerX() {
            return x + width / 2;
        }

        public int centerY() {
            return y + height / 2;
        }
    }
}
