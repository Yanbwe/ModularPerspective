package org.yanbwe.modularperspective.client.cull;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.yanbwe.modularperspective.Config;

/**
 * Room scan (v0.3 parity pass): works out the vertical spans that cover the room the player is
 * standing in, and decides for every block whether it belongs to the cover (cull), is above the
 * cover (keep, so other culling tiers cannot remove it) or is inside the room (no opinion, the
 * ray culler keeps handling interior obstructions).
 *
 * <p>The pipeline mirrors the prototype mod's RoomScanner / RoomSnapshot semantics:
 * <ol>
 *   <li>breadth-first flood through passable cells, tracking the highest air per column;</li>
 *   <li>per-column roof solve: first solid cover above the air, then the top of everything covering
 *       the column (air gaps are crossed so a second storey's floor/ceiling/roof all come out);</li>
 *   <li>footprint closing: dilate by the wall thickness (only claiming actual wall columns) and
 *       fill enclosed holes, so pillars and walls get a cover span too;</li>
 *   <li>publish an immutable snapshot; open-sky columns vote against the room and, past a ratio,
 *       the whole scan bails out and lets the outdoor tier own the space.</li>
 * </ol>
 *
 * <p>Deliberately simpler than the prototype in two places: the scan runs on the client thread
 * inside a visit budget (the prototype uses a budgeted worker), and there is no incremental
 * section-hash bookkeeping yet. Both are candidates for the v0.3 follow-up.
 */
public final class RoomScanner {
    /** This snapshot has no opinion on the block; query the next culling tier. */
    public static final int NO_OPINION = 0;
    /** The block covers the room and should not render. */
    public static final int CULL = 1;
    /** The block is above everything covering the room and must render. */
    public static final int KEEP = 2;

    public enum Type {
        OUTDOOR,
        INDOOR
    }

    /** How far above the player the flood may travel, and how far a roof may be searched for. */
    private static final int MAX_VERTICAL_UP = 24;
    /** How far below the player the flood may travel, for sunken floors and stairs. */
    private static final int MAX_VERTICAL_DOWN = 8;
    /** Extra height above the flood ceiling to keep looking for a solid block. */
    private static final int ROOF_SEARCH_MARGIN = 4;
    /** Column is open to the sky, the sentinel stored instead of a roof base. */
    private static final int OPEN_SKY = Integer.MAX_VALUE;
    /** Above this fraction of open-sky columns the space is not a room; stand down entirely. */
    private static final double OPEN_SKY_BAIL_RATIO = 0.5;
    /** Hard ceiling on the 2D closing grid, so a huge room cannot allocate without bound. */
    private static final int MAX_GRID_CELLS = 512 * 512;
    /** Passes allowed when filling enclosed holes in the footprint. */
    private static final int MAX_HOLE_FILL_PASSES = 32;
    /** Flood visit budget per scan. */
    private static final int MAX_FLOOD_VISITS = 16000;
    /** Cull-span expansion cap for ghost rendering / debug dumps. */
    private static final int MAX_EXPANDED_BLOCKS = 8192;

    /** Default radius of the flood, in blocks. */
    private static final int RADIUS = 24;
    /** Default wall thickness in columns. */
    private static final int WALL_THICKNESS = 2;
    /** Default cover height searched for above the first roof block. */
    private static final int COVER_HEIGHT = 24;
    private static final long RESCAN_INTERVAL_TICKS = 20L;
    private static final double RESCAN_MOVE_SQ = 0.25D;

    private static final long NO_COLUMN = Long.MIN_VALUE;

    private static final RoomScanner INSTANCE = new RoomScanner();

    public static RoomScanner get() {
        return INSTANCE;
    }

    /** Immutable result of one scan. */
    private static final class Snapshot {
        private final it.unimi.dsi.fastutil.longs.Long2LongMap columns;
        private final long contentHash;
        private final int roofColumns;
        private final int wallColumns;
        private final int openSkyColumns;

        private Snapshot(it.unimi.dsi.fastutil.longs.Long2LongMap columns, long contentHash, int roofColumns,
                int wallColumns, int openSkyColumns) {
            this.columns = columns;
            this.contentHash = contentHash;
            this.roofColumns = roofColumns;
            this.wallColumns = wallColumns;
            this.openSkyColumns = openSkyColumns;
            this.columns.defaultReturnValue(NO_COLUMN);
        }
    }

    private volatile Snapshot snapshot;
    private volatile Type type = Type.OUTDOOR;
    private volatile String lastResult = "never run";
    private int openSkyDiag;
    private boolean dirty = true;
    private long lastScanTick = Long.MIN_VALUE;
    private BlockPos lastScanPos;
    private Object lastDimension;

    private RoomScanner() {
    }

    // ------------------------------------------------------------------ read side (any thread)

    public boolean isIndoor() {
        return this.type == Type.INDOOR;
    }

    public Type type() {
        return this.type;
    }

    public String lastResult() {
        return this.lastResult;
    }

    /** Whether the block belongs to the room cover (cull), is above it (keep) or neither. */
    public int test(BlockPos pos) {
        Snapshot snap = this.snapshot;
        if (snap == null) {
            return NO_OPINION;
        }
        long packed = snap.columns.get(packXZ(pos.getX(), pos.getZ()));
        if (packed == NO_COLUMN) {
            return NO_OPINION;
        }
        int roofBase = (int) (packed >>> 32);
        if (roofBase == OPEN_SKY) {
            return NO_OPINION;
        }
        int y = pos.getY();
        if (y < roofBase) {
            return NO_OPINION;
        }
        return y <= (int) packed ? CULL : KEEP;
    }

    /** Number of columns that carry a cover span. */
    public int roofColumnCount() {
        Snapshot snap = this.snapshot;
        return snap == null ? 0 : snap.roofColumns;
    }

    /** Number of claimed wall columns around the room footprint. */
    public int wallColumnCount() {
        Snapshot snap = this.snapshot;
        return snap == null ? 0 : snap.wallColumns;
    }

    /** Number of columns that turned out to be open to the sky. */
    public int openSkyColumnCount() {
        Snapshot snap = this.snapshot;
        return snap == null ? this.openSkyDiag : snap.openSkyColumns;
    }

    public int columnCount() {
        Snapshot snap = this.snapshot;
        return snap == null ? 0 : snap.columns.size();
    }

    /** Compatibility alias: number of room columns that got a cover span. */
    public int airCount() {
        return roofColumnCount();
    }

    /** Compatibility alias: number of claimed (wall) columns. */
    public int shellCount() {
        return wallColumnCount();
    }

    /** True when the block is part of the culled cover span. */
    public boolean isShell(BlockPos pos) {
        return test(pos) == CULL;
    }

    /** True when the block sits inside the room (below the cover). */
    public boolean isRoomAir(BlockPos pos) {
        Snapshot snap = this.snapshot;
        if (snap == null) {
            return false;
        }
        long packed = snap.columns.get(packXZ(pos.getX(), pos.getZ()));
        if (packed == NO_COLUMN) {
            return false;
        }
        int roofBase = (int) (packed >>> 32);
        if (roofBase == OPEN_SKY) {
            return false;
        }
        return pos.getY() < roofBase;
    }

    /** Enumerates the culled cover blocks, for the ghost pass. Returns the number emitted. */
    public int forEachCulledBlock(java.util.function.Consumer<BlockPos> sink) {
        Snapshot snap = this.snapshot;
        if (snap == null) {
            return 0;
        }
        int emitted = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (it.unimi.dsi.fastutil.longs.LongIterator it = snap.columns.keySet().iterator(); it.hasNext(); ) {
            long xz = it.nextLong();
            long packed = snap.columns.get(xz);
            int roofBase = (int) (packed >>> 32);
            if (roofBase == OPEN_SKY) {
                continue;
            }
            int roofTop = (int) packed;
            int x = unpackX(xz);
            int z = unpackZ(xz);
            for (int y = roofBase; y <= roofTop; y++) {
                sink.accept(cursor.set(x, y, z));
                emitted++;
                if (emitted >= MAX_EXPANDED_BLOCKS) {
                    return emitted;
                }
            }
        }
        return emitted;
    }

    // ------------------------------------------------------------------ client thread

    public void invalidate() {
        this.dirty = true;
    }

    public void reset() {
        this.snapshot = null;
        this.type = Type.OUTDOOR;
        this.lastResult = "reset";
        this.dirty = true;
        this.lastScanTick = Long.MIN_VALUE;
        this.lastScanPos = null;
        this.lastDimension = null;
    }

    public void tick(net.minecraft.client.Minecraft minecraft) {
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) {
            reset();
            return;
        }
        if (!Config.CULLING_ENABLED.get()) {
            if (this.snapshot != null) {
                reset();
            }
            return;
        }

        BlockPos pos = minecraft.player.blockPosition();
        Object dimension = level.dimension().location();
        long gameTime = level.getGameTime();
        boolean dimensionChanged = this.lastDimension != null && !this.lastDimension.equals(dimension);
        boolean due = this.dirty
                || dimensionChanged
                || this.lastScanTick == Long.MIN_VALUE
                || gameTime - this.lastScanTick >= RESCAN_INTERVAL_TICKS
                || this.lastScanPos == null
                || pos.distSqr(this.lastScanPos) >= RESCAN_MOVE_SQ;
        if (!due) {
            return;
        }
        this.lastScanTick = gameTime;
        this.lastScanPos = pos.immutable();
        this.lastDimension = dimension;
        this.dirty = false;
        scan(level, pos);
    }

    // ------------------------------------------------------------------ scanning

    private void scan(Level level, BlockPos playerPos) {
        BlockPos start = playerPos.above();
        int yMin = start.getY() - MAX_VERTICAL_DOWN;
        int yMax = start.getY() + MAX_VERTICAL_UP;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LongSet visited = new LongOpenHashSet();
        LongSet roomColumns = new LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.Long2IntMap topAir = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.LongList columnList = new it.unimi.dsi.fastutil.longs.LongArrayList();

        queue.add(start);
        visited.add(start.asLong());
        int visits = 0;
        while (!queue.isEmpty() && visits < MAX_FLOOD_VISITS) {
            BlockPos current = queue.pollFirst();
            visits++;
            long xz = packXZ(current.getX(), current.getZ());
            if (roomColumns.add(xz)) {
                columnList.add(xz);
                topAir.put(xz, current.getY());
            } else {
                int previous = topAir.get(xz);
                if (current.getY() > previous) {
                    topAir.put(xz, current.getY());
                }
            }

            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                BlockPos next = current.relative(direction);
                if (next.getY() < yMin || next.getY() > yMax) {
                    continue;
                }
                int dx = next.getX() - playerPos.getX();
                int dz = next.getZ() - playerPos.getZ();
                if (dx * dx + dz * dz > RADIUS * RADIUS) {
                    continue;
                }
                if (!visited.add(next.asLong())) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (!state.isSolidRender(level, next)) {
                    queue.add(next.immutable());
                }
            }
        }


        org.yanbwe.modularperspective.ModularPerspective.LOGGER.info("[room] flood columns={} visits={}", columnList.size(), visits);
        if (columnList.isEmpty()) {
            bailOut("player is under open sky - not in a room", 0);
            return;
        }

        // Stage 2: per-column roof solve.
        int searchTop = start.getY() + MAX_VERTICAL_UP + ROOF_SEARCH_MARGIN;
        it.unimi.dsi.fastutil.longs.Long2LongMap roofs = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap(columnList.size());
        int openSky = 0;
        for (int i = 0; i < columnList.size(); i++) {
            long xz = columnList.getLong(i);
            int x = unpackX(xz);
            int z = unpackZ(xz);
            int y = topAir.get(xz) + 1;
            int roofBase = Integer.MIN_VALUE;
            while (y <= searchTop) {
                BlockPos probe = new BlockPos(x, y, z);
                if (!level.isLoaded(probe)) {
                    break;
                }
                BlockState state = level.getBlockState(probe);
                if (state.isSolidRender(level, probe)) {
                    roofBase = y;
                    break;
                }
                y++;
            }

            if (roofBase == Integer.MIN_VALUE) {
                roofs.put(xz, packRoof(OPEN_SKY, OPEN_SKY));
                openSky++;
                continue;
            }
            int roofTop = roofBase;
            int limit = roofBase + COVER_HEIGHT;
            for (int probeY = roofBase + 1; probeY <= limit; probeY++) {
                BlockPos probe = new BlockPos(x, probeY, z);
                if (!level.isLoaded(probe)) {
                    break;
                }
                if (level.getBlockState(probe).isSolidRender(level, probe)) {
                    roofTop = probeY;
                }
            }
            roofs.put(xz, packRoof(roofBase, roofTop));
        }

        org.yanbwe.modularperspective.ModularPerspective.LOGGER.info("[room] roof solve columns={} openSky={}", columnList.size(), openSky);
        if (openSky > columnList.size() * OPEN_SKY_BAIL_RATIO) {
            bailOut("player is under open sky (" + openSky + "/" + columnList.size() + " columns) - not in a room", openSky);
            return;
        }

        // Stage 3: close the footprint (dilate over walls, fill enclosed holes).
        it.unimi.dsi.fastutil.longs.Long2LongMap closed = closeFootprint(level, roofs, openSky);
        if (closed == null) {
            bailOut("footprint close failed", openSky);
            return;
        }

        long hash = contentHash(closed);
        Snapshot previous = this.snapshot;
        if (previous != null && previous.contentHash == hash && previous.columns.size() == closed.size()) {
            this.lastResult = "unchanged (" + closed.size() + " columns)";
            return;
        }

        int roofColumns = 0;
        int wallColumns = 0;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : closed.long2LongEntrySet()) {
            long packed = entry.getLongValue();
            if ((int) (packed >>> 32) == OPEN_SKY) {
                continue;
            }
            if (roofs.containsKey(entry.getLongKey())) {
                roofColumns++;
            } else {
                wallColumns++;
            }
        }

        this.snapshot = new Snapshot(closed, hash, roofColumns, wallColumns, openSky);
        this.type = Type.INDOOR;
        org.yanbwe.modularperspective.ModularPerspective.LOGGER.info("[room] published columns={} roof={} wall={} openSky={}", closed.size(), roofColumns, wallColumns, openSky);
        this.lastResult = "INDOOR columns=" + closed.size() + " roof=" + roofColumns + " wall=" + wallColumns
                + " openSky=" + openSky;
    }

    private void bailOut(String reason, int openSky) {
        this.snapshot = null;
        this.type = Type.OUTDOOR;
        this.lastResult = reason;
        this.openSkyDiag = openSky;
        this.dirty = false;
    }

    private it.unimi.dsi.fastutil.longs.Long2LongMap closeFootprint(Level level,
            it.unimi.dsi.fastutil.longs.Long2LongMap roofs, int openSky) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (it.unimi.dsi.fastutil.longs.LongIterator it = roofs.keySet().iterator(); it.hasNext(); ) {
            long xz = it.nextLong();
            int x = unpackX(xz);
            int z = unpackZ(xz);
            if (x < minX) {
                minX = x;
            }
            if (x > maxX) {
                maxX = x;
            }
            if (z < minZ) {
                minZ = z;
            }
            if (z > maxZ) {
                maxZ = z;
            }
        }
        if (minX > maxX) {
            return null;
        }

        int pad = WALL_THICKNESS + 1;
        int gx0 = minX - pad;
        int gx1 = maxX + pad;
        int gz0 = minZ - pad;
        int gz1 = maxZ + pad;
        int w = gx1 - gx0 + 1;
        int h = gz1 - gz0 + 1;
        if ((long) w * h > MAX_GRID_CELLS) {
            return roofs;
        }

        byte[] state = new byte[w * h];
        int[] base = new int[w * h];
        int[] top = new int[w * h];

        for (it.unimi.dsi.fastutil.longs.LongIterator it = roofs.keySet().iterator(); it.hasNext(); ) {
            long xz = it.nextLong();
            long packed = roofs.get(xz);
            int roofBase = (int) (packed >>> 32);
            int idx = (unpackX(xz) - gx0) * h + (unpackZ(xz) - gz0);
            if (roofBase == OPEN_SKY) {
                state[idx] = CELL_OPEN_SKY;
            } else {
                state[idx] = CELL_ROOM;
                base[idx] = roofBase;
                top[idx] = (int) packed;
            }
        }

        dilate(level, state, base, top, w, h, gx0, gz0);
        fillEnclosedHoles(level, state, base, top, w, h);

        it.unimi.dsi.fastutil.longs.Long2LongMap out = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap(roofs.size() * 2);
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < h; iz++) {
                int idx = ix * h + iz;
                if (state[idx] != CELL_ROOM && state[idx] != CELL_CLAIMED) {
                    continue;
                }
                out.put(packXZ(gx0 + ix, gz0 + iz), packRoof(base[idx], top[idx]));
            }
        }
        return out;
    }

    private void dilate(Level level, byte[] state, int[] base, int[] top, int w, int h, int gx0, int gz0) {
        for (int pass = 0; pass < WALL_THICKNESS; pass++) {
            byte[] before = state.clone();
            for (int ix = 0; ix < w; ix++) {
                for (int iz = 0; iz < h; iz++) {
                    int idx = ix * h + iz;
                    if (before[idx] != CELL_EMPTY) {
                        continue;
                    }
                    int b = Integer.MAX_VALUE;
                    int t = Integer.MIN_VALUE;
                    boolean any = false;
                    if (ix > 0 && isSource(before[idx - h])) {
                        any = true;
                        b = Math.min(b, base[idx - h]);
                        t = Math.max(t, top[idx - h]);
                    }
                    if (ix < w - 1 && isSource(before[idx + h])) {
                        any = true;
                        b = Math.min(b, base[idx + h]);
                        t = Math.max(t, top[idx + h]);
                    }
                    if (iz > 0 && isSource(before[idx - 1])) {
                        any = true;
                        b = Math.min(b, base[idx - 1]);
                        t = Math.max(t, top[idx - 1]);
                    }
                    if (iz < h - 1 && isSource(before[idx + 1])) {
                        any = true;
                        b = Math.min(b, base[idx + 1]);
                        t = Math.max(t, top[idx + 1]);
                    }
                    if (!any) {
                        continue;
                    }
                    BlockPos wallProbe = new BlockPos(gx0 + ix, b - 1, gz0 + iz);
                    if (level.isLoaded(wallProbe) && level.getBlockState(wallProbe).isSolidRender(level, wallProbe)) {
                        state[idx] = CELL_CLAIMED;
                        base[idx] = b;
                        top[idx] = t;
                    }
                }
            }
        }
    }

    private void fillEnclosedHoles(Level level, byte[] state, int[] base, int[] top, int w, int h) {
        // Grid cells here only live inside the bounds around the room, so the border of the array is
        // "outside". Open-sky columns are outside anchors as well: anything touching one is open.
        boolean[] outside = new boolean[w * h];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int ix = 0; ix < w; ix++) {
            seedOutside(state, outside, queue, ix, 0, w, h);
            seedOutside(state, outside, queue, ix, h - 1, w, h);
        }
        for (int iz = 0; iz < h; iz++) {
            seedOutside(state, outside, queue, 0, iz, w, h);
            seedOutside(state, outside, queue, w - 1, iz, w, h);
        }
        for (int i = 0; i < state.length; i++) {
            if (state[i] == CELL_OPEN_SKY && !outside[i]) {
                outside[i] = true;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int idx = queue.pollFirst();
            int ix = idx / h;
            int iz = idx % h;
            if (ix > 0) {
                spreadOutside(state, outside, queue, idx - h);
            }
            if (ix < w - 1) {
                spreadOutside(state, outside, queue, idx + h);
            }
            if (iz > 0) {
                spreadOutside(state, outside, queue, idx - 1);
            }
            if (iz < h - 1) {
                spreadOutside(state, outside, queue, idx + 1);
            }
        }

        for (int pass = 0; pass < MAX_HOLE_FILL_PASSES; pass++) {
            byte[] before = state.clone();
            boolean changed = false;
            for (int ix = 0; ix < w; ix++) {
                for (int iz = 0; iz < h; iz++) {
                    int idx = ix * h + iz;
                    if (before[idx] != CELL_EMPTY || outside[idx]) {
                        continue;
                    }
                    int b = Integer.MAX_VALUE;
                    int t = Integer.MIN_VALUE;
                    boolean any = false;
                    if (ix > 0 && isSource(before[idx - h])) {
                        any = true;
                        b = Math.min(b, base[idx - h]);
                        t = Math.max(t, top[idx - h]);
                    }
                    if (ix < w - 1 && isSource(before[idx + h])) {
                        any = true;
                        b = Math.min(b, base[idx + h]);
                        t = Math.max(t, top[idx + h]);
                    }
                    if (iz > 0 && isSource(before[idx - 1])) {
                        any = true;
                        b = Math.min(b, base[idx - 1]);
                        t = Math.max(t, top[idx - 1]);
                    }
                    if (iz < h - 1 && isSource(before[idx + 1])) {
                        any = true;
                        b = Math.min(b, base[idx + 1]);
                        t = Math.max(t, top[idx + 1]);
                    }
                    if (any) {
                        state[idx] = CELL_CLAIMED;
                        base[idx] = b;
                        top[idx] = t;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    private static void seedOutside(byte[] state, boolean[] outside, ArrayDeque<Integer> queue, int ix, int iz, int w, int h) {
        int idx = ix * h + iz;
        if (outside[idx] || isSource(state[idx])) {
            return;
        }
        outside[idx] = true;
        queue.add(idx);
    }

    private static void spreadOutside(byte[] state, boolean[] outside, ArrayDeque<Integer> queue, int idx) {
        if (outside[idx] || isSource(state[idx])) {
            return;
        }
        outside[idx] = true;
        queue.add(idx);
    }

    private static boolean isSource(byte cell) {
        return cell == CELL_ROOM || cell == CELL_CLAIMED;
    }

    private static long contentHash(it.unimi.dsi.fastutil.longs.Long2LongMap columns) {
        long hash = 0x9E3779B97F4A7C15L;
        for (it.unimi.dsi.fastutil.longs.Long2LongMap.Entry entry : columns.long2LongEntrySet()) {
            long key = entry.getLongKey() * 0xC2B2AE3D27D4EB4FL;
            hash ^= key ^ (entry.getLongValue() * 0x165667B19E3779F9L);
            hash = Long.rotateLeft(hash, 27) * 0x9E3779B97F4A7C15L;
        }
        return hash;
    }

    // ------------------------------------------------------------------ grid cell states and packing

    private static final byte CELL_EMPTY = 0;
    private static final byte CELL_ROOM = 1;
    private static final byte CELL_CLAIMED = 2;
    private static final byte CELL_OPEN_SKY = 3;

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long xz) {
        return (int) (xz >> 32);
    }

    private static int unpackZ(long xz) {
        return (int) xz;
    }

    private static long packRoof(int roofBase, int roofTop) {
        return ((long) roofBase << 32) | (roofTop & 0xFFFFFFFFL);
    }
}
