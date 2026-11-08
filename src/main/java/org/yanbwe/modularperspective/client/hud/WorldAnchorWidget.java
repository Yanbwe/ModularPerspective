package org.yanbwe.modularperspective.client.hud;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.api.hud.Anchor;
import org.yanbwe.modularperspective.api.hud.HudContext;
import org.yanbwe.modularperspective.api.hud.HudWidget;

/**
 * 世界锚定 HUD 示例（docs/design.md §10.4）：在本地玩家头顶投影一个小标记。
 *
 * <p>宿主通过 {@link #worldAnchor} 把世界坐标投影成 GUI 坐标，再加 {@code x()/y()} 偏移；
 * 只有正交俯视时投影可用（其它投影下 {@code worldAnchor} 返回 null，控件自行跳过渲染）。
 * 用途：验证“HUD 跟随世界物体而不是屏幕角落”的能力，也给第三方模组一个可复制的样板。
 */
public final class WorldAnchorWidget implements HudWidget {
    private static final int SIZE = 9;
    private static final int FILL = 0x50FFD040;
    private static final int BORDER = 0xFFFFD040;

    @Override
    public Anchor anchor() {
        return Anchor.TOP_LEFT;
    }

    @Override
    public int x() {
        return -SIZE / 2;
    }

    @Override
    public int y() {
        return -SIZE / 2;
    }

    @Override
    public int width() {
        return SIZE;
    }

    @Override
    public int height() {
        return SIZE;
    }

    @Override
    public int zOrder() {
        return 80;
    }

    @Override
    public boolean visible() {
        return Config.HUD_EXAMPLES.get();
    }

    @Override
    public @Nullable Vec3 worldAnchor(HudContext ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        return minecraft.player.getPosition(ctx.partialTick()).add(0.0D, 2.4D, 0.0D);
    }

    @Override
    public void render(HudContext ctx, GuiGraphics graphics, DeltaTracker deltaTracker) {
        double[] projected = ClientHud.guiPosition(worldAnchor(ctx), ctx);
        if (projected == null) {
            return;
        }
        int x = (int) Math.round(projected[0]) + x();
        int y = (int) Math.round(projected[1]) + y();
        graphics.fill(x, y, x + SIZE, y + SIZE, FILL);
        graphics.fill(x, y, x + SIZE, y + 1, BORDER);
        graphics.fill(x, y + SIZE - 1, x + SIZE, y + SIZE, BORDER);
        graphics.fill(x, y, x + 1, y + SIZE, BORDER);
        graphics.fill(x + SIZE - 1, y, x + SIZE, y + SIZE, BORDER);
    }
}
