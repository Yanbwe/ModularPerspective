package org.yanbwe.modularperspective.network;

import java.util.EnumSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.InputLock;

/**
 * 客户端 → 服务端：玩家操作顶掉了某条 SUGGEST 指令的锁位（docs/design.md §5.3、§7.4）。
 *
 * <p>玩家按轴操作（旋转/缩放/移动）时，客户端把该轴对应的 SUGGEST 锁位从本地栈中移除，
 * 并发送一次本包；服务端据此更新同 id 指令的锁位并置 dirty（随后全量快照会带回新状态）。
 * REQUIRE 锁位不会触发本包（玩家无法清除 REQUIRE）。
 */
public record CameraOverriddenPayload(String id, EnumSet<InputLock> locks) implements CustomPacketPayload {
    public static final Type<CameraOverriddenPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModularPerspective.MODID, "camera_overridden"));

    public static final StreamCodec<FriendlyByteBuf, CameraOverriddenPayload> CODEC =
            StreamCodec.of(CameraOverriddenPayload::encode, CameraOverriddenPayload::decode);

    public CameraOverriddenPayload {
        locks = locks.isEmpty() ? EnumSet.noneOf(InputLock.class) : EnumSet.copyOf(locks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, CameraOverriddenPayload payload) {
        buf.writeUtf(payload.id);
        buf.writeVarInt(CameraSnapshotPayload.lockMask(payload.locks));
    }

    private static CameraOverriddenPayload decode(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        EnumSet<InputLock> locks = CameraSnapshotPayload.locks(buf.readVarInt());
        return new CameraOverriddenPayload(id, locks);
    }
}
