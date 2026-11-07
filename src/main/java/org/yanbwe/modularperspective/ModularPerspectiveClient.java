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
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.client.PerspectiveClientNetwork;
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
    private static final String AUTO_MARKER_M3 = "m3-auto.marker";
    private static final String AUTO_MARKER_PERSIST = "m3-persist.marker";
    private static final int AUTO_ENABLE_AT = 40;
    private static final int AUTO_MOVE_AT = 50;
    private static final int AUTO_SHOT_ON_AT = 80;
    private static final int AUTO_DISABLE_AT = 120;
    private static final int AUTO_SHOT_OFF_AT = 160;
    private static final int AUTO_MOVE_START_AT = 60;
    private static final int AUTO_MOVE_STOP_AT = 100;

    // 开发期自动验收（marker 存在才启用；验收后删 marker）。
    private static boolean autoModeM1;
    private static boolean autoModeM2;
    private static boolean autoModeMove;
    private static boolean autoModeM3;
    private static boolean autoModePersist;
    private static boolean autoModeChecked;
    private static double autoMoveStartX;
    private static double autoMoveStartZ;
    private static int autoTicks;
    /** M3 自动验收的阶段机：服务端可能因失焦暂停而延迟发包，按“状态到达”推进而不是固定 tick。 */
    private static int autoPhase;
    private static int autoPhaseTick;

    private ModularPerspectiveClient() {}

    public static void init(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(ModularPerspectiveClient::onRegisterKeyMappings);
        modEventBus.addListener(ModularPerspectiveClient::onRegisterGuiLayers);
        modEventBus.addListener(ModularPerspectiveClient::onClientSetup);
        modEventBus.addListener(PerspectiveClientNetwork::register);

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
            event.setCanceled(true);
            // §5.4：ZOOM 被 REQUIRE 锁住时拦截；SUGGEST 锁由玩家操作顶掉。
            if (!ClientPerspective.consumeLockedInput(InputLock.ZOOM)) {
                ClientPerspective.addDistance((float) delta);
            }
        } else if (Screen.hasAltDown()) {
            event.setCanceled(true);
            if (!ClientPerspective.consumeLockedInput(InputLock.ROTATE)) {
                ClientPerspective.addYawOffset((float) delta * 5.0F);
            }
        }
    }

    // ------------------------------------------------------------------ 开发期自动验收

    private static void runAutoVerification(Minecraft minecraft) {
        if (!autoModeChecked) {
            autoModeChecked = true;
            autoModeM1 = new File(minecraft.gameDirectory, AUTO_MARKER_M1).isFile();
            autoModeM2 = new File(minecraft.gameDirectory, AUTO_MARKER_M2).isFile();
            autoModeMove = new File(minecraft.gameDirectory, AUTO_MARKER_MOVE).isFile();
            autoModeM3 = new File(minecraft.gameDirectory, AUTO_MARKER_M3).isFile();
            autoModePersist = new File(minecraft.gameDirectory, AUTO_MARKER_PERSIST).isFile();
            if (autoModeM1 || autoModeM2 || autoModeMove || autoModeM3 || autoModePersist) {
                ModularPerspective.LOGGER.info("[auto] verification mode: M1={} M2={} MOVE={} M3={} PERSIST={}",
                        autoModeM1, autoModeM2, autoModeMove, autoModeM3, autoModePersist);
            }
        }
        if (!autoModeM1 && !autoModeM2 && !autoModeMove && !autoModeM3 && !autoModePersist) {
            return;
        }
        autoTicks++;
        if (autoModePersist) {
            runPersistVerification(minecraft);
            return;
        }
        if (autoModeM3) {
            runM3Verification(minecraft);
            return;
        }
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

    /**
     * M3 双端链自动验收（marker {@code m3-auto.marker}）：用真实命令跑
     * {@code /perspective camera @s 0 45 duration 200} → 服务端下发快照 → 客户端应用（正交生效）；
     * 再 {@code /perspective clear @s} → 快照清空（回到原版视角），各阶段截图。
     */
    private static void runM3Verification(Minecraft minecraft) {
        if (autoTicks < AUTO_ENABLE_AT) {
            return;
        }
        if (autoPhase == 0) {
            ClientPerspective.setTopDown(false);
            runServerCommand(minecraft, "perspective preset @s top-down 200");
            autoPhase = 1;
            autoPhaseTick = autoTicks;
            ModularPerspective.LOGGER.info("[auto] M3 phase1: preset command sent");
            return;
        }

        ResolvedCameraState state = ClientPerspective.current();
        switch (autoPhase) {
            case 1 -> {
                // 等快照真正生效（服务器失焦暂停时可能延迟），再截图并发 requiresAck 指令。
                if (state != null && state.orthographic()) {
                    runServerCommand(minecraft, "perspective camera @s 0 45 100");
                    autoPhase = 22;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase2: preset applied, ack camera sent; locks={}",
                            state.locks());
                }
            }
            case 22 -> {
                // 帧缓冲要等渲染一帧后才反映新相机，隔几 tick 再截图（否则截到上一帧）。
                if (autoTicks - autoPhaseTick >= 5) {
                    grabScreenshot(minecraft, "m3-preset.png");
                    autoPhase = 2;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase2b: preset screenshot taken");
                }
            }
            case 2 -> {
                // 等预设的 SUGGEST ROTATE 锁位出现 → 模拟玩家顶掉，应上报 Overridden（§5.3）。
                if (state != null && state.locks().contains(InputLock.ROTATE)) {
                    boolean blocked = ClientPerspective.consumeLockedInput(InputLock.ROTATE);
                    autoPhase = 3;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase3: override ROTATE blocked={} locks={}",
                            blocked, ClientPerspective.current() == null ? "null" : ClientPerspective.current().locks());
                } else if (autoTicks - autoPhaseTick > 200) {
                    autoPhase = 3;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.warn("[auto] M3 phase3: no ROTATE lock observed, skipping override test");
                }
            }
            case 3 -> {
                if (autoTicks - autoPhaseTick >= 10) {
                    runServerCommand(minecraft, "perspective policy @s allowSuggest off");
                    autoPhase = 4;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase4: policy off sent");
                }
            }
            case 4 -> {
                if (autoTicks - autoPhaseTick >= 10) {
                    // 策略禁止 SUGGEST 后 push 应失败（命令层 sendFailure，聊天栏 "SUGGEST is not allowed"）。
                    runServerCommand(minecraft, "perspective camera @s 0 45 20");
                    autoPhase = 5;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase5: policy rejection test sent");
                }
            }
            case 5 -> {
                if (autoTicks - autoPhaseTick >= 10) {
                    runServerCommand(minecraft, "perspective policy @s allowSuggest on");
                    autoPhase = 6;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase6: policy on sent");
                }
            }
            case 6 -> {
                if (autoTicks - autoPhaseTick >= 10) {
                    runServerCommand(minecraft, "perspective clear @s");
                    autoPhase = 7;
                    autoPhaseTick = autoTicks;
                    ModularPerspective.LOGGER.info("[auto] M3 phase7: clear sent");
                }
            }
            case 7 -> {
                if (autoTicks - autoPhaseTick >= 20) {
                    grabScreenshot(minecraft, "m3-cleared.png");
                    autoPhase = 8;
                    ModularPerspective.LOGGER.info("[auto] M3 verification finished (topDown={} ortho={})",
                            ClientPerspective.isTopDown(), ClientPerspective.isOrtho());
                }
            }
            default -> {
                // 完成：不再动作。
            }
        }
    }

    /**
     * 持久化验收（marker {@code m3-persist.marker}）：推一条长指令 → saveEverything → stopServer，
     * 退出后检查 playerdata 里的 attachment 是否落盘。
     */
    private static void runPersistVerification(Minecraft minecraft) {
        if (autoTicks == AUTO_ENABLE_AT) {
            runServerCommand(minecraft, "perspective camera @s 0 45 2400");
            ModularPerspective.LOGGER.info("[auto] M3 persist: camera command sent");
        } else if (autoTicks == AUTO_SHOT_ON_AT) {
            net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
            if (server != null) {
                boolean saved = server.saveEverything(true, true, true);
                ModularPerspective.LOGGER.info("[auto] M3 persist: saveEverything={}", saved);
            }
        } else if (autoTicks == AUTO_DISABLE_AT) {
            // 不在 render thread 上调 stopServer()：会从 ServerChunkCache.tick 里重入停止，
            // vanilla 的 chunk tracker 会抛 ArrayIndexOutOfBoundsException（已实测，非本模组逻辑）。
            ModularPerspective.LOGGER.info("[auto] M3 persist: saved, manual kill expected");
        }
    }

    /** 以 OP 权限在集成服务器上执行命令（仅开发期自动验收用；权限 4 绕过单人作弊开关）。 */
    private static void runServerCommand(Minecraft minecraft, String command) {
        net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            ModularPerspective.LOGGER.warn("[auto] M3 no integrated server/player; skipped: {}", command);
            return;
        }
        net.minecraft.server.level.ServerPlayer player =
                server.getPlayerList().getPlayer(minecraft.player.getUUID());
        if (player == null) {
            ModularPerspective.LOGGER.warn("[auto] M3 server player missing; skipped: {}", command);
            return;
        }
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4), command);
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
