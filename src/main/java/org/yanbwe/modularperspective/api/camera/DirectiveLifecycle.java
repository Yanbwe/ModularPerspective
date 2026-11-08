package org.yanbwe.modularperspective.api.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * 指令结束规则（docs/design.md §5.7、§6.1）。
 *
 * <p>抽成与 Minecraft 无关的纯逻辑，便于单测；服务端 {@code PerspectiveServer} 的
 * tick 到期清理与重生清理都走这里，保证“到期/死亡”两种结束原因的口径一致。
 */
public final class DirectiveLifecycle {
    private DirectiveLifecycle() {}

    /** 收集 {@code gameTime} 时刻已到期的指令（{@code durationTicks < 0} 的永不；{@code > 0} 时按 startTick 计算）。 */
    public static List<CameraDirective> expired(List<CameraDirective> directives, long gameTime) {
        List<CameraDirective> expired = new ArrayList<>();
        for (CameraDirective directive : directives) {
            if (directive.expired(gameTime)) {
                expired.add(directive);
            }
        }
        return expired;
    }

    /**
     * 重生时的保留规则（§6.1）：{@code keepOnDeath=true} 保留，其余以 {@code OWNER_DIED} 结束。
     */
    public static RespawnResult retainOnRespawn(List<CameraDirective> directives) {
        List<CameraDirective> kept = new ArrayList<>();
        List<CameraDirective> ended = new ArrayList<>();
        for (CameraDirective directive : directives) {
            if (directive.keepOnDeath()) {
                kept.add(directive);
            } else {
                ended.add(directive);
            }
        }
        return new RespawnResult(kept, ended);
    }

    /** 重生清理结果：保留与结束两组（结束原因固定为 {@link EndReason#OWNER_DIED}，由调用方记录）。 */
    public record RespawnResult(List<CameraDirective> kept, List<CameraDirective> ended) {
        public RespawnResult {
            kept = List.copyOf(kept);
            ended = List.copyOf(ended);
        }
    }
}
