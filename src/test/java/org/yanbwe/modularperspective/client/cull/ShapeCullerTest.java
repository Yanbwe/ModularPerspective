package org.yanbwe.modularperspective.client.cull;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link ShapeCuller} 的纯几何单测（不依赖 Minecraft 类）。 */
class ShapeCullerTest {
    private static final double FEET_X = 0.0D;
    private static final double FEET_Y = 60.0D;
    private static final double FEET_Z = 0.0D;
    private static final double CAMERA_X = 0.0D;
    private static final double CAMERA_Y = 66.0D;
    private static final double CAMERA_Z = -6.0D;
    private static final double RADIUS = 6.0D;
    private static final double CONE = 45.0D;

    private static boolean cull(double x, double y, double z, double radius, double cone) {
        return ShapeCuller.shouldCull(x, y, z, FEET_X, FEET_Y, FEET_Z,
                CAMERA_X, CAMERA_Y, CAMERA_Z, radius, cone);
    }

    @Test
    void blockOnPlayerCameraAxisIsCulled() {
        assertTrue(cull(0.0D, 63.0D, -3.0D, RADIUS, CONE));
    }

    @Test
    void blockNearFeetIsNotCulled() {
        assertFalse(cull(0.0D, 60.5D, -3.0D, RADIUS, CONE));
    }

    @Test
    void blockFarToTheSideIsNotCulled() {
        assertFalse(cull(10.0D, 63.0D, 0.0D, RADIUS, CONE));
    }

    @Test
    void blockCloseToCameraIsAlwaysCulled() {
        assertTrue(cull(0.0D, 65.5D, -5.5D, RADIUS, CONE));
    }

    @Test
    void blockBehindTheAxisIsNotCulled() {
        assertFalse(cull(0.0D, 63.0D, 8.0D, RADIUS, CONE));
    }

    @Test
    void blockOutsideNarrowConeIsNotCulled() {
        assertFalse(cull(2.0D, 63.0D, 0.0D, 1000.0D, 10.0D));
    }

    @Test
    void blockOnAxisInsideNarrowConeIsCulled() {
        assertTrue(cull(0.0D, 63.0D, -3.0D, 1000.0D, 10.0D));
    }
}
