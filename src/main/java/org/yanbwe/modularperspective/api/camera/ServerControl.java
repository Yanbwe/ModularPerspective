package org.yanbwe.modularperspective.api.camera;

/**
 * 客户端是否接受服务端控制（docs/design.md §6.5、§13.1）。
 *
 * <p>{@link #DISABLED} 是玩家权利：服务端策略不能覆盖，只会收到 Refused(DISABLED)。
 */
public enum ServerControl {
    ALLOW,
    /** 只接受 SUGGEST；REQUIRE 会被降级/拒绝。 */
    SUGGEST_ONLY,
    /** 只保留本地控制。 */
    DISABLED
}
