package org.yanbwe.modularperspective.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.yanbwe.modularperspective.client.cull.TerrainCulling;

/**
 * 对齐原型 {@code ChunkRendererRegionMixin}：网格构建时把被剔除方块当作空气读出来。
 *
 * <p>返回 {@code Blocks.AIR} 会连带清掉流体、方块实体、邻居面、平滑光照与可见性图闭包，因此不需要
 * 再单独注入“面是否渲染”。{@link TerrainCulling#idle()} 先做一次便宜短路，避免每次读方块都走两个
 * 扫描器哈希表。
 */
@Mixin(RenderChunkRegion.class)
public abstract class RenderChunkRegionMixin {

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void modularperspective$cull(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (TerrainCulling.idle()) {
            return;
        }
        BlockState state = cir.getReturnValue();
        if (state == null || state.isAir()) {
            return;
        }
        if (TerrainCulling.shouldRemove(state, pos.getX(), pos.getY(), pos.getZ())) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
