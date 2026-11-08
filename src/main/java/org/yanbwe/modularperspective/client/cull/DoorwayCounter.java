package org.yanbwe.modularperspective.client.cull;

/**
 * Counts doorway columns crossing a closed room footprint (v0.3 parity, §16 M6).
 *
 * <p>The prototype mod has no room graph: its flood simply travels through doorways, so rooms that
 * are connected by an opening are merged by the flood itself. This counter exposes that fact as a
 * cheap metric on the 2D footprint instead of inventing a graph the prototype does not have.
 *
 * <p>A doorway is a short run of room cells inside a 1-thick wall line: the wall continues (claimed)
 * beyond both ends of the run, and every cell in the run opens into room air on the other axis.
 * The grid uses the same flat layout as {@link RoomScanner}: {@code idx = ix * h + iz}, with
 * 0 = empty, 1 = room air, 2 = claimed wall, 3 = open sky.
 *
 * <p>Pure Java on purpose so the counter can be unit tested without the Minecraft classpath.
 */
public final class DoorwayCounter {
    /** Widest wall-line gap still reported as a doorway (2-wide doors are the common case). */
    public static final int MAX_DOORWAY_WIDTH = 3;

    private static final byte CELL_ROOM = 1;
    private static final byte CELL_CLAIMED = 2;

    private DoorwayCounter() {
    }

    /**
     * @param state flat footprint grid ({@code idx = ix * h + iz})
     * @param w     grid width (X)
     * @param h     grid height (Z)
     * @return number of room columns that cross a wall opening
     */
    public static int countDoorways(byte[] state, int w, int h) {
        int doorways = 0;
        // Openings in walls running along Z: a run of room cells along Z inside the wall line.
        for (int ix = 1; ix < w - 1; ix++) {
            int iz = 1;
            while (iz < h - 1) {
                if (!isDoorwayEdgeZWall(state, ix * h + iz, h)) {
                    iz++;
                    continue;
                }
                int runEnd = iz;
                while (runEnd + 1 < h - 1 && isDoorwayEdgeZWall(state, ix * h + runEnd + 1, h)) {
                    runEnd++;
                }
                int length = runEnd - iz + 1;
                if (length <= MAX_DOORWAY_WIDTH
                        && state[ix * h + iz - 1] == CELL_CLAIMED
                        && state[ix * h + runEnd + 1] == CELL_CLAIMED) {
                    doorways += length;
                }
                iz = runEnd + 1;
            }
        }
        // Openings in walls running along X: a run of room cells along X inside the wall line.
        for (int iz = 1; iz < h - 1; iz++) {
            int ix = 1;
            while (ix < w - 1) {
                if (!isDoorwayEdgeXWall(state, ix * h + iz, h)) {
                    ix++;
                    continue;
                }
                int runEnd = ix;
                while (runEnd + 1 < w - 1 && isDoorwayEdgeXWall(state, (runEnd + 1) * h + iz, h)) {
                    runEnd++;
                }
                int length = runEnd - ix + 1;
                if (length <= MAX_DOORWAY_WIDTH
                        && state[(ix - 1) * h + iz] == CELL_CLAIMED
                        && state[(runEnd + 1) * h + iz] == CELL_CLAIMED) {
                    doorways += length;
                }
                ix = runEnd + 1;
            }
        }
        return doorways;
    }

    /** Room cell inside a wall running along Z: room on both X sides, wall continuing on a Z side. */
    private static boolean isDoorwayEdgeZWall(byte[] state, int idx, int h) {
        return state[idx] == CELL_ROOM
                && state[idx - h] == CELL_ROOM && state[idx + h] == CELL_ROOM
                && (state[idx - 1] == CELL_CLAIMED || state[idx + 1] == CELL_CLAIMED);
    }

    /** Room cell inside a wall running along X: room on both Z sides, wall continuing on an X side. */
    private static boolean isDoorwayEdgeXWall(byte[] state, int idx, int h) {
        return state[idx] == CELL_ROOM
                && state[idx - 1] == CELL_ROOM && state[idx + 1] == CELL_ROOM
                && (state[idx - h] == CELL_CLAIMED || state[idx + h] == CELL_CLAIMED);
    }
}
