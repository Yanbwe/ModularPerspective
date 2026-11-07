package org.yanbwe.modularperspective.api.hud;

import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;

/**
 * 每帧传给 HUD 控件的只读上下文（docs/design.md §10.2）。
 *
 * @param partialTick 帧插值系数（0..1）
 * @param screenWidth GUI 缩放后的屏幕宽
 * @param screenHeight GUI 缩放后的屏幕高
 * @param state 当前帧的相机状态快照（可能为 null，表示没有相机指令）
 * @param directives 当前客户端仲裁栈的只读快照（本地预设 + 服务端指令）
 * @param occludedByScreen 当前帧是否被任意 Screen 遮挡（为 true 时宿主不渲染、不路由鼠标）
 */
public record HudContext(
        float partialTick,
        int screenWidth,
        int screenHeight,
        @Nullable ResolvedCameraState state,
        List<CameraDirective> directives,
        boolean occludedByScreen) {
}
