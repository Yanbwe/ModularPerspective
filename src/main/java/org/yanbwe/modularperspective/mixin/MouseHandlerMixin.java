package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.yanbwe.modularperspective.client.input.CursorController;

/**
 * 伪解锁时锁定视角（docs/design.md §8.3）：光标是自由箭头，但 {@code MouseHandler#turnPlayer} 仍会被
 * 累积位移调用；直接取消，否则移动光标会同时旋转相机（B-01/B-08 的“光标与视角解耦”）。
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void modularperspective$cancelTurnPlayerWhileUnlocked(double sensitivity, CallbackInfo ci) {
        if (CursorController.isPseudoUnlocked()) {
            ci.cancel();
        }
    }
}
