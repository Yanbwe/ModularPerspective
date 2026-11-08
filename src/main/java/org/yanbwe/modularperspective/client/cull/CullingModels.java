package org.yanbwe.modularperspective.client.cull;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 对齐原型 {@code NeoForgeTerrainCullingModels}：用 NeoForge 原生 ModelData 做位置相关的模型剔除。
 *
 * <p>区块网格走 {@code RenderChunkRegion} 注入即可；这里覆盖 NeoForge/模组模型路径（也覆盖 Sodium 的
 * 模型烘焙路径），保证同一个方块在两条管线里都消失。
 */
public final class CullingModels {

    private CullingModels() {
    }

    public static void modifyModels(ModelEvent.ModifyBakingResult event) {
        event.getModels().replaceAll((id, model) -> model instanceof CullingModel ? model : new CullingModel(model));
    }

    private static final class CullingModel extends BakedModelWrapper<BakedModel> {
        private static final ModelProperty<Boolean> CULLED = new ModelProperty<>();

        private CullingModel(BakedModel originalModel) {
            super(originalModel);
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state,
                                      ModelData modelData) {
            ModelData delegated = super.getModelData(level, pos, state, modelData);
            try {
                if (TerrainCulling.shouldRemove(state, pos.getX(), pos.getY(), pos.getZ())) {
                    return delegated.derive().with(CULLED, Boolean.TRUE).build();
                }
            } catch (Exception ignored) {
            }
            return delegated;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                        RandomSource random, ModelData modelData,
                                        @Nullable RenderType renderType) {
            if (Boolean.TRUE.equals(modelData.get(CULLED))) {
                return List.of();
            }
            return super.getQuads(state, side, random, modelData, renderType);
        }
    }
}
