package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code Minecraft#missTime} 是 protected 字段（§8.3 伪解锁后需要免掉“首次点击被忽略”的原版节流）。
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("missTime")
    void modularperspective$setMissTime(int missTime);
}
