package org.yanbwe.modularperspective.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.Projection;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;

/**
 * §5.2 仲裁规则单测（M1 验收项）：priority / enforcement / seq 字典序、通道独立、INPUT_LOCK 并集、
 * 到期清理与同 id 替换。
 */
class CameraStackTest {
    private static final long TICK = 100L;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static CameraDirective angleDirective(String id, long seq, int priority, Enforcement enforcement,
                                                  float yaw, float pitch) {
        CameraTarget target = new CameraTarget(null, yaw, pitch, null, null, null, null, null);
        return new CameraDirective(id, OWNER, seq, priority, enforcement, EnumSet.of(CameraChannel.ANGLE), target,
                0.0F, 0.0F, Easing.LINEAR, CameraDirective.UNLIMITED, false, false, TICK);
    }

    private static CameraDirective positionDirective(String id, long seq, int priority, Position3 position) {
        CameraTarget target = new CameraTarget(position, null, null, null, null, null, null, null);
        return new CameraDirective(id, OWNER, seq, priority, Enforcement.SUGGEST,
                EnumSet.of(CameraChannel.POSITION), target, 0.0F, 0.0F, Easing.LINEAR,
                CameraDirective.UNLIMITED, false, false, TICK);
    }

    private static CameraDirective projectionDirective(String id, long seq, int priority, Projection projection) {
        CameraTarget target = new CameraTarget(null, null, null, null, null, projection, null, null);
        return new CameraDirective(id, OWNER, seq, priority, Enforcement.SUGGEST,
                EnumSet.of(CameraChannel.PROJECTION), target, 0.0F, 0.0F, Easing.LINEAR,
                CameraDirective.UNLIMITED, false, false, TICK);
    }

    @Test
    void emptyStackResolvesToNull() {
        assertNull(new CameraStack().resolve(TICK));
    }

    @Test
    void higherPriorityWinsEvenWhenOlder() {
        CameraStack stack = new CameraStack();
        stack.put(angleDirective("low", 2L, 10, Enforcement.REQUIRE, 10.0F, 20.0F));
        stack.put(angleDirective("high", 1L, 20, Enforcement.SUGGEST, 30.0F, 40.0F));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(30.0F, resolved.yaw(), 0.001F);
        assertEquals(40.0F, resolved.pitch(), 0.001F);
    }

    @Test
    void requireBeatsSuggestAtSamePriority() {
        CameraStack stack = new CameraStack();
        stack.put(angleDirective("suggest", 2L, 100, Enforcement.SUGGEST, 10.0F, 0.0F));
        stack.put(angleDirective("require", 1L, 100, Enforcement.REQUIRE, 90.0F, 0.0F));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(90.0F, resolved.yaw(), 0.001F);
    }

    @Test
    void laterSeqWinsWithinSamePriorityAndEnforcement() {
        CameraStack stack = new CameraStack();
        stack.put(angleDirective("older", 1L, 100, Enforcement.SUGGEST, 10.0F, 0.0F));
        stack.put(angleDirective("newer", 2L, 100, Enforcement.SUGGEST, 20.0F, 0.0F));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(20.0F, resolved.yaw(), 0.001F);
    }

    @Test
    void channelsAreResolvedIndependently() {
        CameraStack stack = new CameraStack();
        stack.put(positionDirective("pos", 1L, 10, new Position3(1.0, 2.0, 3.0)));
        stack.put(projectionDirective("proj", 1L, 10, Projection.ORTHOGRAPHIC));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(new Position3(1.0, 2.0, 3.0), resolved.position());
        assertEquals(Projection.ORTHOGRAPHIC, resolved.projection());
        assertNull(resolved.yaw());
    }

    @Test
    void inputLocksAreUnionRegardlessOfPriority() {
        CameraStack stack = new CameraStack();
        CameraTarget highTarget = new CameraTarget(null, null, null, null, null, null, null,
                EnumSet.of(InputLock.ROTATE));
        CameraTarget lowTarget = new CameraTarget(null, null, null, null, null, null, null,
                EnumSet.of(InputLock.MOVE));
        stack.put(new CameraDirective("high", OWNER, 2L, 500, Enforcement.REQUIRE,
                EnumSet.of(CameraChannel.INPUT_LOCK), highTarget, 0.0F, 0.0F, Easing.LINEAR,
                CameraDirective.UNLIMITED, false, false, TICK));
        stack.put(new CameraDirective("low", OWNER, 1L, 1, Enforcement.SUGGEST,
                EnumSet.of(CameraChannel.INPUT_LOCK), lowTarget, 0.0F, 0.0F, Easing.LINEAR,
                CameraDirective.UNLIMITED, false, false, TICK));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(EnumSet.of(InputLock.ROTATE, InputLock.MOVE), resolved.locks());
    }

    @Test
    void expiredDirectiveIsPruned() {
        CameraStack stack = new CameraStack();
        CameraTarget target = new CameraTarget(null, 15.0F, 0.0F, null, null, null, null, null);
        stack.put(new CameraDirective("temp", OWNER, 1L, 10, Enforcement.SUGGEST, EnumSet.of(CameraChannel.ANGLE),
                target, 0.0F, 0.0F, Easing.LINEAR, 5, false, false, TICK));

        assertEquals(0, stack.pruneExpired(TICK + 4).size());
        assertNotNull(stack.resolve(TICK + 4));
        // start 100 + duration 5 => 从 tick 105 起视为到期（tick - start >= duration）
        assertNull(stack.resolve(TICK + 5));
        assertEquals(0, stack.size());
    }

    @Test
    void putWithSameIdReplacesDirective() {
        CameraStack stack = new CameraStack();
        stack.put(angleDirective("same", 1L, 10, Enforcement.SUGGEST, 10.0F, 0.0F));
        stack.put(angleDirective("same", 2L, 10, Enforcement.SUGGEST, 25.0F, 0.0F));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(25.0F, resolved.yaw(), 0.001F);
        assertEquals(1, stack.size());
    }

    @Test
    void removalFallsBackToNextDirective() {
        CameraStack stack = new CameraStack();
        stack.put(angleDirective("top", 2L, 100, Enforcement.SUGGEST, 10.0F, 0.0F));
        stack.put(angleDirective("base", 1L, 10, Enforcement.SUGGEST, 30.0F, 0.0F));
        stack.remove("top");

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(30.0F, resolved.yaw(), 0.001F);
    }

    @Test
    void targetWithoutValueDoesNotWinChannel() {
        CameraStack stack = new CameraStack();
        // 只有 yaw 没有 pitch 的目标：ANGLE 通道整体由它解析，pitch 保留 null（不做字段继承）
        CameraTarget target = new CameraTarget(null, 45.0F, null, null, null, null, null, null);
        stack.put(new CameraDirective("angle", OWNER, 1L, 10, Enforcement.SUGGEST, EnumSet.of(CameraChannel.ANGLE),
                target, 0.0F, 0.0F, Easing.LINEAR, CameraDirective.UNLIMITED, false, false, TICK));

        ResolvedCameraState resolved = stack.resolve(TICK);
        assertNotNull(resolved);
        assertEquals(45.0F, resolved.yaw(), 0.001F);
        assertNull(resolved.pitch());
        assertTrue(resolved.locks().isEmpty());
    }
}
