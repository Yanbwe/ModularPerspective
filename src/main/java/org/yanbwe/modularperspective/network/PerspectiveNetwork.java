package org.yanbwe.modularperspective.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.server.PerspectiveServer;

/**
 * 双端网络注册（docs/design.md §7.1、§7.4）。
 *
 * <p>全部 payload 走 {@code optional()}：原版客户端连模组服 / 模组客户端连原版服都不应握手失败。
 * Neo↔Neo 之间的协议版本必须完全一致（§7.5），版本号随线格式变更递增。
 */
public final class PerspectiveNetwork {
    private static final String PROTOCOL_VERSION = "2";

    private PerspectiveNetwork() {}

    /** 公共侧（mod bus）：服务端处理客户端回执。 */
    public static void registerCommon(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(CameraArrivedPayload.TYPE, CameraArrivedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PerspectiveServer.onArrived(player, payload);
                    }
                }));
        registrar.playToServer(CameraOverriddenPayload.TYPE, CameraOverriddenPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PerspectiveServer.onOverridden(player, payload);
                    }
                }));
        registrar.playToServer(CameraRefusedPayload.TYPE, CameraRefusedPayload.CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        PerspectiveServer.onRefused(player, payload);
                    }
                }));
    }

    /** 给单个玩家发全量快照；客户端未声明 channel 时静默跳过（§7.1 发送前自检）。 */
    public static void sendSnapshot(ServerPlayer player, CameraSnapshotPayload payload) {
        if (!player.connection.hasChannel(CameraSnapshotPayload.TYPE)) {
            ModularPerspective.LOGGER.debug("[perspective] skip snapshot to {} (no channel)", player.getGameProfile().getName());
            return;
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** 客户端回执上行。 */
    public static void sendArrived(String id, long seq, long revision) {
        PacketDistributor.sendToServer(new CameraArrivedPayload(id, seq, revision));
    }

    /** 客户端上报“SUGGEST 锁被玩家操作顶掉”（§5.3）。 */
    public static void sendOverridden(String id, java.util.EnumSet<org.yanbwe.modularperspective.api.camera.InputLock> locks) {
        PacketDistributor.sendToServer(new CameraOverriddenPayload(id, locks));
    }

    /** 客户端上报“拒绝执行某条服务端指令”（§5.4）。 */
    public static void sendRefused(String id, org.yanbwe.modularperspective.api.camera.EndReason reason) {
        PacketDistributor.sendToServer(new CameraRefusedPayload(id, reason));
    }
}
