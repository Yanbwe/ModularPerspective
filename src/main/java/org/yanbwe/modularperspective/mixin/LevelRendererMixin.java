package org.yanbwe.modularperspective.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.client.cull.SectionCuller;

/**
 * 正交渲染链注入点（docs/design.md §8.1、§11.3、§11.4）。
 *
 * <ul>
 *   <li>{@code offsetFrustum}：<b>必须</b>在首次 {@code applyFrustum} 之前短路。
 *       {@code offsetToFullyIncludeCameraCube(8)} 的退出条件是“相机立方体完全进入视锥”，
 *       正交默认参数下实测 3,000,001 轮不退出（A-15、§18.2 #4）。</li>
 *   <li>{@code renderSky} / {@code renderClouds}：天空盘、星星、云都是固定尺寸几何体，
 *       正交下会随距离缩放失真，v0.1 直接跳过（§8.2，Iris 兼容待实机验证）。</li>
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

    /**
     * v0.1 section 级剔除（docs/design.md §11.3）：渲染 section 前过滤 {@code visibleSections}。
     * 不直接改写 LevelRenderer 的列表（它由 applyFrustum 每帧重建）；只替换本次迭代的迭代器。
     * 保持 opaque 正序 / translucent 倒序（原调用用 index 区分方向）。
     */
    @Redirect(method = "renderSectionLayer",
              at = @At(value = "INVOKE",
                       target = "Lit/unimi/dsi/fastutil/objects/ObjectArrayList;listIterator(I)Lit/unimi/dsi/fastutil/objects/ObjectListIterator;"))
    private ObjectListIterator<SectionRenderDispatcher.RenderSection> modularperspective$filterHiddenSections(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> sections, int index) {
        if (!SectionCuller.isCulling() || SectionCuller.hiddenCount() == 0) {
            return sections.listIterator(index);
        }
        ObjectArrayList<SectionRenderDispatcher.RenderSection> filtered = new ObjectArrayList<>(sections.size());
        for (int i = 0; i < sections.size(); i++) {
            SectionRenderDispatcher.RenderSection section = sections.get(i);
            if (!SectionCuller.isHidden(section.getOrigin())) {
                filtered.add(section);
            }
        }
        return filtered.listIterator(index == 0 ? 0 : filtered.size());
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void modularperspective$noCloudsInOrtho(PoseStack poseStack, Matrix4f matrix, Matrix4f projection,
                                                    float partialTick, double x, double y, double z, CallbackInfo ci) {
        if (ClientPerspective.isOrtho()) {
            ci.cancel();
        }
    }
}
