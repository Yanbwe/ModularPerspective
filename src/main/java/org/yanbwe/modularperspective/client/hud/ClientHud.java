package org.yanbwe.modularperspective.client.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.api.hud.Anchor;
import org.yanbwe.modularperspective.api.hud.HudContext;
import org.yanbwe.modularperspective.api.hud.HudLayout;
import org.yanbwe.modularperspective.api.hud.HudRegistry;
import org.yanbwe.modularperspective.api.hud.HudWidget;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.client.input.CursorController;

/**
 * HUD 宿主（docs/design.md §10.2、§10.3）。
 *
 * <p>注册到 {@code RegisterGuiLayersEvent#registerAboveAll}，因此位于原版 HUD、睡眠/演示/调试层
 * 以及所有 Screen 之上的“挂件层”。本类只画自己的控件，不接管 Screen、不改原版 HUD。
 *
 * <p>鼠标路由状态机：hover 每帧对鼠标坐标轮询；按下命中非 clickThrough 控件时 capture（独占直到抬起，
 * 即使指针移出矩形）；滚轮只给 hover 控件且 clickThrough=true 不消费。Screen 打开或 HUD 被隐藏时清空
 * hover/pressed/capture，防止“幽灵按下”。
 */
public final class ClientHud implements LayeredDraw.Layer {
    public static final ClientHud INSTANCE = new ClientHud();

    private static final Comparator<Registered> Z_ORDER =
            Comparator.comparingInt(registered -> registered.widget().zOrder());
    /** debugClick 用来模拟一次完整的按下/抬起。 */
    private static final int DEBUG_BUTTON = 0;

    private static boolean debugForced;

    private final List<Registered> widgets = new ArrayList<>();
    private boolean initialised;

    private @Nullable HudContext context;

    private double mouseX = -1.0;
    private double mouseY = -1.0;

    private @Nullable Registered hovered;
    private @Nullable Registered pressed;
    private @Nullable Registered captured;

    private record Registered(ResourceLocation id, HudWidget widget) {
        private HudLayout.Rect rect(HudContext ctx) {
            return HudLayout.rect(widget.anchor(), widget.x(), widget.y(),
                    widget.width(), widget.height(), ctx.screenWidth(), ctx.screenHeight());
        }
    }

    private ClientHud() {}

    // ------------------------------------------------------------------ 入口

    public static void toggleDebugOverlay() {
        debugForced = !debugForced;
        ModularPerspective.LOGGER.info("[perspective] debug HUD overlay {}", debugForced ? "ON" : "OFF");
    }

    public static boolean debugOverlayForced() {
        return debugForced;
    }

    /** 当前是否应渲染 HUD 层（§10.2 整层门控）。 */
    public static boolean overlayVisible() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.screen == null
                && !minecraft.options.hideGui
                && ClientPerspective.hudVisible();
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        HudContext ctx = buildContext(minecraft, deltaTracker, graphics);
        this.context = ctx;
        ensureWidgets(ctx);
        if (!overlayVisible()) {
            clearMouseState();
            return;
        }
        updateMouse(minecraft, ctx);
        hovered = hitTest(mouseX, mouseY);
        List<Registered> renderOrder = new ArrayList<>(widgets);
        renderOrder.sort(Z_ORDER);
        for (Registered registered : renderOrder) {
            if (registered.widget().visible()) {
                registered.widget().render(ctx, graphics, deltaTracker);
            }
        }
    }

    private HudContext buildContext(Minecraft minecraft, DeltaTracker deltaTracker, GuiGraphics graphics) {
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        ResolvedCameraState state = ClientPerspective.frameState(partialTick);
        List<CameraDirective> directives = ClientPerspective.directiveView();
        return new HudContext(partialTick, graphics.guiWidth(), graphics.guiHeight(),
                state, directives, minecraft.screen != null);
    }

    private void ensureWidgets(HudContext ctx) {
        if (initialised) {
            return;
        }
        initialised = true;
        Map<ResourceLocation, Function<HudContext, HudWidget>> factories = HudRegistry.factories();
        for (Map.Entry<ResourceLocation, Function<HudContext, HudWidget>> entry : factories.entrySet()) {
            HudWidget widget = Objects.requireNonNull(entry.getValue().apply(ctx), "HUD widget factory returned null");
            widgets.add(new Registered(entry.getKey(), widget));
        }
        ModularPerspective.LOGGER.info("[perspective] HUD layer initialised with {} widget(s)", widgets.size());
    }

    private void updateMouse(Minecraft minecraft, HudContext ctx) {
        if (!CursorController.isPseudoUnlocked()) {
            mouseX = -1.0;
            mouseY = -1.0;
            return;
        }
        int screenWidth = minecraft.getWindow().getScreenWidth();
        int screenHeight = minecraft.getWindow().getScreenHeight();
        if (screenWidth <= 0 || screenHeight <= 0) {
            mouseX = -1.0;
            mouseY = -1.0;
            return;
        }
        mouseX = minecraft.mouseHandler.xpos() * ctx.screenWidth() / screenWidth;
        mouseY = minecraft.mouseHandler.ypos() * ctx.screenHeight() / screenHeight;
    }

    // ------------------------------------------------------------------ 鼠标路由（§10.3）

    /**
     * {@code InputEvent.MouseButton.Pre} 入口。
     *
     * @return true = 事件已消费（不触发攻击/挖掘/拾取）
     */
    public static boolean onMouseButton(boolean pressed, int button) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientHud hud = INSTANCE;
        if (!overlayVisible()) {
            hud.clearMouseState();
            return false;
        }
        HudContext ctx = hud.context;
        if (ctx == null) {
            return false;
        }
        if (pressed) {
            Registered target = hud.hitTest(hud.mouseX, hud.mouseY);
            if (target == null || target.widget().clickThrough()) {
                return false;
            }
            hud.captured = target;
            hud.pressed = target;
            target.widget().onMouseDown(ctx, button);
            return true;
        }
        Registered captured = hud.captured;
        if (captured == null) {
            return false;
        }
        hud.captured = null;
        hud.pressed = null;
        captured.widget().onMouseUp(ctx, button);
        return !captured.widget().clickThrough();
    }

    /** {@code InputEvent.MouseScrollingEvent} 入口，返回 true 表示消费（取消原版滚轮）。 */
    public static boolean onMouseScroll(double delta) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientHud hud = INSTANCE;
        if (!overlayVisible() || delta == 0.0) {
            if (!overlayVisible()) {
                hud.clearMouseState();
            }
            return false;
        }
        HudContext ctx = hud.context;
        if (ctx == null) {
            return false;
        }
        hud.updateMouse(minecraft, ctx);
        Registered target = hud.hovered != null ? hud.hovered : hud.hitTest(hud.mouseX, hud.mouseY);
        if (target == null || target.widget().clickThrough()) {
            return false;
        }
        target.widget().onMouseScroll(ctx, delta);
        return true;
    }

    private @Nullable Registered hitTest(double mouseX, double mouseY) {
        if (mouseX < 0.0 || mouseY < 0.0 || context == null) {
            return null;
        }
        List<Registered> order = new ArrayList<>(widgets);
        order.sort(Z_ORDER.reversed());
        for (Registered registered : order) {
            HudWidget widget = registered.widget();
            if (!widget.visible()) {
                continue;
            }
            if (registered.rect(context).contains(mouseX, mouseY)) {
                return registered;
            }
        }
        return null;
    }

    private void clearMouseState() {
        hovered = null;
        pressed = null;
        captured = null;
        mouseX = -1.0;
        mouseY = -1.0;
    }

    // ------------------------------------------------------------------ dev 自动验收

    /** 模拟一次左键点击（供 dev 自动验收；不依赖真实 GLFW 事件）。 */
    public static void debugClick(int guiX, int guiY) {
        ClientHud hud = INSTANCE;
        Minecraft minecraft = Minecraft.getInstance();
        HudContext ctx = hud.context;
        if (ctx == null) {
            ModularPerspective.LOGGER.warn("[hud] debug click ignored: no context yet");
            return;
        }
        hud.mouseX = guiX;
        hud.mouseY = guiY;
        Registered target = hud.hitTest(guiX, guiY);
        boolean pressConsumed = hud.handleDebugButton(ctx, true, target);
        boolean releaseConsumed = hud.handleDebugButton(ctx, false, target);
        ModularPerspective.LOGGER.info(
                "[hud] debug click at ({},{}) target={} pressConsumed={} releaseConsumed={} hovered={}",
                guiX, guiY, target == null ? "none" : target.id(), pressConsumed, releaseConsumed,
                hud.hovered == null ? "none" : hud.hovered.id());
    }

    private boolean handleDebugButton(HudContext ctx, boolean pressed, @Nullable Registered target) {
        if (pressed) {
            if (target == null || target.widget().clickThrough()) {
                return false;
            }
            captured = target;
            this.pressed = target;
            target.widget().onMouseDown(ctx, DEBUG_BUTTON);
            return true;
        }
        if (captured == null) {
            return false;
        }
        Registered released = captured;
        captured = null;
        this.pressed = null;
        released.widget().onMouseUp(ctx, DEBUG_BUTTON);
        return !released.widget().clickThrough();
    }

    /** 当前 HUD 状态摘要（dev 自动验收日志用）。 */
    public static String debugStatus() {
        ClientHud hud = INSTANCE;
        return "visible=" + overlayVisible()
                + " widgets=" + hud.widgets.size()
                + " hovered=" + (hud.hovered == null ? "none" : hud.hovered.id())
                + " captured=" + (hud.captured == null ? "none" : hud.captured.id())
                + " hudVisible=" + ClientPerspective.hudVisible();
    }

    /** 已注册控件数量（测试/日志用）。 */
    public static int widgetCount() {
        return INSTANCE.widgets.size();
    }

    /** 某个控件是否在给定坐标下（自动验收快速断言）。 */
    public static boolean widgetAt(int guiX, int guiY) {
        ClientHud hud = INSTANCE;
        return hud.hitTest(guiX, guiY) != null;
    }

    /** 命中测试是否越过 clickThrough=false 的控件（debug 用）。 */
    public static boolean hasNonClickThroughAt(int guiX, int guiY) {
        ClientHud hud = INSTANCE;
        Registered target = hud.hitTest(guiX, guiY);
        return target != null && !target.widget().clickThrough();
    }
}
