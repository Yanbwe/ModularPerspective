package org.yanbwe.modularperspective.client.camera;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.Projection;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;

/**
 * 相机指令栈与仲裁（docs/design.md §5.2、§5.3、§5.6）。
 *
 * <p>纯逻辑、不依赖 Minecraft 客户端状态，便于单元测试（M1 验收要求覆盖 §5.2 仲裁）：
 * <ul>
 *   <li>每个通道各自取一个赢家，字典序 {@code (priority, enforcement.weight, seq)} 大的胜出；</li>
 *   <li>{@code INPUT_LOCK} 是例外：所有指令的锁位取并集，与 priority/enforcement 无关（§5.3）；</li>
 *   <li>到期指令在 resolve 前清理；同 id 重复 put 即替换。</li>
 * </ul>
 */
public final class CameraStack {
    /** 仲裁排序：priority 大者胜 → REQUIRE 优于 SUGGEST → seq 大者（更晚）胜（§5.2）。 */
    private static final Comparator<CameraDirective> PRECEDENCE = Comparator
            .comparingInt(CameraDirective::priority)
            .thenComparingInt(directive -> directive.enforcement().weight())
            .thenComparingLong(CameraDirective::seq);

    private final Map<String, CameraDirective> byId = new LinkedHashMap<>();
    private long nextSeq;

    /** 分配单调递增序号；同一调用方的指令应使用服务端/客户端统一分配的 seq（§5.1）。 */
    public long allocateSeq() {
        return ++nextSeq;
    }

    /** 入栈；同 id 已存在则替换（旧指令语义上的结束由调用方记录为 SUPERSEDED，§5.2）。 */
    public void put(CameraDirective directive) {
        byId.put(directive.id(), directive);
    }

    /** 移除并返回；不存在返回 null。 */
    @Nullable
    public CameraDirective remove(String id) {
        return byId.remove(id);
    }

    public void clear() {
        byId.clear();
    }

    public List<CameraDirective> directives() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    /** 清理到期指令；返回被清理的指令（调用方可用于派发 ENDED 事件）。 */
    public List<CameraDirective> pruneExpired(long tick) {
        if (byId.isEmpty()) {
            return List.of();
        }
        List<CameraDirective> removed = new java.util.ArrayList<>();
        byId.values().removeIf(directive -> {
            if (directive.expired(tick)) {
                removed.add(directive);
                return true;
            }
            return false;
        });
        return removed;
    }

    /**
     * 解析当前 tick 的输出；没有任何仍有效的指令时返回 null（＝完全交还原版相机）。
     */
    @Nullable
    public ResolvedCameraState resolve(long tick) {
        pruneExpired(tick);
        if (byId.isEmpty()) {
            return null;
        }

        Position3 position = null;
        Float yaw = null;
        Float pitch = null;
        Float distance = null;
        Float fov = null;
        Projection projection = null;
        Boolean hudVisible = null;

        CameraDirective winner = best(CameraChannel.POSITION);
        if (winner != null) {
            position = winner.target().position();
        }
        winner = best(CameraChannel.ANGLE);
        if (winner != null) {
            yaw = winner.target().yaw();
            pitch = winner.target().pitch();
        }
        winner = best(CameraChannel.ZOOM);
        if (winner != null) {
            distance = winner.target().distance();
        }
        winner = best(CameraChannel.FOV);
        if (winner != null) {
            fov = winner.target().fov();
        }
        winner = best(CameraChannel.PROJECTION);
        if (winner != null) {
            projection = winner.target().projection();
        }
        winner = best(CameraChannel.HUD);
        if (winner != null) {
            hudVisible = winner.target().hudVisible();
        }

        EnumSet<InputLock> locks = EnumSet.noneOf(InputLock.class);
        for (CameraDirective directive : byId.values()) {
            if (directive.hasChannel(CameraChannel.INPUT_LOCK) && directive.target().locks() != null) {
                locks.addAll(directive.target().locks());
            }
        }

        return new ResolvedCameraState(position, yaw, pitch, distance, fov, projection, hudVisible, locks);
    }

    /** 某通道当前赢家（到位回执、SUGGEST 顶掉判断用，§5.5）。 */
    @Nullable
    public CameraDirective winner(CameraChannel channel) {
        return best(channel);
    }

    /**
     * 某个锁位当前来自哪条指令（并集语义下的“来源”）：取持有该位且优先级最高者（§5.3、§5.4）。
     * 玩家尝试操作该轴时，据此判断是 REQUIRE（拦截）还是 SUGGEST（允许并上报 Overridden）。
     */
    @Nullable
    public CameraDirective lockSource(InputLock lock) {
        CameraDirective best = null;
        for (CameraDirective directive : byId.values()) {
            if (!directive.hasChannel(CameraChannel.INPUT_LOCK) || directive.target().locks() == null
                    || !directive.target().locks().contains(lock)) {
                continue;
            }
            if (best == null || PRECEDENCE.compare(directive, best) > 0) {
                best = directive;
            }
        }
        return best;
    }

    @Nullable
    private CameraDirective best(CameraChannel channel) {        CameraDirective best = null;
        for (CameraDirective directive : byId.values()) {
            if (!directive.hasChannel(channel) || !targetHasValue(directive.target(), channel)) {
                continue;
            }
            if (best == null || PRECEDENCE.compare(directive, best) > 0) {
                best = directive;
            }
        }
        return best;
    }

    private static boolean targetHasValue(CameraTarget target, CameraChannel channel) {
        return switch (channel) {
            case POSITION -> target.hasPosition();
            case ANGLE -> target.hasAngle();
            case ZOOM -> target.hasDistance();
            case FOV -> target.hasFov();
            case PROJECTION -> target.hasProjection();
            case HUD -> target.hasHudVisible();
            case INPUT_LOCK -> target.hasLocks();
        };
    }
}
