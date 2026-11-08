package org.yanbwe.modularperspective.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * 正交渲染链注入点（docs/design.md §8.1、§8.2）。
 *
 * <ul>
 *   <li>{@code offsetFrustum}：<b>必须</b>在首次 {@code applyFrustum} 之前短路。
 *       {@code offsetToFullyIncludeCameraCube(8)} 的退出条件是“相机立方体完全进入视锥”，
 *       正交默认参数下实测 3,000,001 轮不退出（A-15、§18.2 #4）。
 *       2026-11-08 原型剔除方案重写时曾误删本 mixin，导致地形加载直接卡死在此循环。</li>
 *   <li>{@code renderSky} / {@code renderClouds}：天空盘、星星、云都是固定尺寸几何体，
 *       正交下会随距离缩放失真，直接跳过。</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "offsetFrustum", at = @At("HEAD"), cancellable = true)
    private static void modularperspective$skipOffsetFrustumInOrtho(Frustum frustum,
                                                                    CallbackInfoReturnable<Frustum> cir) {
        if (ClientPerspective.isOrtho()) {
            cir.setReturnValue(frustum);
        }
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noSkyInOrtho(Matrix4f matrix, Matrix4f projection, float partialTick, Camera camera,
                                                 boolean thickFog, Runnable runnable, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noCloudsInOrtho(PoseStack poseStack, Matrix4f matrix, Matrix4f projection,
                                                    float partialTick, double x, double y, double z, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }
}
