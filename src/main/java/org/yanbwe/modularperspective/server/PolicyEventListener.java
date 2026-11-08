package org.yanbwe.modularperspective.server;

import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicyChangedEvent;

/**
 * 示例：订阅服务端策略变更事件（docs/design.md §6.5）。
 *
 * <p>第三方模组可在 game bus 上监听 {@link PerspectivePolicyChangedEvent}，根据
 * {@code previous()}/{@code current()} 同步自己的 UI 或记录日志；事件在策略真正生效后派发，
 * 该 tick 末会补发一次全量快照（{@link PerspectiveServer}）。这里用 debug 日志做最小示例。
 */
public final class PolicyEventListener {
    private PolicyEventListener() {}

    public static void onPolicyChanged(PerspectivePolicyChangedEvent event) {
        ModularPerspective.LOGGER.debug("[perspective] policy changed for {}: {} -> {}",
                event.player().getGameProfile().getName(), event.previous(), event.current());
    }
}
