package org.yanbwe.modularperspective.client;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.yanbwe.modularperspective.client.camera.ClientPerspective;
import org.yanbwe.modularperspective.network.CameraSnapshotPayload;

/**
 * 客户端侧网络注册（docs/design.md §7.1、§7.3）：只注册下行快照。
 *
 * <p>单独放在 client 包，保证 dedicated server 不会加载客户端相机类。
 */
public final class PerspectiveClientNetwork {
    private static final String PROTOCOL_VERSION = "1";

    private PerspectiveClientNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL_VERSION).optional()
                .playToClient(CameraSnapshotPayload.TYPE, CameraSnapshotPayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> ClientPerspective.applySnapshot(payload)));
    }
}
