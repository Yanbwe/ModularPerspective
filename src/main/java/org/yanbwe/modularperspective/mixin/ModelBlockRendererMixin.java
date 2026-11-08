package org.yanbwe.modularperspective.mixin;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.yanbwe.modularperspective.client.cull.CullingFaceFilter;

/**
 * 真实场景反馈 m07847 的“③ 面”修复：被剔除方块在 chunk mesh 里会以空气参与烘焙，
 * 于是它周围原本被遮挡的实心方块面会被重新生成（用户看到“内部面漏出来”）。
 * 这里在方块建模阶段拦下这类面：只要邻位是被剔除方块，就视为“原来就不该画”的面直接跳过。
 * 幽灵体积只负责补回原本可见的外表面，剖切面一律不画。
 *
 * <p>注意：NeoForge 给 ModelBlockRenderer 增加带 ModelData/RenderType 的 12 参重载，
 * 区块网格实际调用的是 12 参版本（10 参旧版本只是委托），所以必须显式写出 12 参描述符。
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {

    private static final String AO_12 = "tesselateWithAO("
            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/client/resources/model/BakedModel;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "ZLnet/minecraft/util/RandomSource;"
            + "JILnet/neoforged/neoforge/client/model/data/ModelData;"
            + "Lnet/minecraft/client/renderer/RenderType;)V";

    private static final String FLAT_12 = "tesselateWithoutAO("
            + "Lnet/minecraft/world/level/BlockAndTintGetter;"
            + "Lnet/minecraft/client/resources/model/BakedModel;"
            + "Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
            + "ZLnet/minecraft/util/RandomSource;"
            + "JILnet/neoforged/neoforge/client/model/data/ModelData;"
            + "Lnet/minecraft/client/renderer/RenderType;)V";

    @Redirect(
            method = {AO_12, FLAT_12},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(" +
                            "Lnet/minecraft/world/level/block/state/BlockState;" +
                            "Lnet/minecraft/world/level/BlockGetter;" +
                            "Lnet/minecraft/core/BlockPos;" +
                            "Lnet/minecraft/core/Direction;" +
                            "Lnet/minecraft/core/BlockPos;)Z"))
    private boolean modularperspective$hideFacesNextToCulled(BlockState state, BlockGetter level,
            BlockPos pos, Direction direction, BlockPos neighbor) {
        if (CullingFaceFilter.isCulled(neighbor)) {
            return false;
        }
        return Block.shouldRenderFace(state, level, pos, direction, neighbor);
    }
}
