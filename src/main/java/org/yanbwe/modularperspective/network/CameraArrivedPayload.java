package org.yanbwe.modularperspective.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularperspective.ModularPerspective;

/**
 * 客户端 → 服务端到位回执（docs/design.md §5.5、§7.4）。
 *
 * <p>服务端只校验 id/seq/revision 并派发事件，不修改栈；未知 id/非法字段一律丢弃 + debug。
 */
public record CameraArrivedPayload(String id, long seq, long revision) implements CustomPacketPayload {

    public static final Type<CameraArrivedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModularPerspective.MODID, "camera_arrived"));

    public static final StreamCodec<FriendlyByteBuf, CameraArrivedPayload> CODEC =
            StreamCodec.of(CameraArrivedPayload::encode, CameraArrivedPayload::decode);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, CameraArrivedPayload payload) {
        buf.writeUtf(payload.id);
        buf.writeLong(payload.seq);
        buf.writeLong(payload.revision);
    }

    private static CameraArrivedPayload decode(FriendlyByteBuf buf) {
        return new CameraArrivedPayload(buf.readUtf(), buf.readLong(), buf.readLong());
    }
}
