package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * 相机落位注入点（docs/design.md §8.1、§8.2）：{@code Camera#setup} 结束时套用帧首仲裁结果。
 *
 * <p>只覆盖有赢家的通道：ANGLE 赢家 → 3 参 {@code setRotation(yaw, pitch, 0)}（2 参已废弃且会清 roll，§4.1）；
 * POSITION 赢家 → {@code setPosition}。位置/朝向都来自 {@link ClientPerspective#current()}，
 * 不在 mixin 内自行仲裁（§5.6）。
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch, float roll);

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Inject(method = "setup", at = @At("RETURN"))
    private void modularperspective$applyResolvedState(BlockGetter level, Entity entity, boolean detached,
                                                       boolean mirrored, float partialTick, CallbackInfo ci) {
        ResolvedCameraState state = ClientPerspective.frameState(partialTick);
        if (state == null) {
            return;
        }
        if (state.hasAngle()) {
            float yaw = state.yaw() != null ? state.yaw()
                    : (entity != null ? entity.getViewYRot(partialTick) : 0.0F);
            float pitch = state.pitch() != null ? state.pitch()
                    : (entity != null ? entity.getViewXRot(partialTick) : 0.0F);
            this.setRotation(yaw, pitch, 0.0F);
        }
        if (state.hasPosition()) {
            org.yanbwe.modularperspective.api.camera.Position3 position = state.position();
            this.setPosition(new Vec3(position.x(), position.y(), position.z()));
        }
    }
}
