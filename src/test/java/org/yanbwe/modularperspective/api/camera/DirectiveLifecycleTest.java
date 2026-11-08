package org.yanbwe.modularperspective.api.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link DirectiveLifecycle} 单测：到期清理与重生保留规则（docs/design.md §5.7、§6.1）。 */
class DirectiveLifecycleTest {

    private static CameraDirective directive(String id, int durationTicks, boolean keepOnDeath, long startTick) {
        return new CameraDirective(id, UUID.randomUUID(), 1L, 100, Enforcement.SUGGEST,
                EnumSet.of(CameraChannel.ANGLE), CameraTarget.EMPTY, 0.0F, 0.0F, Easing.LINEAR,
                durationTicks, false, keepOnDeath, startTick);
    }

    @Test
    void expiredFiltersOnlyFinishedFiniteDirectives() {
        List<CameraDirective> directives = List.of(
                directive("done", 10, false, 0L),
                directive("unlimited", CameraDirective.UNLIMITED, false, 0L),
                directive("fresh", 100, false, 50L));

        List<CameraDirective> expired = DirectiveLifecycle.expired(directives, 60L);

        assertEquals(1, expired.size());
        assertEquals("done", expired.get(0).id());
    }

    @Test
    void unlimitedDirectiveNeverExpires() {
        CameraDirective endless = directive("endless", CameraDirective.UNLIMITED, false, 0L);

        assertTrue(DirectiveLifecycle.expired(List.of(endless), Long.MAX_VALUE / 2L).isEmpty());
    }

    @Test
    void respawnKeepsKeepOnDeathAndEndsOthers() {
        CameraDirective keep = directive("keep", CameraDirective.UNLIMITED, true, 0L);
        CameraDirective drop = directive("drop", CameraDirective.UNLIMITED, false, 0L);

        DirectiveLifecycle.RespawnResult result = DirectiveLifecycle.retainOnRespawn(List.of(keep, drop));

        assertEquals(1, result.kept().size());
        assertEquals("keep", result.kept().get(0).id());
        assertEquals(1, result.ended().size());
        assertEquals("drop", result.ended().get(0).id());
    }

    @Test
    void respawnWithNoDirectivesHasEmptyResult() {
        DirectiveLifecycle.RespawnResult result = DirectiveLifecycle.retainOnRespawn(List.of());

        assertTrue(result.kept().isEmpty());
        assertTrue(result.ended().isEmpty());
    }
}
