package org.yanbwe.modularperspective.client.cull;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SightlineVolumeTest {

    private static final double EPS = 1.0E-6D;

    @Test
    void pointOnSegmentHasZeroDistance() {
        double[] t = new double[1];
        double distance = SightlineVolume.perpendicularDistance(0.0D, 1.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 2.0D, 0.0D, t);
        assertEquals(0.0D, distance, EPS);
        assertEquals(0.5D, t[0], EPS);
    }

    @Test
    void distanceUsesClosestPointInsideSegment() {
        double[] t = new double[1];
        double distance = SightlineVolume.perpendicularDistance(3.0D, 1.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 2.0D, 0.0D, t);
        assertEquals(3.0D, distance, EPS);
        assertEquals(0.5D, t[0], EPS);
    }

    @Test
    void distanceClampsBeyondSegmentEnds() {
        double[] t = new double[1];
        double distance = SightlineVolume.perpendicularDistance(0.0D, 5.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 2.0D, 0.0D, t);
        assertEquals(3.0D, distance, EPS);
        assertEquals(1.0D, t[0], EPS);
    }

    @Test
    void envelopeFadesAtBothEnds() {
        assertEquals(0.0D, SightlineVolume.envelope(0.0D), EPS);
        assertEquals(0.0D, SightlineVolume.envelope(1.0D), EPS);
        assertEquals(1.0D, SightlineVolume.envelope(0.5D), EPS);
    }

    @Test
    void alphaIsZeroInsideInnerRadius() {
        assertEquals(0.0D, SightlineVolume.alpha(2.0D, 0.5D, 3.0D, 8.0D, 0.45D), EPS);
    }

    @Test
    void alphaRisesQuicklyPastInnerRadius() {
        double mid = SightlineVolume.alpha(5.5D, 0.5D, 3.0D, 8.0D, 0.45D);
        assertTrue(mid > 0.45D * 0.5D, "半程不透明度应快速上升，实测=" + mid);
        assertEquals(0.45D, SightlineVolume.alpha(8.0D, 0.5D, 3.0D, 8.0D, 0.45D), 1.0E-6D);
    }

    @Test
    void alphaIsZeroNearSegmentEnds() {
        assertEquals(0.0D, SightlineVolume.alpha(8.0D, 0.001D, 3.0D, 8.0D, 0.45D), EPS);
        assertEquals(0.0D, SightlineVolume.alpha(8.0D, 0.999D, 3.0D, 8.0D, 0.45D), EPS);
    }
}
