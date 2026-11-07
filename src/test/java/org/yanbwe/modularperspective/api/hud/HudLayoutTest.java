package org.yanbwe.modularperspective.api.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link HudLayout} 的纯坐标测试（M4 验收：锚点/偏移/命中区间）。 */
class HudLayoutTest {
    private static final int SCREEN_WIDTH = 854;
    private static final int SCREEN_HEIGHT = 480;
    private static final int WIDTH = 96;
    private static final int HEIGHT = 18;

    @Test
    void leftAnchorsUseOffsetAsIs() {
        assertEquals(4, HudLayout.x(Anchor.TOP_LEFT, 4, WIDTH, SCREEN_WIDTH));
        assertEquals(4, HudLayout.x(Anchor.CENTER_LEFT, 4, WIDTH, SCREEN_WIDTH));
        assertEquals(4, HudLayout.x(Anchor.BOTTOM_LEFT, 4, WIDTH, SCREEN_WIDTH));
        assertEquals(4, HudLayout.y(Anchor.TOP_LEFT, 4, HEIGHT, SCREEN_HEIGHT));
        assertEquals(4, HudLayout.y(Anchor.TOP_CENTER, 4, HEIGHT, SCREEN_HEIGHT));
        assertEquals(4, HudLayout.y(Anchor.TOP_RIGHT, 4, HEIGHT, SCREEN_HEIGHT));
    }

    @Test
    void rightAnchorsAreRelativeToRightEdge() {
        assertEquals(SCREEN_WIDTH - WIDTH + 4,
                HudLayout.x(Anchor.TOP_RIGHT, 4, WIDTH, SCREEN_WIDTH));
        assertEquals(SCREEN_WIDTH - WIDTH - 4,
                HudLayout.x(Anchor.BOTTOM_RIGHT, -4, WIDTH, SCREEN_WIDTH));
    }

    @Test
    void centerAnchorsAreCenteredPlusOffset() {
        assertEquals((SCREEN_WIDTH - WIDTH) / 2, HudLayout.x(Anchor.CENTER, 0, WIDTH, SCREEN_WIDTH));
        assertEquals((SCREEN_HEIGHT - HEIGHT) / 2, HudLayout.y(Anchor.CENTER, 0, HEIGHT, SCREEN_HEIGHT));
        assertEquals((SCREEN_WIDTH - WIDTH) / 2 + 10,
                HudLayout.x(Anchor.TOP_CENTER, 10, WIDTH, SCREEN_WIDTH));
    }

    @Test
    void bottomAnchorsAreRelativeToBottomEdge() {
        assertEquals(SCREEN_HEIGHT - HEIGHT + 4,
                HudLayout.y(Anchor.BOTTOM_LEFT, 4, HEIGHT, SCREEN_HEIGHT));
        assertEquals(SCREEN_HEIGHT - HEIGHT - 4,
                HudLayout.y(Anchor.BOTTOM_RIGHT, -4, HEIGHT, SCREEN_HEIGHT));
    }

    @Test
    void rectMatchesAnchoredPosition() {
        HudLayout.Rect rect = HudLayout.rect(Anchor.TOP_LEFT, 4, 4, WIDTH, HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertEquals(4, rect.x());
        assertEquals(4, rect.y());
        assertEquals(4 + WIDTH, rect.right());
        assertEquals(4 + HEIGHT, rect.bottom());
        assertEquals(4 + WIDTH / 2, rect.centerX());
        assertEquals(4 + HEIGHT / 2, rect.centerY());
    }

    @Test
    void rectContainsUsesHalfOpenInterval() {
        HudLayout.Rect rect = HudLayout.rect(Anchor.TOP_LEFT, 4, 4, WIDTH, HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertTrue(rect.contains(4.0, 4.0));
        assertTrue(rect.contains(4.0 + WIDTH - 0.001, 4.0 + HEIGHT - 0.001));
        assertFalse(rect.contains(4.0 + WIDTH, 4.0));
        assertFalse(rect.contains(4.0, 4.0 + HEIGHT));
        assertFalse(rect.contains(3.999, 4.0));
        assertFalse(rect.contains(-1.0, -1.0));
    }

    @Test
    void adjacentWidgetsDoNotOverlapByOnePixel() {
        HudLayout.Rect first = HudLayout.rect(Anchor.TOP_LEFT, 0, 0, 10, 10, SCREEN_WIDTH, SCREEN_HEIGHT);
        HudLayout.Rect second = HudLayout.rect(Anchor.TOP_LEFT, 10, 0, 10, 10, SCREEN_WIDTH, SCREEN_HEIGHT);
        assertTrue(first.contains(9.999, 5.0));
        assertFalse(first.contains(10.0, 5.0));
        assertTrue(second.contains(10.0, 5.0));
    }
}
