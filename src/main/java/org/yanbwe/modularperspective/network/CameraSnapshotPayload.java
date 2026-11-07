package org.yanbwe.modularperspective.network;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.Projection;

/**
 * 服务端 → 客户端全量相机快照（docs/design.md §7.3）。
 *
 * <p>唯一的下行状态包：客户端收到后只替换“服务端来源”的指令，本地预设（F4/F6/Home）保留。
 * revision 单调递增；客户端丢弃 {@code revision <} 已应用的包，相等视为强制重发照常应用。
 */
public record CameraSnapshotPayload(
        long revision,
        boolean hudVisible,
        List<CameraDirective> directives) implements CustomPacketPayload {

    public static final Type<CameraSnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModularPerspective.MODID, "camera_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, CameraSnapshotPayload> CODEC =
            StreamCodec.of(CameraSnapshotPayload::encode, CameraSnapshotPayload::decode);

    public CameraSnapshotPayload {
        directives = List.copyOf(directives);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void encode(FriendlyByteBuf buf, CameraSnapshotPayload payload) {
        buf.writeVarLong(payload.revision);
        buf.writeBoolean(payload.hudVisible);
        buf.writeVarInt(payload.directives.size());
        for (CameraDirective directive : payload.directives) {
            writeDirective(buf, directive);
        }
    }

    private static CameraSnapshotPayload decode(FriendlyByteBuf buf) {
        long revision = buf.readVarLong();
        boolean hudVisible = buf.readBoolean();
        int size = buf.readVarInt();
        List<CameraDirective> directives = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            directives.add(readDirective(buf));
        }
        return new CameraSnapshotPayload(revision, hudVisible, directives);
    }

    // ------------------------------------------------------------------ 指令编码

    static void writeDirective(FriendlyByteBuf buf, CameraDirective directive) {
        buf.writeUtf(directive.id());
        buf.writeBoolean(directive.owner() != null);
        if (directive.owner() != null) {
            buf.writeUUID(directive.owner());
        }
        buf.writeLong(directive.seq());
        buf.writeVarInt(directive.priority());
        buf.writeEnum(directive.enforcement());
        buf.writeVarInt(channelMask(directive.channels()));
        writeTarget(buf, directive.target());
        buf.writeFloat(directive.fadeInTicks());
        buf.writeFloat(directive.fadeOutTicks());
        buf.writeEnum(directive.easing());
        buf.writeInt(directive.durationTicks());
        buf.writeBoolean(directive.requiresAck());
        buf.writeBoolean(directive.keepOnDeath());
        buf.writeVarLong(directive.startTick());
    }

    static CameraDirective readDirective(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        UUID owner = buf.readBoolean() ? buf.readUUID() : null;
        long seq = buf.readLong();
        int priority = buf.readVarInt();
        Enforcement enforcement = buf.readEnum(Enforcement.class);
        EnumSet<CameraChannel> channels = channels(buf.readVarInt());
        CameraTarget target = readTarget(buf);
        float fadeInTicks = buf.readFloat();
        float fadeOutTicks = buf.readFloat();
        Easing easing = buf.readEnum(Easing.class);
        int durationTicks = buf.readInt();
        boolean requiresAck = buf.readBoolean();
        boolean keepOnDeath = buf.readBoolean();
        long startTick = buf.readVarLong();
        return new CameraDirective(id, owner, seq, priority, enforcement, channels, target,
                fadeInTicks, fadeOutTicks, easing, durationTicks, requiresAck, keepOnDeath, startTick);
    }

    private static void writeTarget(FriendlyByteBuf buf, CameraTarget target) {
        buf.writeBoolean(target.position() != null);
        if (target.position() != null) {
            buf.writeDouble(target.position().x());
            buf.writeDouble(target.position().y());
            buf.writeDouble(target.position().z());
        }
        writeNullableFloat(buf, target.yaw());
        writeNullableFloat(buf, target.pitch());
        writeNullableFloat(buf, target.distance());
        writeNullableFloat(buf, target.fov());
        buf.writeBoolean(target.projection() != null);
        if (target.projection() != null) {
            buf.writeEnum(target.projection());
        }
        buf.writeBoolean(target.hudVisible() != null);
        if (target.hudVisible() != null) {
            buf.writeBoolean(target.hudVisible());
        }
        buf.writeBoolean(target.locks() != null);
        if (target.locks() != null) {
            buf.writeVarInt(lockMask(target.locks()));
        }
    }

    private static CameraTarget readTarget(FriendlyByteBuf buf) {
        Position3 position = null;
        if (buf.readBoolean()) {
            position = new Position3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        Float yaw = readNullableFloat(buf);
        Float pitch = readNullableFloat(buf);
        Float distance = readNullableFloat(buf);
        Float fov = readNullableFloat(buf);
        Projection projection = buf.readBoolean() ? buf.readEnum(Projection.class) : null;
        Boolean hudVisible = buf.readBoolean() ? buf.readBoolean() : null;
        EnumSet<InputLock> locks = buf.readBoolean() ? locks(buf.readVarInt()) : null;
        return new CameraTarget(position, yaw, pitch, distance, fov, projection, hudVisible, locks);
    }

    private static void writeNullableFloat(FriendlyByteBuf buf, @Nullable Float value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            buf.writeFloat(value);
        }
    }

    @Nullable
    private static Float readNullableFloat(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readFloat() : null;
    }

    static int channelMask(EnumSet<CameraChannel> channels) {
        int mask = 0;
        for (CameraChannel channel : channels) {
            mask |= 1 << channel.ordinal();
        }
        return mask;
    }

    static EnumSet<CameraChannel> channels(int mask) {
        EnumSet<CameraChannel> set = EnumSet.noneOf(CameraChannel.class);
        for (CameraChannel channel : CameraChannel.values()) {
            if ((mask & (1 << channel.ordinal())) != 0) {
                set.add(channel);
            }
        }
        return set;
    }

    static int lockMask(EnumSet<InputLock> locks) {
        int mask = 0;
        for (InputLock lock : locks) {
            mask |= 1 << lock.ordinal();
        }
        return mask;
    }

    static EnumSet<InputLock> locks(int mask) {
        EnumSet<InputLock> set = EnumSet.noneOf(InputLock.class);
        for (InputLock lock : InputLock.values()) {
            if ((mask & (1 << lock.ordinal())) != 0) {
                set.add(lock);
            }
        }
        return set;
    }
}
