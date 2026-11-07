package org.yanbwe.modularperspective.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.mixin.MinecraftAccessor;
import org.yanbwe.modularperspective.mixin.MouseHandlerAccessor;

/**
 * 伪解锁光标（docs/design.md §8.3、B-01/B-08 的落地）。
 *
 * <p>核心事实：原版 {@code MouseHandler#grabMouse} 有 {@code if (!mouseGrabbed)} 守卫（MouseHandler:320），
 * 而 {@code setScreen(null)} 又会回头调它，所以“关掉再抓取”不能用原版路径直接做。这里改成显式状态机：
 * <ol>
 *   <li>解锁：先 {@code releaseMouse()} 让 GLFW 光标恢复普通箭头，再把 {@code mouseGrabbed} 置回 true
 *       ——这样 {@code onPress} 仍走游戏输入分支（KeyMapping.set/click），相机旋转由 {@code turnPlayer} 取消；</li>
 *   <li>锁定：先把 {@code mouseGrabbed} 置 false 绕过守卫，再走原版 {@code grabMouse()}（恢复隐藏光标/居中/missTime）。</li>
 * </ol>
 * 光标位置 → 世界射线在 {@link #applyCursorPick(Minecraft)}（由 {@code GameRenderer#pick} @RETURN 调用），
 * 复用 {@code hitResult} 让原版挖/放/使用逻辑直接作用在光标处的方块上。
 */
public final class CursorController {
    private static final double PICK_DISTANCE = 512.0D;
    private static final double DRAG_CLICK_THRESHOLD = 3.0D;
    /** 中键拖拽旋转相机的灵敏度：度/像素。 */
    private static final float DRAG_SENSITIVITY = 0.25F;

    private static boolean unlocked;
    private static boolean applied;
    private static boolean dragging;
    private static double dragStartX;
    private static double dragStartY;
    private static double dragLastX;
    private static double dragLastY;

    private CursorController() {}

    public static boolean isPseudoUnlocked() {
        return unlocked;
    }

    public static void toggle() {
        setUnlocked(!unlocked);
    }

    public static void setUnlocked(boolean value) {
        if (unlocked == value) {
            return;
        }
        unlocked = value;
        dragging = false;
        if (!value) {
            restore();
        }
        ModularPerspective.LOGGER.info("[perspective] pseudo-unlock {}", value ? "ENABLED" : "DISABLED");
    }

    /** 退出世界/切维度时调用：恢复原版鼠标状态。 */
    public static void reset() {
        unlocked = false;
        dragging = false;
        restore();
    }

    /** 客户端 tick：保持伪解锁状态、驱动中键拖拽旋转。 */
    public static void tick(Minecraft minecraft) {
        if (unlocked && minecraft.level != null && minecraft.screen == null) {
            if (!applied) {
                applyPseudoUnlock(minecraft);
                applied = true;
            } else if (dragging) {
                double deltaX = minecraft.mouseHandler.xpos() - dragLastX;
                double deltaY = minecraft.mouseHandler.ypos() - dragLastY;
                dragLastX = minecraft.mouseHandler.xpos();
                dragLastY = minecraft.mouseHandler.ypos();
                ClientPerspective.addYawOffset((float) (-deltaX * DRAG_SENSITIVITY));
                ClientPerspective.addPitchOffset((float) (deltaY * DRAG_SENSITIVITY));
            }
        } else if (minecraft.screen != null) {
            // 开 Screen 时原版会把光标交还给 GUI；关闭 Screen 后由 tick 重新套用伪解锁。
            applied = false;
            dragging = false;
        }
    }

    /**
     * 中键输入（§9.1、B-08）：伪解锁下整体吞掉中键；按下进入拖拽，松手时位移 &lt; 3px 则重放 pick block。
     *
     * @return true 表示事件已被消费（调用方应取消原版处理）
     */
    public static boolean onMouseButton(Minecraft minecraft, int button, boolean pressed) {
        if (!unlocked || button != GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            return false;
        }
        if (pressed) {
            dragging = true;
            dragStartX = minecraft.mouseHandler.xpos();
            dragStartY = minecraft.mouseHandler.ypos();
            dragLastX = dragStartX;
            dragLastY = dragStartY;
        } else if (dragging) {
            dragging = false;
            double dx = minecraft.mouseHandler.xpos() - dragStartX;
            double dy = minecraft.mouseHandler.ypos() - dragStartY;
            if (dx * dx + dy * dy < DRAG_CLICK_THRESHOLD * DRAG_CLICK_THRESHOLD) {
                // 重放原版 pick block（KeyMapping.click 会让下一 tick 的 handleKeybinds 消费）。
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_MIDDLE));
            }
        }
        return true;
    }

    /**
     * 用光标屏幕位置做世界射线拾取（正交专用；透视回退原版中心射线）。
     *
     * <p>正交下所有射线平行于相机视线，屏幕位置只决定射线原点在相机平面内的偏移：
     * {@code origin = camPos + right * nx * halfW + up * ny * halfH + forward * near}。
     */
    public static void applyCursorPick(Minecraft minecraft) {
        if (!unlocked || minecraft.level == null || minecraft.player == null || minecraft.screen != null) {
            return;
        }
        ResolvedCameraState state = ClientPerspective.current();
        if (state == null || !state.orthographic()) {
            return;
        }

        Window window = minecraft.getWindow();
        double nx = 2.0D * minecraft.mouseHandler.xpos() / window.getWidth() - 1.0D;
        double ny = 1.0D - 2.0D * minecraft.mouseHandler.ypos() / window.getHeight();
        float halfHeight = state.distance() != null ? state.distance() : ClientPerspective.DEFAULT_TOP_DOWN_DISTANCE;
        float halfWidth = (float) ((double) window.getWidth() / window.getHeight() * halfHeight);

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Quaternionf rotation = camera.rotation();
        Vector3f right = rotation.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = rotation.transform(new Vector3f(0.0F, 1.0F, 0.0F));
        Vector3f forward = camera.getLookVector();

        Vec3 cameraPosition = camera.getPosition();
        double ox = cameraPosition.x + right.x() * nx * halfWidth + up.x() * ny * halfHeight
                + forward.x() * ClientPerspective.NEAR_PLANE;
        double oy = cameraPosition.y + right.y() * nx * halfWidth + up.y() * ny * halfHeight
                + forward.y() * ClientPerspective.NEAR_PLANE;
        double oz = cameraPosition.z + right.z() * nx * halfWidth + up.z() * ny * halfHeight
                + forward.z() * ClientPerspective.NEAR_PLANE;
        Vec3 from = new Vec3(ox, oy, oz);
        Vec3 to = from.add(forward.x() * PICK_DISTANCE, forward.y() * PICK_DISTANCE, forward.z() * PICK_DISTANCE);

        BlockHitResult hit = minecraft.level.clip(
                new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
        minecraft.hitResult = hit;
        minecraft.crosshairPickEntity = null;
        if (hit.getType() == HitResult.Type.MISS) {
            minecraft.hitResult = BlockHitResult.miss(to, Direction.UP, BlockPos.containing(to));
        }
    }

    private static void applyPseudoUnlock(Minecraft minecraft) {
        MouseHandler mouseHandler = minecraft.mouseHandler;
        mouseHandler.releaseMouse();
        ((MouseHandlerAccessor) mouseHandler).modularperspective$setMouseGrabbed(true);
        mouseHandler.setIgnoreFirstMove();
        ((MinecraftAccessor) minecraft).modularperspective$setMissTime(10_000);

        Window window = minecraft.getWindow();
        GLFW.glfwSetCursorPos(window.getWindow(), window.getScreenWidth() / 2.0D, window.getScreenHeight() / 2.0D);
    }

    private static void restore() {
        applied = false;
        dragging = false;
        Minecraft minecraft = Minecraft.getInstance();
        MouseHandler mouseHandler = minecraft.mouseHandler;
        ((MouseHandlerAccessor) mouseHandler).modularperspective$setMouseGrabbed(false);
        mouseHandler.grabMouse();
    }
}
