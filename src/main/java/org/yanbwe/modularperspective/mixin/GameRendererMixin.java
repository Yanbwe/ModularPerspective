package org.yanbwe.modularperspective.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.client.input.CursorController;

/**
 * 正交渲染链注入点（docs/design.md §8.1、§8.2）。
 *
 * <ul>
 *   <li>{@code getProjectionMatrix}：PROJECTION 赢家为 {@code ORTHOGRAPHIC} 时替换为正交（@RETURN）。</li>
 *   <li>{@code resetProjectionMatrix}：用自定义 {@code VertexSorting}（按视线深度）替换 vanilla 的
 *       {@code DISTANCE_TO_ORIGIN}，再取消原调用（@HEAD，§8.2 第二层）。</li>
 *   <li>{@code renderItemInHand}：内部会重设投影矩阵且第三人称原版也不画手，正交下直接取消（A-11）。</li>
 *   <li>{@code bobHurt}/{@code bobView}：正交下取消，否则 bob 会右乘到正交矩阵上（A-04）。</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true)
    private void modularperspective$orthoProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        ResolvedCameraState state = ClientPerspective.current();
        if (state != null && state.orthographic()) {
            cir.setReturnValue(ClientPerspective.projectionMatrix(state));
        }
    }

    @Inject(method = "resetProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void modularperspective$viewSorting(Matrix4f matrix, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            RenderSystem.setProjectionMatrix(matrix, ClientPerspective.viewSorting());
            ci.cancel();
        }
    }

    /**
     * 伪解锁光标：{@code GameRenderer#pick(float)} 会用屏幕中心射线覆盖 {@code hitResult}；
     * 在 @RETURN 之后换成本地光标射线（正交专用，§8.3）。
     */
    @Inject(method = "pick", at = @At("RETURN"))
    private void modularperspective(float partialTick, CallbackInfo ci) {
        CursorController.applyCursorPick(Minecraft.getInstance());
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noHandInOrtho(Camera camera, float partialTick, Matrix4f matrix, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noBobHurtInOrtho(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noBobViewInOrtho(PoseStack poseStack, float partialTick, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }
}
