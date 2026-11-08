package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.yanbwe.modularperspective.client.cull.BlockCuller;
import org.yanbwe.modularperspective.client.cull.RoomScanner;
import org.yanbwe.modularperspective.client.cull.SightlineScanner;

/**
 * v0.2 方块级剔除的渲染钩子（docs/design.md §11.2、§16 M6）：
 * 区块 mesh 构建期间，把 {@link BlockCuller} 标记的方块返回成空气，
 * 于是 baked model 不再为该方块生成面/顶点。只影响渲染，不改世界。
 */
@Mixin(RenderChunkRegion.class)
public class RenderChunkRegionMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void modularperspective$cullBlock(BlockPos blockPos, CallbackInfoReturnable<BlockState> cir) {
        int roomTest = RoomScanner.get().test(blockPos);
        if (roomTest == RoomScanner.CULL) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }
        if (roomTest == RoomScanner.KEEP) {
            return;
        }
        if (SightlineScanner.get().isCulled(blockPos)) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
            return;
        }
        if (BlockCuller.isCulled(blockPos)) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
