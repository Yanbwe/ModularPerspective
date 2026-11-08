package org.yanbwe.modularperspective.client.cull;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the doorway counter used by the room tier (v0.3 parity, §16 M6).
 *
 * <p>The grid uses the same cell values as {@link RoomScanner}: 0 = empty, 1 = room air,
 * 2 = claimed wall, 3 = open sky. The flat layout is row-major with {@code idx = ix * h + iz}.
 */
class RoomDoorwayTest {
    private static final byte EMPTY = 0;
    private static final byte ROOM = 1;
    private static final byte CLAIMED = 2;

    @Test
    void sealedRoomHasNoDoorways() {
        byte[] grid = sealedRoom(12, 10);
        assertEquals(0, DoorwayCounter.countDoorways(grid, 12, 10));
    }

    @Test
    void twoRoomsConnectedByOneDoorwayReportTwoDoorwayColumns() {
        int w = 12;
        int h = 10;
        byte[] grid = sealedRoom(w, h);
        // Divider wall at ix = 5; carve a 2-wide doorway at iz 4..5.
        for (int iz = 1; iz < h - 1; iz++) {
            grid[5 * h + iz] = CLAIMED;
        }
        grid[5 * h + 4] = ROOM;
        grid[5 * h + 5] = ROOM;
        assertEquals(2, DoorwayCounter.countDoorways(grid, w, h));
    }

    @Test
    void singleColumnDoorwayCountsOnce() {
        int w = 12;
        int h = 10;
        byte[] grid = sealedRoom(w, h);
        for (int iz = 1; iz < h - 1; iz++) {
            grid[5 * h + iz] = CLAIMED;
        }
        grid[5 * h + 4] = ROOM;
        assertEquals(1, DoorwayCounter.countDoorways(grid, w, h));
    }

    @Test
    void openSkyAndEmptyColumnsAreIgnored() {
        int w = 8;
        int h = 8;
        byte[] grid = new byte[w * h];
        // A cross of claimed walls with a room cell in the middle; no room neighbours.
        grid[3 * h + 3] = ROOM;
        assertEquals(0, DoorwayCounter.countDoorways(grid, w, h));
    }

    /** A hollow rectangle: outer ring claimed, interior room air, 1-cell border of empty around. */
    private static byte[] sealedRoom(int w, int h) {
        byte[] grid = new byte[w * h];
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < h; iz++) {
                boolean border = ix == 0 || iz == 0 || ix == w - 1 || iz == h - 1;
                boolean inner = ix >= 1 && ix <= w - 2 && iz >= 1 && iz <= h - 2;
                grid[ix * h + iz] = border ? CLAIMED : (inner ? ROOM : EMPTY);
            }
        }
        // The ring must be walls, the interior rooms; leave the outermost ring as wall.
        for (int ix = 1; ix < w - 1; ix++) {
            grid[ix * h + 0] = CLAIMED;
            grid[ix * h + (h - 1)] = CLAIMED;
        }
        for (int iz = 1; iz < h - 1; iz++) {
            grid[0 * h + iz] = CLAIMED;
            grid[(w - 1) * h + iz] = CLAIMED;
        }
        return grid;
    }
}
