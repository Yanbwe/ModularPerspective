package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;

/**
 * WASD 相对屏幕（docs/design.md §9.2）与 INPUT_LOCK.MOVE（§5.3、§9.2）。
 *
 * <p>原版在 {@code Entity#getInputVector} 里按玩家 yaw 旋转 (leftImpulse, forwardImpulse)。
 * 俯视相机 yaw = 玩家 yaw + {@link ClientPerspective#yawOffsetDegrees()}（中键拖动产生的偏移），
 * 所以这里先按该偏移预旋转输入，抵消原版旋转后净效果就是"相对相机/屏幕"。
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void modularperspective$screenRelativeMovement(boolean movingSlowly, float sneakingSpeed, CallbackInfo ci) {
        Input input = (Input) (Object) this;
        if (ClientPerspective.isTopDown()) {
            float offset = ClientPerspective.yawOffsetDegrees();
            if (offset != 0.0F) {
                float sin = Mth.sin(offset * Mth.DEG_TO_RAD);
                float cos = Mth.cos(offset * Mth.DEG_TO_RAD);
                float left = input.leftImpulse;
                float forward = input.forwardImpulse;
                input.leftImpulse = left * cos - forward * sin;
                input.forwardImpulse = forward * cos + left * sin;
            }
            // §9.2：MOVE 锁只清水平输入；跳跃/潜行不锁（服务端同步会分裂）。
            if (ClientPerspective.isInputLocked(InputLock.MOVE)) {
                input.leftImpulse = 0.0F;
                input.forwardImpulse = 0.0F;
            }
        }
    }
}
