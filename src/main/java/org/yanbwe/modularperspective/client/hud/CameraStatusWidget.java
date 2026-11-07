package org.yanbwe.modularperspective.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.yanbwe.modularperspective.api.hud.Anchor;
import org.yanbwe.modularperspective.api.hud.HudContext;
import org.yanbwe.modularperspective.api.hud.HudLayout;
import org.yanbwe.modularperspective.api.hud.HudWidget;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * 内置状态挂件（docs/design.md §10.2）：左上角小胶囊，显示当前是否俯视。
 *
 * <p>{@code clickThrough=false}：点击它切换调试覆盖层，同时作为“可点击 HUD 控件”的验收样本——
 * 点击不应触发原版攻击/挖掘（§10.3）。
 */
public final class CameraStatusWidget implements HudWidget {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 18;
    private static final int BACKGROUND = 0x99202020;
    private static final int BORDER = 0xFF6A6A6A;
    private static final int TEXT_ACTIVE = 0xFF8CF08C;
    private static final int TEXT_IDLE = 0xFFD0D0D0;

    @Override
    public Anchor anchor() {
        return Anchor.TOP_LEFT;
    }

    @Override
    public int x() {
        return 4;
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
        return 100;
    }

    @Override
    public boolean clickThrough() {
        return false;
    }

    @Override
    public void render(HudContext ctx, GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        int x = HudLayout.x(anchor(), x(), width(), ctx.screenWidth());
        int y = HudLayout.y(anchor(), y(), height(), ctx.screenHeight());
        boolean ortho = ctx.state() != null && ctx.state().orthographic();
        String text = ortho ? "Perspective: 俯视" : "Perspective: 原版";
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, BACKGROUND);
        graphics.fill(x, y, x + WIDTH, y + 1, BORDER);
        graphics.fill(x, y + HEIGHT - 1, x + WIDTH, y + HEIGHT, BORDER);
        graphics.drawString(minecraft.font, text, x + 5, y + 5, ortho ? TEXT_ACTIVE : TEXT_IDLE, false);
    }

    @Override
    public boolean onMouseDown(HudContext ctx, int button) {
        if (button == 0) {
            ClientHud.toggleDebugOverlay();
            return true;
        }
        return false;
    }
}
