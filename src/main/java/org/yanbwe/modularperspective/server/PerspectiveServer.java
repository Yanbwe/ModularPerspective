package org.yanbwe.modularperspective.server;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicy;
import org.yanbwe.modularperspective.api.camera.Projection;
import org.yanbwe.modularperspective.network.CameraArrivedPayload;
import org.yanbwe.modularperspective.network.CameraSnapshotPayload;
import org.yanbwe.modularperspective.network.PerspectiveNetwork;

/**
 * 服务端指令栈与快照（docs/design.md §6.1、§6.2、§7.3）。
 *
 * <p>M3 首版用内存 map 按玩家 UUID 保存（等价于 attachment 的最小实现；持久化与 copyOnDeath 的
 * 序列化在 M4 接入，届时改成 {@code AttachmentType} 并补 Codec）。变更有两类触发：
 * 每台服 tick 检查到期，以及命令/API 显式修改时置 {@code dirty}；每个 tick 末最多发一次全量快照，
 * revision 只在真正发包时 +1（§7.3）。
 */
public final class PerspectiveServer {
    private static final Map<UUID, State> STATES = new HashMap<>();

    private PerspectiveServer() {}

    /** 每个玩家一条栈 + revision/seq 分配器。 */
    private static final class State {
        final List<CameraDirective> directives = new ArrayList<>();
        PerspectivePolicy policy = PerspectivePolicy.DEFAULT;
        boolean hudVisible = true;
        long revision;
        long nextSeq;
        boolean dirty;
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
    }

    // ------------------------------------------------------------------ 门面

    /** push 一条指令；同 id 旧指令被替换（§5.2）。返回带 seq 的最终指令。 */
    public static CameraDirective push(ServerPlayer target, String id, int priority, Enforcement enforcement,
                                       EnumSet<CameraChannel> channels, CameraTarget value,
                                       int durationTicks, boolean requiresAck, boolean keepOnDeath) {
        State state = state(target);
        validate(state.policy, enforcement, value);
        long seq = ++state.nextSeq;
        long now = target.serverLevel().getGameTime();
        CameraDirective directive = new CameraDirective(id, target.getUUID(), seq, priority, enforcement,
                channels, value, 0.0F, 0.0F, Easing.LINEAR, durationTicks, requiresAck, keepOnDeath, now);
        state.directives.removeIf(existing -> existing.id().equals(id));
        state.directives.add(directive);
        state.dirty = true;
        return directive;
    }

    public static boolean remove(ServerPlayer target, String id) {
        State state = state(target);
        boolean removed = state.directives.removeIf(directive -> directive.id().equals(id));
        if (removed) {
            state.dirty = true;
        }
        return removed;
    }

    public static int clear(ServerPlayer target) {
        State state = state(target);
        int size = state.directives.size();
        state.directives.clear();
        if (size > 0) {
            state.dirty = true;
        }
        return size;
    }

    public static List<CameraDirective> directives(ServerPlayer target) {
        return List.copyOf(state(target).directives);
    }

    public static long revision(ServerPlayer target) {
        return state(target).revision;
    }

    public static PerspectivePolicy policy(ServerPlayer target) {
        return state(target).policy;
    }

    // ------------------------------------------------------------------ tick 与事件

    /** 每 tick：清理到期指令；有变化（或命令刚改过）则 revision+1 并发一次全量快照。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        long now = server.overworld().getGameTime();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            State state = STATES.get(player.getUUID());
            if (state == null) {
                continue;
            }
            boolean changed = state.directives.removeIf(directive -> directive.expired(now));
            if (changed || state.dirty) {
                state.dirty = false;
                sendSnapshot(player, state);
            }
        }
    }

    private static void sendSnapshot(ServerPlayer player, State state) {
        state.revision++;
        CameraSnapshotPayload payload = new CameraSnapshotPayload(state.revision, state.hudVisible,
                List.copyOf(state.directives));
        PerspectiveNetwork.sendSnapshot(player, payload);
        ModularPerspective.LOGGER.info("[perspective] snapshot sent to {} revision={} directives={}",
                player.getGameProfile().getName(), state.revision, payload.directives().size());
    }

    /** 登录/重生/换维度后强制补发全量快照（§7.3 发送时机）。 */
    public static void sendFullSnapshot(ServerPlayer player) {
        State state = state(player);
        state.dirty = false;
        sendSnapshot(player, state);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullSnapshot(player);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            STATES.remove(player.getUUID());
        }
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullSnapshot(player);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendFullSnapshot(player);
        }
    }

    /** 客户端到位回执：只校验 id/seq 仍存在，不修改栈（§7.4）。 */
    public static void onArrived(ServerPlayer player, CameraArrivedPayload payload) {
        State state = STATES.get(player.getUUID());
        if (state == null) {
            return;
        }
        boolean known = state.directives.stream()
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
