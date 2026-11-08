package org.yanbwe.modularperspective.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.api.hud.Anchor;
import org.yanbwe.modularperspective.api.hud.HudContext;
import org.yanbwe.modularperspective.api.hud.HudLayout;
import org.yanbwe.modularperspective.api.hud.HudWidget;

/**
 * 第三方 HUD 控件示例（docs/design.md §10.2）：可拖拽的小徽标。
 *
 * <p>用于演示外部模组如何通过 {@link org.yanbwe.modularperspective.api.hud.HudRegistry}
 * 注册控件、拿到鼠标事件、以及拖拽时自行调整偏移；配置项 {@code hudExamples=false} 可整体关闭示例控件。
 */
public final class ExampleBadgeWidget implements HudWidget {
    private static final int WIDTH = 112;
    private static final int HEIGHT = 16;
    private static final int BACKGROUND = 0x99202020;
    private static final int BORDER = 0xFF6A6A6A;
    private static final int TEXT = 0xFFE0E0E0;

    private int offsetX = 4;
    private int offsetY = 26;

    @Override
    public Anchor anchor() {
        return Anchor.TOP_LEFT;
    }

    @Override
    public int x() {
        return offsetX;
    }

    @Override
    public int y() {
        return offsetY;
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public int height() {
        return HEIGHT;
    }

    @Override
    public int zOrder() {
        return 90;
    }

    @Override
    public boolean clickThrough() {
        return false;
    }

    @Override
    public boolean draggable() {
        return true;
    }

    @Override
    public boolean visible() {
        return Config.HUD_EXAMPLES.get();
    }

    @Override
    public void render(HudContext ctx, GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        int x = HudLayout.x(anchor(), x(), width(), ctx.screenWidth());
        int y = HudLayout.y(anchor(), y(), height(), ctx.screenHeight());
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BACKGROUND);
        graphics.fill(x, y, x + WIDTH, y + 1, BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, BORDER);
        graphics.drawString(minecraft.font, "示例控件（可拖拽）", x + 5, y + 4, TEXT, false);
    }

    @Override
    public void onDrag(HudContext ctx, double deltaX, double deltaY) {
        // 拖拽只改自身偏移；超出屏幕时钳制在可视区域内（宿主不替控件做布局决定）。
        int maxX = Math.max(0, ctx.screenWidth() - WIDTH);
        int maxY = Math.max(0, ctx.screenHeight() - HEIGHT);
        offsetX = Mth.clamp(offsetX + (int) Math.round(deltaX), 0, maxX);
        offsetY = Mth.clamp(offsetY + (int) Math.round(deltaY), 0, maxY);
    }
}
