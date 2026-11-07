package org.yanbwe.modularperspective.api.camera;

/**
 * 指令结束原因（docs/design.md §5.7、§6.3 的 {@code PerspectiveDirectiveEndedEvent}）。
 */
public enum EndReason {
    /** 被服务端显式 remove。 */
    REMOVED,
    /** 被 clear 清空（含玩家自己清空本地栈）。 */
    CLEARED,
    /** durationTicks 到期。 */
    EXPIRED,
    /** 同 id 指令被新指令替换。 */
    SUPERSEDED,
    /** 所有者断开连接。 */
    OWNER_DISCONNECTED,
    /** 所有者死亡且 keepOnDeath=false。 */
    OWNER_DIED,
    /** 服务端策略拒绝，或客户端 Refused(POLICY/DISABLED)。 */
    POLICY_REFUSED
}
