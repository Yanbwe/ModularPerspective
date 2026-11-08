package org.yanbwe.modularperspective.client.cull;

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.ModularPerspective;

/**
 * Room scan (v0.3 parity pass, worker threading): works out the vertical spans that cover the room
 * the player is standing in, and decides for every block whether it belongs to the cover (cull), is
 * above the cover (keep, so other culling tiers cannot remove it) or is inside the room (no
 * opinion, the ray culler keeps handling interior obstructions).
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
 * <p>Like the prototype, the scan no longer runs on the client thread: the client thread captures a
 * cheap copy of the loaded chunk sections around the player ({@link SectionView}), hands it to a
 * single budgeted worker thread, and the resulting snapshot is swapped in on the client thread on a
 * later tick. The client thread only pays for the section copy and the snapshot swap.
 *
 * <p>Deliberately simpler than the prototype in one place: there is no incremental section-hash
 * bookkeeping yet (a scan re-reads the whole captured box).
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
    /** 单次扫描硬预算；超时保留旧快照并等下一轮（原模组 worker/预算的近似）。 */
    private static final long SCAN_BUDGET_NANOS = 3_000_000L;
    /** How far above the head we look for a ceiling before running the full flood. */
    private static final int CEILING_PROBE = 8;
    /** Cull-span expansion cap for ghost rendering / debug dumps. */
    private static final int MAX_EXPANDED_BLOCKS = 8192;

    /** Default radius of the flood, in blocks. */
    private static final int RADIUS = 24;
    /** Default wall thickness in columns. */
    private static final int WALL_THICKNESS = 2;
    /** Widest wall-line gap still reported as a doorway (2-wide doors are the common case). */
    /** Default cover height searched for above the first roof block. */
    private static final int COVER_HEIGHT = 24;
    private static final long RESCAN_INTERVAL_TICKS = 20L;
    private static final double RESCAN_MOVE_SQ = 0.25D;

    /** Horizontal capture radius: flood radius plus the footprint pad and a safety margin. */
    private static final int CAPTURE_RADIUS = RADIUS + WALL_THICKNESS + 2;

    private static final long NO_COLUMN = Long.MIN_VALUE;

    private static final RoomScanner INSTANCE = new RoomScanner();

    public static RoomScanner get() {
        return INSTANCE;
    }

    /** Immutable result of one scan. */
    private static final class Snapshot {
        private final Long2LongMap columns;
        private final long contentHash;
        private final int roofColumns;
        private final int wallColumns;
        private final int openSkyColumns;
        private final int doorways;

        private Snapshot(Long2LongMap columns, long contentHash, int roofColumns,
                int wallColumns, int openSkyColumns, int doorways) {
            this.columns = columns;
            this.contentHash = contentHash;
            this.roofColumns = roofColumns;
            this.wallColumns = wallColumns;
            this.openSkyColumns = openSkyColumns;
            this.doorways = doorways;
            this.columns.defaultReturnValue(NO_COLUMN);
        }
    }

    /**
     * A read-only copy of the block solidity around the player, made on the client thread so the
     * worker never touches the live level. Sections are copied as vanilla paletted containers (the
     * same copy used by chunk serialization), so concurrent block updates can no longer race us.
     */
    private static final class SectionView {
        private final Long2ObjectMap<PalettedContainer<BlockState>> sections;
        private final LongSet loadedChunks;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int minY;
        private final int maxY;

        private SectionView(Long2ObjectMap<PalettedContainer<BlockState>> sections, LongSet loadedChunks,
                int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
            this.sections = sections;
            this.loadedChunks = loadedChunks;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
        }

        private boolean isLoaded(int x, int y, int z) {
            if (y < this.minY || y > this.maxY || x < this.minX || x > this.maxX || z < this.minZ || z > this.maxZ) {
                return false;
            }
            return this.loadedChunks.contains(ChunkPos.asLong(x >> 4, z >> 4));
        }

        private boolean isSolid(int x, int y, int z) {
            if (!this.isLoaded(x, y, z)) {
                return false;
            }
            PalettedContainer<BlockState> container = this.sections.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
            if (container == null) {
                return false;
            }
            return solidRender(container.get(x & 15, y & 15, z & 15));
        }
    }

    /** A scan request handed from the client thread to the worker. */
    private static final class ScanRequest {
        private final long epoch;
        private final SectionView view;
        private final BlockPos playerPos;
        private final int yMin;
        private final int yMax;
        private final long captureNanos;

        private ScanRequest(long epoch, SectionView view, BlockPos playerPos, int yMin, int yMax, long captureNanos) {
            this.epoch = epoch;
            this.view = view;
            this.playerPos = playerPos;
            this.yMin = yMin;
            this.yMax = yMax;
            this.captureNanos = captureNanos;
        }
    }

    /** Worker output: either a closed footprint or a bail-out reason. */
    private static final class ScanResult {
        private final long epoch;
        private final Long2LongMap columns;
        private final long contentHash;
        private final int roofColumns;
        private final int wallColumns;
        private final int openSky;
        private final int doorways;
        private final int visits;
        private final String bailReason;
        private final boolean budgetExceeded;
        private final long computeNanos;
        private final long captureNanos;

        private ScanResult(long epoch, Long2LongMap columns, long contentHash, int roofColumns, int wallColumns,
                int openSky, int doorways, int visits, String bailReason, boolean budgetExceeded,
                long computeNanos, long captureNanos) {
            this.epoch = epoch;
            this.columns = columns;
            this.contentHash = contentHash;
            this.roofColumns = roofColumns;
            this.wallColumns = wallColumns;
            this.openSky = openSky;
            this.doorways = doorways;
            this.visits = visits;
            this.bailReason = bailReason;
            this.budgetExceeded = budgetExceeded;
            this.computeNanos = computeNanos;
            this.captureNanos = captureNanos;
        }

        private static ScanResult bail(long epoch, String reason, int visits, boolean budget, long computeNanos,
                long captureNanos) {
            return new ScanResult(epoch, null, 0L, 0, 0, 0, 0, visits, reason, budget, computeNanos, captureNanos);
        }
    }

    private volatile Snapshot snapshot;
    private volatile Type type = Type.OUTDOOR;
    private volatile String lastResult = "never run";
    private volatile long lastScanNanos;
    private volatile long maxScanNanos;
    private int openSkyDiag;
    private boolean dirty = true;
    private long lastScanTick = Long.MIN_VALUE;
    private BlockPos lastScanPos;
    private Object lastDimension;

    /** Bumped whenever a pending scan must be discarded (reset / world change). */
    private volatile long scanEpoch;
    /** True while the worker owns a request; the client thread only polls the result. */
    private boolean scanInFlight;
    private volatile ScanResult pendingResult;
    private final ArrayBlockingQueue<ScanRequest> requests = new ArrayBlockingQueue<>(1);
    private Thread scanThread;

    private RoomScanner() {
    }

    // ------------------------------------------------------------------ read side (any thread)

    public boolean isIndoor() {
        return this.type == Type.INDOOR;
    }

    public Type type() {
        return this.type;
    }

    /** Microseconds spent in the last completed scan (capture + worker compute). */
    public long lastScanMicros() {
        return this.lastScanNanos / 1000L;
    }

    /** Worst completed scan time since startup, in microseconds. */
    public long maxScanMicros() {
        return this.maxScanNanos / 1000L;
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

    /** Number of doorway columns crossing the room's wall ring (v0.3 parity, see DoorwayCounter). */
    public int doorwayCount() {
        Snapshot snap = this.snapshot;
        return snap == null ? 0 : snap.doorways;
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
        for (LongIterator it = snap.columns.keySet().iterator(); it.hasNext(); ) {
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
        this.scanEpoch++;
        this.pendingResult = null;
        this.scanInFlight = false;
        this.requests.clear();
        this.snapshot = null;
        this.type = Type.OUTDOOR;
        this.lastResult = "reset";
        this.lastScanNanos = 0L;
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
            if (this.snapshot != null || this.scanInFlight) {
                reset();
            }
            return;
        }

        pollResult();

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
        if (!due || this.scanInFlight) {
            return;
        }
        startScan(level, pos, gameTime, dimension);
    }

    // ------------------------------------------------------------------ client thread: capture + dispatch

    private void startScan(Level level, BlockPos playerPos, long gameTime, Object dimension) {
        this.lastScanTick = gameTime;
        this.lastScanPos = playerPos.immutable();
        this.lastDimension = dimension;
        this.dirty = false;

        BlockPos start = playerPos.above();
        if (!hasCeilingNearby(level, start)) {
            bailOut("open sky above player", 0);
            return;
        }

        long captureStart = System.nanoTime();
        int yMin = start.getY() - MAX_VERTICAL_DOWN;
        int yMax = start.getY() + MAX_VERTICAL_UP;
        SectionView view = captureView(level, start, yMin, yMax);
        long captureNanos = System.nanoTime() - captureStart;
        if (view == null) {
            bailOut("chunk capture failed (no loaded sections)", 0);
            return;
        }

        ensureWorker();
        ScanRequest request = new ScanRequest(this.scanEpoch, view, playerPos.immutable(), yMin, yMax, captureNanos);
        if (!this.requests.offer(request)) {
            // A previous request is still queued; the next interval will try again.
            this.lastResult = "scan request already queued";
            return;
        }
        this.scanInFlight = true;
    }

    private SectionView captureView(Level level, BlockPos start, int yMin, int yMax) {
        int cx0 = (start.getX() - CAPTURE_RADIUS) >> 4;
        int cx1 = (start.getX() + CAPTURE_RADIUS) >> 4;
        int cz0 = (start.getZ() - CAPTURE_RADIUS) >> 4;
        int cz1 = (start.getZ() + CAPTURE_RADIUS) >> 4;
        int topY = yMax + ROOF_SEARCH_MARGIN + COVER_HEIGHT;
        int bottomY = Math.max(level.getMinBuildHeight(), yMin - 1);
        int topClamped = Math.min(level.getMaxBuildHeight() - 1, topY);

        Long2ObjectMap<PalettedContainer<BlockState>> sections = new Long2ObjectOpenHashMap<>();
        LongSet loadedChunks = new LongOpenHashSet();
        int loaded = 0;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.getChunk(cx, cz);
                if (chunk == null || chunk.isEmpty()) {
                    if (chunk != null) {
                        loadedChunks.add(ChunkPos.asLong(cx, cz));
                        loaded++;
                    }
                    continue;
                }
                loadedChunks.add(ChunkPos.asLong(cx, cz));
                loaded++;
                int secY0 = SectionPos.blockToSectionCoord(bottomY);
                int secY1 = SectionPos.blockToSectionCoord(topClamped);
                for (int secY = secY0; secY <= secY1; secY++) {
                    int index = chunk.getSectionIndexFromSectionY(secY);
                    if (index < 0 || index >= chunk.getSections().length) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(index);
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    sections.put(SectionPos.asLong(cx, secY, cz), section.getStates().copy());
                }
            }
        }
        if (loaded == 0) {
            return null;
        }
        return new SectionView(sections, loadedChunks,
                start.getX() - CAPTURE_RADIUS, start.getX() + CAPTURE_RADIUS,
                start.getZ() - CAPTURE_RADIUS, start.getZ() + CAPTURE_RADIUS,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    private void ensureWorker() {
        if (this.scanThread != null) {
            return;
        }
        Thread thread = new Thread(this::workerLoop, "modularperspective-room-scan");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        this.scanThread = thread;
        thread.start();
    }

    private void workerLoop() {
        while (true) {
            ScanRequest request;
            try {
                request = this.requests.take();
            } catch (InterruptedException e) {
                return;
            }
            ScanResult result;
            try {
                result = scan(request);
            } catch (Throwable t) {
                result = ScanResult.bail(request.epoch, "scan error: " + t, 0, false, 0L, request.captureNanos);
            }
            this.pendingResult = result;
        }
    }

    private void pollResult() {
        ScanResult result = this.pendingResult;
        if (result == null) {
            return;
        }
        this.pendingResult = null;
        this.scanInFlight = false;
        if (result.epoch != this.scanEpoch) {
            return;
        }
        long elapsed = result.captureNanos + result.computeNanos;
        this.lastScanNanos = elapsed;
        this.maxScanNanos = Math.max(this.maxScanNanos, elapsed);
        if (result.bailReason != null) {
            if (result.budgetExceeded) {
                this.lastResult = result.bailReason + " (" + (elapsed / 1000L) + "us)";
                return;
            }
            this.snapshot = null;
            this.type = Type.OUTDOOR;
            this.lastResult = result.bailReason;
            this.openSkyDiag = result.openSky;
            return;
        }
        Snapshot previous = this.snapshot;
        if (previous != null && previous.contentHash == result.contentHash
                && previous.columns.size() == result.columns.size()) {
            this.lastResult = "unchanged (" + result.columns.size() + " columns)";
            return;
        }
        this.snapshot = new Snapshot(result.columns, result.contentHash, result.roofColumns, result.wallColumns,
                result.openSky, result.doorways);
        this.type = Type.INDOOR;
        this.lastResult = "INDOOR columns=" + result.columns.size() + " roof=" + result.roofColumns
                + " wall=" + result.wallColumns + " openSky=" + result.openSky + " doors=" + result.doorways;
        ModularPerspective.LOGGER.info(
                "[room] published columns={} roof={} wall={} openSky={} doors={} took={}us capture={}us visits={}",
                result.columns.size(), result.roofColumns, result.wallColumns, result.openSky, result.doorways,
                elapsed / 1000L, result.captureNanos / 1000L, result.visits);
        markDirtyAroundPlayer();
    }

    /** 房间/屋顶快照变化后，把玩家周围 3x3x3 个 section 标脏，邻居方块的面按新剔除状态重建（m07629）。 */
    private void markDirtyAroundPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.levelRenderer == null) {
            return;
        }
        BlockPos center = minecraft.player.blockPosition();
        int sectionX = SectionPos.blockToSectionCoord(center.getX());
        int sectionY = SectionPos.blockToSectionCoord(center.getY());
        int sectionZ = SectionPos.blockToSectionCoord(center.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    minecraft.levelRenderer.setSectionDirty(sectionX + dx, sectionY + dy, sectionZ + dz);
                }
            }
        }
    }

    // ------------------------------------------------------------------ worker thread

    private ScanResult scan(ScanRequest request) {
        long start = System.nanoTime();
        SectionView view = request.view;
        BlockPos playerPos = request.playerPos;
        BlockPos startPos = playerPos.above();
        int yMin = request.yMin;
        int yMax = request.yMax;

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        LongSet visited = new LongOpenHashSet();
        LongSet roomColumns = new LongOpenHashSet();
        it.unimi.dsi.fastutil.longs.Long2IntMap topAir = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        LongList columnList = new LongArrayList();

        queue.add(startPos);
        visited.add(startPos.asLong());
        int visits = 0;
        while (!queue.isEmpty() && visits < MAX_FLOOD_VISITS) {
            BlockPos current = queue.pollFirst();
            visits++;
            if ((visits & 0x3F) == 0 && System.nanoTime() - start > SCAN_BUDGET_NANOS) {
                return ScanResult.bail(request.epoch,
                        "flood over budget after " + visits + " visits - probably outdoors",
                        0, true, System.nanoTime() - start, request.captureNanos);
            }
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

            for (Direction direction : Direction.values()) {
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
                if (!view.isSolid(next.getX(), next.getY(), next.getZ())) {
                    queue.add(next.immutable());
                }
            }
        }

        if (Config.DEBUG_OVERLAY.get()) {
            ModularPerspective.LOGGER.info("[room] flood columns={} visits={}", columnList.size(), visits);
        }
        if (visits >= MAX_FLOOD_VISITS) {
            return ScanResult.bail(request.epoch, "flood visit budget exhausted (probably outdoors)", 0, false,
                    System.nanoTime() - start, request.captureNanos);
        }
        if (columnList.isEmpty()) {
            return ScanResult.bail(request.epoch, "player is under open sky - not in a room", 0, false,
                    System.nanoTime() - start, request.captureNanos);
        }

        // Stage 2: per-column roof solve.
        int searchTop = startPos.getY() + MAX_VERTICAL_UP + ROOF_SEARCH_MARGIN;
        Long2LongMap roofs = new Long2LongOpenHashMap(columnList.size());
        int openSky = 0;
        for (int i = 0; i < columnList.size(); i++) {
            if ((i & 0x1F) == 0 && System.nanoTime() - start > SCAN_BUDGET_NANOS) {
                return ScanResult.bail(request.epoch, "scan budget exceeded in roof solve", 0, true,
                        System.nanoTime() - start, request.captureNanos);
            }
            long xz = columnList.getLong(i);
            int x = unpackX(xz);
            int z = unpackZ(xz);
            int y = topAir.get(xz) + 1;
            int roofBase = Integer.MIN_VALUE;
            while (y <= searchTop) {
                if (!view.isLoaded(x, y, z)) {
                    break;
                }
                if (view.isSolid(x, y, z)) {
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
                if (!view.isLoaded(x, probeY, z)) {
                    break;
                }
                if (view.isSolid(x, probeY, z)) {
                    roofTop = probeY;
                }
            }
            roofs.put(xz, packRoof(roofBase, roofTop));
        }

        if (Config.DEBUG_OVERLAY.get()) {
            ModularPerspective.LOGGER.info("[room] roof solve columns={} openSky={}", columnList.size(), openSky);
        }
        // 保守化（2026-11-08 实机反馈）：只要有任意一列看到天空，就不判定为室内，避免树冠/局部遮挡误判。
        if (openSky > 0) {
            return ScanResult.bail(request.epoch, "player is under open sky (" + openSky + "/" + columnList.size()
                    + " columns) - not in a room", 0, false, System.nanoTime() - start, request.captureNanos);
        }

        // Stage 3: close the footprint (dilate over walls, fill enclosed holes).
        ClosedFootprint closedFootprint = closeFootprint(view, roofs);
        Long2LongMap closed = closedFootprint == null ? null : closedFootprint.columns();
        int doorways = closedFootprint == null ? 0 : closedFootprint.doorways();
        if (System.nanoTime() - start > SCAN_BUDGET_NANOS) {
            return ScanResult.bail(request.epoch, "scan budget exceeded in footprint close", 0, true,
                    System.nanoTime() - start, request.captureNanos);
        }
        if (closed == null) {
            return ScanResult.bail(request.epoch, "footprint close failed", 0, false,
                    System.nanoTime() - start, request.captureNanos);
        }

        long hash = contentHash(closed);
        int roofColumns = 0;
        int wallColumns = 0;
        for (Long2LongMap.Entry entry : closed.long2LongEntrySet()) {
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

        long computeNanos = System.nanoTime() - start;
        return new ScanResult(request.epoch, closed, hash, roofColumns, wallColumns, openSky, doorways, visits,
                null, false, computeNanos, request.captureNanos);
    }

    private static boolean hasCeilingNearby(Level level, BlockPos start) {
        int baseY = start.getY();
        for (int probeY = baseY; probeY <= baseY + CEILING_PROBE; probeY++) {
            BlockPos probe = new BlockPos(start.getX(), probeY, start.getZ());
            if (!level.isLoaded(probe)) {
                return false;
            }
            if (level.getBlockState(probe).isSolidRender(level, probe)) {
                return true;
            }
        }
        return false;
    }

    private void bailOut(String reason, int openSky) {
        this.scanEpoch++;
        this.pendingResult = null;
        this.scanInFlight = false;
        this.requests.clear();
        this.snapshot = null;
        this.type = Type.OUTDOOR;
        this.lastResult = reason;
        this.openSkyDiag = openSky;
        this.dirty = false;
    }

    private ClosedFootprint closeFootprint(SectionView view, Long2LongMap roofs) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (LongIterator it = roofs.keySet().iterator(); it.hasNext(); ) {
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
            return new ClosedFootprint(roofs, 0);
        }

        byte[] state = new byte[w * h];
        int[] base = new int[w * h];
        int[] top = new int[w * h];

        for (LongIterator it = roofs.keySet().iterator(); it.hasNext(); ) {
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

        dilate(view, state, base, top, w, h, gx0, gz0);
        fillEnclosedHoles(state, base, top, w, h);

        Long2LongMap out = new Long2LongOpenHashMap(roofs.size() * 2);
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < h; iz++) {
                int idx = ix * h + iz;
                if (state[idx] != CELL_ROOM && state[idx] != CELL_CLAIMED) {
                    continue;
                }
                out.put(packXZ(gx0 + ix, gz0 + iz), packRoof(base[idx], top[idx]));
            }
        }
        return new ClosedFootprint(out, DoorwayCounter.countDoorways(state, w, h));
    }

    /** Closed footprint plus the number of doorway columns crossing its wall ring. */
    private record ClosedFootprint(Long2LongMap columns, int doorways) {
    }

    /**
     * Counts columns of room air crossed by a doorway: both +/-X neighbours are room columns and both
     * +/-Z neighbours are claimed wall columns, or the other way around. The prototype mod has no room
     * graph - its flood simply travels through doorways - so counting the crossing columns is how the
     * "the room continues through this opening" fact is exposed without inventing a graph the prototype
     * does not have.
     */
    private void dilate(SectionView view, byte[] state, int[] base, int[] top, int w, int h, int gx0, int gz0) {
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
                    int wallX = gx0 + ix;
                    int wallZ = gz0 + iz;
                    if (view.isLoaded(wallX, b - 1, wallZ) && view.isSolid(wallX, b - 1, wallZ)) {
                        state[idx] = CELL_CLAIMED;
                        base[idx] = b;
                        top[idx] = t;
                    }
                }
            }
        }
    }

    private void fillEnclosedHoles(byte[] state, int[] base, int[] top, int w, int h) {
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

    /** Level-free equivalent of {@code BlockState#isSolidRender} (uses the same cached rule). */
    private static boolean solidRender(BlockState state) {
        return state.canOcclude()
                && Block.isShapeFullBlock(state.getOcclusionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    private static long contentHash(Long2LongMap columns) {
        long hash = 0x9E3779B97F4A7C15L;
        for (Long2LongMap.Entry entry : columns.long2LongEntrySet()) {
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
