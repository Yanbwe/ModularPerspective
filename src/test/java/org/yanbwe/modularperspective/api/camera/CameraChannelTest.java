package org.yanbwe.modularperspective.api.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * M0 骨架的纯逻辑单测：确认 §4.2/§5/§5.7/§6.5 的枚举面与设计文档一致。
 *
 * <p>不依赖渲染或游戏客户端，可在 {@code gradlew test} 下直接跑（§11.5）。
 */
class CameraChannelTest {
    @Test
    void channelSetMatchesDesign() {
        List<String> names = Arrays.stream(CameraChannel.values()).map(Enum::name).toList();
        assertEquals(List.of("POSITION", "ANGLE", "ZOOM", "PROJECTION", "FOV", "HUD", "INPUT_LOCK"), names);
    }

    @Test
    void endReasonSetMatchesDesign() {
        List<String> names = Arrays.stream(EndReason.values()).map(Enum::name).toList();
        assertEquals(
                List.of("REMOVED", "CLEARED", "EXPIRED", "SUPERSEDED", "OWNER_DISCONNECTED", "OWNER_DIED",
                        "POLICY_REFUSED"),
                names);
    }

    @Test
    void enforcementAndProjectionAreBinary() {
        assertEquals(2, Enforcement.values().length);
        assertEquals(2, Projection.values().length);
    }

    @Test
    void serverControlHasThreeLevels() {
        assertEquals(3, ServerControl.values().length);
        assertTrue(Arrays.asList(ServerControl.values()).contains(ServerControl.ALLOW));
    }
}
