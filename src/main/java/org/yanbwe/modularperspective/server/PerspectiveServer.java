package org.yanbwe.modularperspective.server;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.EndReason;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicy;
import org.yanbwe.modularperspective.api.camera.Projection;
import org.yanbwe.modularperspective.network.CameraArrivedPayload;
import org.yanbwe.modularperspective.network.CameraSnapshotPayload;
import org.yanbwe.modularperspective.network.PerspectiveNetwork;

/**
 * 服务端指令栈与快照（docs/design.md §6.1、§6.2、§7.3）。
 *
 * <p>状态以玩家 {@code AttachmentType} 形式持久化（{@link PerspectiveAttachment}），随存档保存、
 * 死亡时按 {@code copyOnDeath} 复制后再由 {@link #onPlayerRespawn} 执行结束规则。
 * 变更有两类触发：每台服 tick 检查到期，以及命令/API 显式修改时置 {@code dirty}；
 * 每个 tick 末最多发一次全量快照，revision 只在真正发包时 +1（§7.3）。
 */
public final class PerspectiveServer {
    private PerspectiveServer() {}

    private static PerspectiveAttachment state(ServerPlayer player) {
        return player.getData(PerspectiveAttachments.PERSPECTIVE);
    }

    // ------------------------------------------------------------------ 门面

    /** push 一条指令；同 id 旧指令被替换（§5.2）。返回带 seq 的最终指令。 */
    public static CameraDirective push(ServerPlayer target, String id, int priority, Enforcement enforcement,
                                       EnumSet<CameraChannel> channels, CameraTarget value,
                                       int durationTicks, boolean requiresAck, boolean keepOnDeath) {
        PerspectiveAttachment state = state(target);
        validate(state.policy(), enforcement, value);
        long seq = state.allocateSeq();
        long now = target.serverLevel().getGameTime();
        CameraDirective directive = new CameraDirective(id, target.getUUID(), seq, priority, enforcement,
                channels, value, 0.0F, 0.0F, Easing.LINEAR, durationTicks, requiresAck, keepOnDeath, now);
        state.directives().removeIf(existing -> existing.id().equals(id));
        state.directives().add(directive);
        state.markDirty();
        return directive;
    }

    public static boolean remove(ServerPlayer target, String id) {
        PerspectiveAttachment state = state(target);
        boolean removed = state.directives().removeIf(directive -> directive.id().equals(id));
        if (removed) {
            state.markDirty();
        }
        return removed;
    }

    public static int clear(ServerPlayer target) {
        PerspectiveAttachment state = state(target);
        int size = state.directives().size();
        state.directives().clear();
        if (size > 0) {
            state.markDirty();
        }
        return size;
    }

    /** 由命令层修改已有指令（如 lock 位）时使用：同 id 替换并置 dirty。 */
    public static void replace(ServerPlayer target, CameraDirective directive) {
        PerspectiveAttachment state = state(target);
        state.directives().removeIf(existing -> existing.id().equals(directive.id()));
        state.directives().add(directive);
        state.markDirty();
    }

    /** 供命令层分配 seq（与 push 使用同一个计数器）。 */
    public static long nextSeq(ServerPlayer target) {
        return state(target).allocateSeq();
    }

    /** 设置服务端策略（§6.5；命令/外部 API 用）。 */
    public static void setPolicy(ServerPlayer target, PerspectivePolicy policy) {
        state(target).setPolicy(policy);
    }

    public static List<CameraDirective> directives(ServerPlayer target) {
        return List.copyOf(state(target).directives());
    }

    public static long revision(ServerPlayer target) {
        return state(target).revision();
    }

    public static PerspectivePolicy policy(ServerPlayer target) {
        return state(target).policy();
    }

    // ------------------------------------------------------------------ tick 与事件

    /** 每 tick：清理到期指令；有变化（或命令刚改过）则 revision+1 并发一次全量快照。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            PerspectiveAttachment state = state(player);
            boolean changed = state.directives().removeIf(directive -> directive.expired(now));
            if (changed) {
                state.markDirty();
            }
            if (state.dirty()) {
                state.clearDirty();
                sendSnapshot(player, state);
            }
        }
    }

    private static void sendSnapshot(ServerPlayer player, PerspectiveAttachment state) {
        state.setRevision(state.revision() + 1);
        CameraSnapshotPayload payload = new CameraSnapshotPayload(state.revision(), state.hudVisible(),
                List.copyOf(state.directives()));
        PerspectiveNetwork.sendSnapshot(player, payload);
        ModularPerspective.LOGGER.info("[perspective] snapshot sent to {} revision={} directives={}",
                player.getGameProfile().getName(), state.revision(), payload.directives().size());
    }

    /** 登录/重生/换维度后强制补发全量快照（§7.3 发送时机）。 */
    public static void sendFullSnapshot(ServerPlayer player) {
        PerspectiveAttachment state = state(player);
        state.clearDirty();
        sendSnapshot(player, state);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullSnapshot(player);
        }
    }

    /**
     * 登出不再清状态：{@link PerspectiveAttachment} 会随玩家存档保存（§6.1）。
     * 这里只清掉“脏”标记，避免下个 tick 对离线玩家发快照。
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            state(player).clearDirty();
        }
    }

    /** 重生：keepOnDeath=false 的指令以 OWNER_DIED 结束，然后补发全量快照（§6.1）。 */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        PerspectiveAttachment state = state(player);
        List<CameraDirective> ended = new ArrayList<>();
        state.directives().removeIf(directive -> {
            if (directive.keepOnDeath()) {
                return false;
            }
            ended.add(directive);
            return true;
        });
        for (CameraDirective directive : ended) {
            ModularPerspective.LOGGER.info("[perspective] directive {} ended ({}) after respawn of {}",
                    directive.id(), EndReason.OWNER_DIED, player.getGameProfile().getName());
        }
        if (!ended.isEmpty()) {
            state.markDirty();
        }
        sendFullSnapshot(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullSnapshot(player);
        }
    }

    /** 客户端到位回执：只校验 id/seq 仍存在，不修改栈（§7.4）。 */
    public static void onArrived(ServerPlayer player, CameraArrivedPayload payload) {
        PerspectiveAttachment state = state(player);
        boolean known = state.directives().stream()
                .anyMatch(directive -> directive.id().equals(payload.id()) && directive.seq() == payload.seq());
        if (!known) {
            ModularPerspective.LOGGER.debug("[perspective] arrived for unknown directive {} seq={}", payload.id(), payload.seq());
            return;
        }
        ModularPerspective.LOGGER.info("[perspective] arrived {} seq={} from {} (revision={})",
                payload.id(), payload.seq(), player.getGameProfile().getName(), payload.revision());
    }

    // ------------------------------------------------------------------ 策略

    private static void validate(PerspectivePolicy policy, Enforcement enforcement, CameraTarget value) {
        if (enforcement == Enforcement.REQUIRE && !policy.allowRequire()) {
            throw new IllegalArgumentException("policy: REQUIRE is not allowed");
        }
        if (enforcement == Enforcement.SUGGEST && !policy.allowSuggest()) {
            throw new IllegalArgumentException("policy: SUGGEST is not allowed");
        }
        if (value.projection() == Projection.ORTHOGRAPHIC && !policy.allowOrthographic()) {
            throw new IllegalArgumentException("policy: orthographic projection is not allowed");
        }
        if (value.distance() != null && value.distance() > policy.maxDistance()) {
            throw new IllegalArgumentException("policy: distance exceeds max " + policy.maxDistance());
        }
    }
}
