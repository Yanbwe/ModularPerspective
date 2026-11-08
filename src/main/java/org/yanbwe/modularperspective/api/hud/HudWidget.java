package org.yanbwe.modularperspective.api.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 第三方可注册的 HUD 控件（docs/design.md §10.2）。
 *
 * <p>实现类只需给出锚点/偏移/尺寸/title，渲染与鼠标路由由 {@link HudRegistry} 的客户端宿主统一处理；
 * 默认 {@link #clickThrough()} 为 {@code true}（纯展示，不吞事件），交互控件应显式返回 {@code false}。
 *
 * <p>坐标是原版 GUI 缩放坐标；渲染发生在原版 HUD 之后（注册到 {@code registerAboveAll} 的层内），
 * 同一层内按 {@link #zOrder()} 升序绘制，命中测试按降序 —— 重叠时只有最上层能收到鼠标事件，
 * 一旦按下即独占捕获（直到抬起），其它控件不会“半路抢走”。
 *
 * <p>M4 起支持两类扩展：
 * <ul>
 *   <li>{@link #draggable()}：宿主负责按下/拖动路由，控件在 {@link #onDrag} 里自行调整偏移；</li>
 *   <li>{@link #worldAnchor(HudContext)}：返回世界坐标后，宿主把它投影到 GUI 坐标（仅正交俯视时可用），
 *       实现“世界锚定 HUD”；返回 null 表示普通屏幕锚点。</li>
 * </ul>
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

    /** 是否由宿主负责拖拽路由（按下后移动鼠标即可拖动，控件自行改 {@link #x()}/{@link #y()}）。 */
    default boolean draggable() {
        return false;
    }

    /**
     * 世界锚点（§10.4 世界锚定 HUD）：非 null 时宿主把该点投影为 GUI 坐标，再加 {@link #x()}/{@link #y()} 偏移；
     * 返回 null 表示使用屏幕锚点。只有正交俯视时宿主才会给出投影（其它投影下退回屏幕锚点）。
     */
    default @Nullable Vec3 worldAnchor(HudContext ctx) {
        return null;
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

    /** 拖拽开始（仅 {@link #draggable()} 且被按下时调用）。 */
    default void onDragStart(HudContext ctx) {}

    /** 拖拽中（鼠标位移增量，GUI 像素；仅 {@link #draggable()} 且已被捕获时调用）。 */
    default void onDrag(HudContext ctx, double deltaX, double deltaY) {}

    /** 拖拽结束（松开鼠标时调用）。 */
    default void onDragEnd(HudContext ctx) {}
}
