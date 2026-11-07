package org.yanbwe.modularperspective.client.hud;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.api.hud.Anchor;
import org.yanbwe.modularperspective.api.hud.HudContext;
import org.yanbwe.modularperspective.api.hud.HudLayout;
import org.yanbwe.modularperspective.api.hud.HudWidget;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.client.cull.BlockCuller;
import org.yanbwe.modularperspective.client.cull.RoomScanner;
import org.yanbwe.modularperspective.client.cull.SectionCuller;

/**
 * 调试覆盖层（docs/design.md §10.2、§16 M4）：显示相机/仲裁/策略状态与剔除统计占位。
 *
 * <p>默认由 {@code Config.DEBUG_OVERLAY}（common）控制；点击左上角状态挂件可临时强制显示
 * （{@link ClientHud#debugOverlayForced()}）。{@code clickThrough=true}：不吞鼠标事件。
 */
public final class DebugHudWidget implements HudWidget {
    private static final int WIDTH = 300;
    private static final int HEIGHT = 104;
    private static final int BACKGROUND = 0xB0000000;
    private static final int BORDER = 0xFF6A6A6A;
    private static final int LINE_HEIGHT = 11;
    private static final int TEXT = 0xFFD0FFD0;
    private static final int TITLE = 0xFFFFE08C;

    @Override
    public Anchor anchor() {
        return Anchor.TOP_RIGHT;
    }

    @Override
    public int x() {
        return -4;
    }

    @Override
    public int y() {
        return 4;
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
        return 200;
    }

    @Override
    public boolean clickThrough() {
        return true;
    }

    @Override
    public boolean visible() {
        return Config.DEBUG_OVERLAY.get() || ClientHud.debugOverlayForced();
    }

    @Override
    public void render(HudContext ctx, GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        int x = HudLayout.x(anchor(), x(), width(), ctx.screenWidth());
        int y = HudLayout.y(anchor(), y(), height(), ctx.screenHeight());
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BACKGROUND);
        graphics.fill(x, y, x + WIDTH, y + 1, BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, BORDER);
        List<String> lines = lines(ctx);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(minecraft.font, lines.get(i), x + 5, y + 5 + i * LINE_HEIGHT,
                    i == 0 ? TITLE : TEXT, false);
        }
    }

    private List<String> lines(HudContext ctx) {
        ResolvedCameraState state = ctx.state();
        List<String> lines = new ArrayList<>();
        lines.add("Modular Perspective M6");
        if (state == null) {
            lines.add("camera: 无指令（原版视角）");
        } else {
            lines.add(String.format("ortho=%s dist=%s pitch=%s fov=%s",
                    state.orthographic(),
                    format(state.distance()), format(state.pitch()), format(state.fov())));
            lines.add(String.format("yaw=%s  locks=%s",
                    format(state.yaw()), state.locks().isEmpty() ? "[]" : state.locks()));
        }
        lines.add(String.format("stack=%d  revision=%d  hudVisible=%s",
                ctx.directives().size(), ClientPerspective.snapshotRevision(), ClientPerspective.hudVisible()));
        var policy = ClientPerspective.serverPolicy();
        lines.add(String.format("policy req=%s sug=%s ortho=%s max=%d",
                policy.allowRequire(), policy.allowSuggest(), policy.allowOrthographic(),
                policy.maxDirectives()));
        if (Config.CULLING_BLOCK_LEVEL.get()) {
            lines.add("cull: block " + (BlockCuller.isCulling() ? "on" : "off")
                    + " blocks=" + BlockCuller.culledCount() + " r=" + BlockCuller.raysCast()
                    + " dirty=" + BlockCuller.dirtyPending());
        } else {
            lines.add("cull: section " + (SectionCuller.isCulling() ? "on" : "off")
                    + " h=" + SectionCuller.hiddenCount() + " r=" + SectionCuller.raysCast()
                    + " scan=" + SectionCuller.lastScanMicros() + "us");
            lines.add("filter=" + SectionCuller.lastFilterMicros() + "us/" + SectionCuller.maxFilterMicros() + "us");
        }
        lines.add("room: " + (RoomScanner.isIndoor() ? "indoor" : "outdoor") + " air=" + RoomScanner.airCount() + " shell=" + RoomScanner.shellCount());
        lines.add("HUD: " + ClientHud.widgetCount() + " widget(s)  [点击左上胶囊切换本层]");
        return lines;
    }

    private static String format(Float value) {
        return value == null ? "-" : String.format("%.1f", value);
    }
}
