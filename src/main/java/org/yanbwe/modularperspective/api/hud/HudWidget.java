package org.yanbwe.modularperspective.api.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 第三方可注册的 HUD 控件（docs/design.md §10.2）。
 *
 * <p>实现类只需给出锚点/偏移/尺寸/title，渲染与鼠标路由由 {@link HudRegistry} 的客户端宿主统一处理；
 * 默认 {@link #clickThrough()} 为 {@code true}（纯展示，不吞事件），交互控件应显式返回 {@code false}。
 *
 * <p>坐标是原版 GUI 缩放坐标；渲染发生在原版 HUD 之后（注册到 {@code registerAboveAll} 的层内），
 * 同一层内按 {@link #zOrder()} 升序绘制，命中测试按降序。
 */
public interface HudWidget {
    Anchor anchor();

    /** 相对锚点的 x 偏移（像素，GUI 缩放坐标）。 */
    int x();

    /** 相对锚点的 y 偏移（像素，GUI 缩放坐标）。 */
    int y();

    int width();

    int height();

    int zOrder();

    default boolean clickThrough() {
        return true;
    }

    default boolean visible() {
        return true;
    }

    void render(HudContext ctx, GuiGraphics graphics, DeltaTracker deltaTracker);

    default boolean onMouseDown(HudContext ctx, int button) {
        return false;
    }

    default boolean onMouseUp(HudContext ctx, int button) {
        return false;
    }

    default boolean onMouseScroll(HudContext ctx, double delta) {
        return false;
    }

    default boolean onMouseMove(HudContext ctx, double mouseX, double mouseY) {
        return false;
    }
}
