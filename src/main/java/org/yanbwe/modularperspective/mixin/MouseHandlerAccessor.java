package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 伪解锁专用：{@code MouseHandler#mouseGrabbed} 是私有字段，伪解锁后需要把光标恢复成 GLFW 普通箭头，
 * 但游戏逻辑仍要认为“鼠标已被抓取”（否则 {@code startAttack}/{@code continueAttack} 不工作）。
 * 见 docs/design.md §8.3。
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("mouseGrabbed")
    void modularperspective$setMouseGrabbed(boolean grabbed);
}
