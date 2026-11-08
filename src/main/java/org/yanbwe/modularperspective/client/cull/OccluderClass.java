package org.yanbwe.modularperspective.client.cull;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Occluder classification for the sightline tier (v0.3 parity, docs/design.md §11.2, §16 M6).
 *
 * <p>The prototype mod groups solid voxels hit by the camera→player rays into three classes before
 * flooding connected shapes: {@code TREE} shapes (logs/leaves) may touch diagonally and are allowed
 * a limited spread, whereas {@code BUILT} structures (planks, bricks) and {@code TERRAIN} only
 * connect face-to-face. The class also picks the flood connectivity, which keeps a tree canopy from
 * being glued to the wall it grows against.
 */
public enum OccluderClass {
    TREE,
    BUILT,
    TERRAIN;

    /** Six face neighbours, used by built structures and terrain. */
    static final net.minecraft.core.Direction[] FACE_NEIGHBOURS = new net.minecraft.core.Direction[] {
            net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN,
            net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
            net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST
    };

    /** Rock: the default for anything that is not clearly built or a plant. */
    public static OccluderClass classify(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.WART_BLOCKS)
                || state.is(BlockTags.BAMBOO_BLOCKS)) {
            return TREE;
        }
        if (state.is(BlockTags.PLANKS) || state.is(BlockTags.STONE_BRICKS)) {
            return BUILT;
        }
        return TERRAIN;
    }

    /** TREE blocks also connect diagonally (26 neighbours); the other classes are face-connected. */
    public boolean diagonalFlood() {
        return this == TREE;
    }
}
