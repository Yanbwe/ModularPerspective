package org.yanbwe.modularperspective.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.EndReason;

/**
 * 客户端 → 服务端：客户端拒绝了一条服务端指令（docs/design.md §5.4、§7.4）。
 *
 * <p>示例：客户端把 {@code serverControl} 设为 {@code DISABLED}，收到 REQUIRE 指令时不应执行，
 * 而以 {@link EndReason#POLICY_REFUSED} 结束它；服务端只记录 + 移除，不因此报错或断开。
 */
public record CameraRefusedPayload(String id, EndReason reason) implements CustomPacketPayload {
    public static final Type<CameraRefusedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModularPerspective.MODID, "camera_refused"));

    public static final StreamCodec<FriendlyByteBuf, CameraRefusedPayload> CODEC =
            StreamCodec.of(CameraRefusedPayload::encode, CameraRefusedPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, CameraRefusedPayload payload) {
        buf.writeUtf(payload.id);
        buf.writeEnum(payload.reason);
    }

    private static CameraRefusedPayload decode(FriendlyByteBuf buf) {
        return new CameraRefusedPayload(buf.readUtf(), buf.readEnum(EndReason.class));
    }
}
