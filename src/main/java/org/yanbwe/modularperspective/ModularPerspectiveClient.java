package org.yanbwe.modularperspective;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.File;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.client.input.CursorController;

/**
 * 客户端入口（仅客户端类加载）。
 *
 * <p>FML 4.0.42 里 {@code @EventBusSubscriber.bus} 已标废弃，所以 mod bus 监听器、配置界面扩展点
 * 都在主类构造器阶段注册；game bus 客户端事件仍在 {@link #init} 里注册（§3.3）。
 *
 * <ul>
 *   <li>M1：F6 切本地俯视预设（正交 + 第三人称背面 + pitch 45°）。</li>
 *   <li>M2：F4 总开关（正交俯视 + 伪解锁光标）；Home 吸附角度；Ctrl+滚轮缩放；Alt+滚轮旋转。</li>
 * </ul>
 *
 * <p>开发期自动验收：游戏目录存在 {@code poc1-auto.marker} 时做 M1 的“开→截图→关→截图”；
 * 存在 {@code m2-auto.marker} 时做 M2 的“解锁→移动光标→截图→关→截图”；
 * 存在 {@code move-auto.marker} 时做 WASD 移动测试（仅开发期，验收后删 marker）。
 */
public final class ModularPerspectiveClient {
    public static final String KEY_CATEGORY = "key.categories.modularperspective";
    public static final KeyMapping TOGGLE_TOP_DOWN = new KeyMapping("key.modularperspective.toggle_top_down",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, KEY_CATEGORY);
    public static final KeyMapping TOGGLE_PERSPECTIVE = new KeyMapping("key.modularperspective.toggle_perspective",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F4, KEY_CATEGORY);
    public static final KeyMapping SNAP_ANGLE = new KeyMapping("key.modularperspective.snap_angle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_HOME, KEY_CATEGORY);

    private static final String AUTO_MARKER_M1 = "poc1-auto.marker";
    private static final String AUTO_MARKER_M2 = "m2-auto.marker";
    private static final String AUTO_MARKER_MOVE = "move-auto.marker";
    private static final int AUTO_ENABLE_AT = 40;
    private static final int AUTO_MOVE_AT = 50;
    private static final int AUTO_SHOT_ON_AT = 80;
    private static final int AUTO_DISABLE_AT = 120;
    private static final int AUTO_SHOT_OFF_AT = 160;
    private static final int AUTO_MOVE_START_AT = 60;
    private static final int AUTO_MOVE_STOP_AT = 100;

    private static boolean autoModeM1;
    private static boolean autoModeM2;
    private static boolean autoModeMove;
    private static boolean autoModeChecked;
    private static double autoMoveStartX;
    private static double autoMoveStartZ;
    private static int autoTicks;

    private ModularPerspectiveClient() {}

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(ModularPerspectiveClient::onRegisterKeyMappings);
        modEventBus.addListener(ModularPerspectiveClient::onRegisterGuiLayers);
        modEventBus.addListener(ModularPerspectiveClient::onClientSetup);

        // 客户端配置界面（Mods 列表里点 Config 可编辑 §13 的客户端项）。
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // game bus：tick 消费按键 + 相机状态推进；FOV 通道走 ViewportEvent（§8.2，不 mixin getFov）。
        NeoForge.EVENT_BUS.addListener(ModularPerspectiveClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(ModularPerspectiveClient::onMouseButton);
        NeoForge.EVENT_BUS.addListener(ModularPerspectiveClient::onMouseScrolling);
        NeoForge.EVENT_BUS.addListener(ClientPerspective::onComputeFov);

        ModularPerspective.LOGGER.info("[{}] client entry loaded", ModularPerspective.MODID);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_TOP_DOWN);
        event.register(TOGGLE_PERSPECTIVE);
        event.register(SNAP_ANGLE);
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 伪解锁时隐藏原版准星（§8.3）：准星本身画在屏幕中心，与自由光标位置不符。
        event.wrapLayer(VanillaGuiLayers.CROSSHAIR, original -> (guiGraphics, deltaTracker) -> {
            if (!CursorController.isPseudoUnlocked()) {
                original.render(guiGraphics, deltaTracker);
            }
        });
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        ModularPerspective.LOGGER.info("[{}] client setup complete", ModularPerspective.MODID);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            ClientPerspective.reset();
            CursorController.reset();
            autoTicks = 0;
            return;
        }
        while (TOGGLE_TOP_DOWN.consumeClick()) {
            ClientPerspective.toggleTopDown();
        }
        while (TOGGLE_PERSPECTIVE.consumeClick()) {
            // F4 = 总开关（§9.1）：开 = 正交俯视 + 自由光标；关 = 完全回原版。
            ClientPerspective.setTopDown(!ClientPerspective.isTopDown());
        }
        while (SNAP_ANGLE.consumeClick()) {
            ClientPerspective.snapAngles();
        }
        ClientPerspective.tick(minecraft);
        CursorController.tick(minecraft);
        runAutoVerification(minecraft);
    }

    /**
     * 中键在伪解锁下被整体吞掉（§9.1、B-08）：拖拽用于转相机，<3px 的松手重放 pick block。
     */
    private static void onMouseButton(InputEvent.MouseButton.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean pressed = event.getAction() == InputConstants.PRESS;
        if (CursorController.onMouseButton(minecraft, event.getButton(), pressed)) {
            event.setCanceled(true);
        }
    }

    /**
     * 滚轮（§9.1）：Ctrl+滚轮缩放（distance 2..10），Alt+滚轮按 5° 步转相机；都不满足时
     * 不取消——保留原版快捷栏切换。事件在 game bus，且只在没有 Screen 时派发（§9.1）。
     */
    private static void onMouseScrolling(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientPerspective.isTopDown() || minecraft.screen != null) {
            return;
        }
        double delta = event.getScrollDeltaY();
        if (delta == 0.0D) {
            return;
        }
        if (Screen.hasControlDown()) {
            ClientPerspective.addDistance((float) delta);
            event.setCanceled(true);
        } else if (Screen.hasAltDown()) {
            ClientPerspective.addYawOffset((float) delta * 5.0F);
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ 开发期自动验收

    private static void runAutoVerification(Minecraft minecraft) {
        if (!autoModeChecked) {
            autoModeChecked = true;
            autoModeM1 = new File(minecraft.gameDirectory, AUTO_MARKER_M1).isFile();
            autoModeM2 = new File(minecraft.gameDirectory, AUTO_MARKER_M2).isFile();
            autoModeMove = new File(minecraft.gameDirectory, AUTO_MARKER_MOVE).isFile();
            if (autoModeM1 || autoModeM2 || autoModeMove) {
                ModularPerspective.LOGGER.info("[auto] verification mode: M1={} M2={} MOVE={}",
                        autoModeM1, autoModeM2, autoModeMove);
            }
        }
        if (!autoModeM1 && !autoModeM2 && !autoModeMove) {
            return;
        }
        autoTicks++;
        if (autoTicks == AUTO_ENABLE_AT) {
            ClientPerspective.setTopDown(true);
            if (autoModeM2) {
                CursorController.setUnlocked(true);
                moveCursor(minecraft, 0.32D, 0.50D);
            }
        } else if (autoTicks == AUTO_MOVE_START_AT && autoModeMove) {
            autoMoveStartX = minecraft.player.getX();
            autoMoveStartZ = minecraft.player.getZ();
            minecraft.options.keyUp.setDown(true);
            ModularPerspective.LOGGER.info("[auto] move test started at ({}, {}) screen={} forwardImpulse={}",
                    autoMoveStartX, autoMoveStartZ, minecraft.screen, minecraft.player.input.forwardImpulse);
        } else if (autoTicks == AUTO_MOVE_STOP_AT && autoModeMove) {
            minecraft.options.keyUp.setDown(false);
            double dx = minecraft.player.getX() - autoMoveStartX;
            double dz = minecraft.player.getZ() - autoMoveStartZ;
            ModularPerspective.LOGGER.info("[auto] move test finished: dx={} dz={} dist={} forwardImpulse={} screen={} focused={}",
                    dx, dz, Math.sqrt(dx * dx + dz * dz), minecraft.player.input.forwardImpulse,
                    minecraft.screen, minecraft.isWindowActive());
            grabScreenshot(minecraft, "move-after.png");
        } else if (autoTicks == AUTO_MOVE_AT) {
            if (autoModeM2) {
                moveCursor(minecraft, 0.30D, 0.50D);
            }
        } else if (autoTicks == AUTO_SHOT_ON_AT) {
            grabScreenshot(minecraft, autoModeM2 ? "m2-unlocked.png" : "poc1-on.png");
            logHitResult(minecraft, autoModeM2 ? "unlocked" : "m1-on");
        } else if (autoTicks == AUTO_DISABLE_AT) {
            if (autoModeM2) {
                CursorController.setUnlocked(false);
            } else {
                ClientPerspective.setTopDown(false);
            }
        } else if (autoTicks == AUTO_SHOT_OFF_AT) {
            grabScreenshot(minecraft, autoModeM2 ? "m2-locked.png" : "poc1-off.png");
            logHitResult(minecraft, autoModeM2 ? "locked" : "m1-off");
            ModularPerspective.LOGGER.info("[auto] verification finished (M1={}, M2={})", autoModeM1, autoModeM2);
        }
    }

    private static void moveCursor(Minecraft minecraft, double nx, double ny) {
        GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(),
                minecraft.getWindow().getScreenWidth() * nx, minecraft.getWindow().getScreenHeight() * ny);
    }

    private static void logHitResult(Minecraft minecraft, String label) {
        HitResult hit = minecraft.hitResult;
        ModularPerspective.LOGGER.info("[auto] {}: pseudoUnlocked={} hit={}", label,
                CursorController.isPseudoUnlocked(), hit == null ? "null" : hit.getType() + "@" + hit.getLocation());
    }

    private static void grabScreenshot(Minecraft minecraft, String name) {
        try {
            Screenshot.grab(minecraft.gameDirectory, name, minecraft.getMainRenderTarget(),
                    component -> ModularPerspective.LOGGER.info("[auto] screenshot {}: {}", name, component.getString()));
        } catch (Throwable throwable) {
            ModularPerspective.LOGGER.error("[auto] screenshot failed", throwable);
        }
    }
}
