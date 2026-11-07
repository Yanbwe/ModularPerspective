package org.yanbwe.modularperspective.api.camera;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * 服务端策略变更事件（docs/design.md §6.3、§6.5）。
 *
 * <p>在 game bus 上派发；监听方可据此记录/同步策略。事件在策略真正生效后由
 * {@code PerspectiveServer#setPolicy} 触发，且该 tick 末会补发一次全量快照。
 */
public class PerspectivePolicyChangedEvent extends Event {
    private final ServerPlayer player;
    private final PerspectivePolicy previous;
    private final PerspectivePolicy current;

    public PerspectivePolicyChangedEvent(ServerPlayer player, PerspectivePolicy previous, PerspectivePolicy current) {
        this.player = player;
        this.previous = previous;
        this.current = current;
    }

    public ServerPlayer player() {
        return player;
    }

    public PerspectivePolicy previous() {
        return previous;
    }

    public PerspectivePolicy current() {
        return current;
    }
}
