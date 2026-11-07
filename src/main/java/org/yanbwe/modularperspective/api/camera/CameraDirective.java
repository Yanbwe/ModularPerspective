package org.yanbwe.modularperspective.api.camera;

import java.util.EnumSet;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * 一条相机指令（docs/design.md §5.1）。
 *
 * @param id            调用方定义、作用域内唯一；重复 push 同 id = 替换旧指令（旧指令以 {@code SUPERSEDED} 结束，
 *                      §5.2）
 * @param owner         归属玩家（本地层为 null；服务端指令为发起玩家 UUID，用于登出/死亡清理）
 * @param seq           服务端/客户端单调序号，用于同优先级 tie-break（§5.2）
 * @param priority      数值越大越优先（无符号大小比较）
 * @param enforcement   SUGGEST 可被玩家操作顶掉、REQUIRE 不可（§5.4）；tie-break 时 REQUIRE 优于 SUGGEST
 * @param channels      本指令声明的通道；未声明的通道不参与仲裁
 * @param target        {@link CameraTarget.Unspecified} 对应 {@link CameraTarget#EMPTY}，表示不指定具体值
 * @param fadeInTicks   淡入 tick 数（0 = 立即）
 * @param fadeOutTicks  淡出 tick 数（0 = 立即；移除/过期时由客户端做视觉淡出）
 * @param easing        过渡曲线
 * @param durationTicks 持续时间 tick；{@code < 0} = 无限（默认 -1）
 * @param requiresAck   是否需要到位回执（§5.5）
 * @param keepOnDeath   所有者死亡时是否保留（§6.1）
 * @param startTick     入栈时的游戏 tick（仲裁“最晚开始”与 fade 计算用）
 */
public record CameraDirective(
        String id,
        @Nullable UUID owner,
        long seq,
        int priority,
        Enforcement enforcement,
        EnumSet<CameraChannel> channels,
        CameraTarget target,
        float fadeInTicks,
        float fadeOutTicks,
        Easing easing,
        int durationTicks,
        boolean requiresAck,
        boolean keepOnDeath,
        long startTick) {

    public static final int UNLIMITED = -1;

    public CameraDirective {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("channels must not be empty");
        }
        channels = EnumSet.copyOf(channels);
        if (target == null) {
            target = CameraTarget.EMPTY;
        }
        if (fadeInTicks < 0.0F || fadeOutTicks < 0.0F) {
            throw new IllegalArgumentException("fade ticks must be >= 0");
        }
        if (durationTicks == 0) {
            throw new IllegalArgumentException("durationTicks must be != 0 (use UNLIMITED for endless)");
        }
        if (enforcement == null) {
            enforcement = Enforcement.SUGGEST;
        }
        if (easing == null) {
            easing = Easing.LINEAR;
        }
    }

    /** 便捷构造：SUGGEST、无限时长、无 fade、无需回执、不保留到死亡之后。 */
    public static CameraDirective simple(String id, @Nullable UUID owner, long seq, int priority,
                                         EnumSet<CameraChannel> channels, CameraTarget target, long startTick) {
        return new CameraDirective(id, owner, seq, priority, Enforcement.SUGGEST, channels, target,
                0.0F, 0.0F, Easing.LINEAR, UNLIMITED, false, false, startTick);
    }

    public boolean hasChannel(CameraChannel channel) {
        return channels.contains(channel);
    }

    /** 已到期（{@code durationTicks > 0} 且存活 tick 数达到 duration；{@code < 0} 永不）。 */
    public boolean expired(long tick) {
        return durationTicks > 0 && tick - startTick >= durationTicks;
    }

    /** 已存活 tick 数（含当前 tick 的 partial 部分在调用方处理）。 */
    public float ageTicks(long tick) {
        return (float) (tick - startTick);
    }
}
