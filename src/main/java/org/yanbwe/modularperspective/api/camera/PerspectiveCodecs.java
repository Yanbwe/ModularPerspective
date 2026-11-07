package org.yanbwe.modularperspective.api.camera;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * 相机数据结构的持久化 Codec（docs/design.md §6.1）。
 *
 * <p>网络层用 {@code StreamCodec}（见 {@code network/}），玩家存档用这里的数据 Codec。
 * 枚举统一按名字序列化，未知名字解码为 {@code null}/{@code Optional.empty}，随后由结构默认值兜底。
 */
public final class PerspectiveCodecs {
    public static final Codec<CameraChannel> CHANNEL = enumCodec(CameraChannel.values());
    public static final Codec<Enforcement> ENFORCEMENT = enumCodec(Enforcement.values());
    public static final Codec<Easing> EASING = enumCodec(Easing.values());
    public static final Codec<Projection> PROJECTION = enumCodec(Projection.values());
    public static final Codec<InputLock> INPUT_LOCK = enumCodec(InputLock.values());

    public static final Codec<Position3> POSITION = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(Position3::x),
            Codec.DOUBLE.fieldOf("y").forGetter(Position3::y),
            Codec.DOUBLE.fieldOf("z").forGetter(Position3::z)
    ).apply(instance, Position3::new));

    public static final Codec<EnumSet<CameraChannel>> CHANNELS = CHANNEL.listOf().xmap(
            list -> {
                EnumSet<CameraChannel> set = EnumSet.noneOf(CameraChannel.class);
                set.addAll(list);
                return set;
            },
            List::copyOf);

    public static final Codec<EnumSet<InputLock>> LOCKS = INPUT_LOCK.listOf().xmap(
            list -> {
                EnumSet<InputLock> set = EnumSet.noneOf(InputLock.class);
                set.addAll(list);
                return set;
            },
            List::copyOf);

    public static final Codec<CameraTarget> TARGET = RecordCodecBuilder.create(instance -> instance.group(
            POSITION.optionalFieldOf("position").forGetter(target -> Optional.ofNullable(target.position())),
            Codec.FLOAT.optionalFieldOf("yaw").forGetter(target -> Optional.ofNullable(target.yaw())),
            Codec.FLOAT.optionalFieldOf("pitch").forGetter(target -> Optional.ofNullable(target.pitch())),
            Codec.FLOAT.optionalFieldOf("distance").forGetter(target -> Optional.ofNullable(target.distance())),
            Codec.FLOAT.optionalFieldOf("fov").forGetter(target -> Optional.ofNullable(target.fov())),
            PROJECTION.optionalFieldOf("projection").forGetter(target -> Optional.ofNullable(target.projection())),
            Codec.BOOL.optionalFieldOf("hud_visible").forGetter(target -> Optional.ofNullable(target.hudVisible())),
            LOCKS.optionalFieldOf("locks").forGetter(target -> Optional.ofNullable(target.locks()))
    ).apply(instance, (position, yaw, pitch, distance, fov, projection, hudVisible, locks) -> new CameraTarget(
            position.orElse(null), yaw.orElse(null), pitch.orElse(null), distance.orElse(null), fov.orElse(null),
            projection.orElse(null), hudVisible.orElse(null), locks.orElse(null))));

    public static final Codec<CameraDirective> DIRECTIVE = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(CameraDirective::id),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(directive -> Optional.ofNullable(directive.owner())),
            Codec.LONG.fieldOf("seq").forGetter(CameraDirective::seq),
            Codec.INT.fieldOf("priority").forGetter(CameraDirective::priority),
            ENFORCEMENT.fieldOf("enforcement").forGetter(CameraDirective::enforcement),
            CHANNELS.fieldOf("channels").forGetter(CameraDirective::channels),
            TARGET.fieldOf("target").forGetter(CameraDirective::target),
            Codec.FLOAT.optionalFieldOf("fade_in", 0.0F).forGetter(CameraDirective::fadeInTicks),
            Codec.FLOAT.optionalFieldOf("fade_out", 0.0F).forGetter(CameraDirective::fadeOutTicks),
            EASING.optionalFieldOf("easing", Easing.LINEAR).forGetter(CameraDirective::easing),
            Codec.INT.fieldOf("duration").forGetter(CameraDirective::durationTicks),
            Codec.BOOL.optionalFieldOf("requires_ack", false).forGetter(CameraDirective::requiresAck),
            Codec.BOOL.optionalFieldOf("keep_on_death", false).forGetter(CameraDirective::keepOnDeath),
            Codec.LONG.fieldOf("start_tick").forGetter(CameraDirective::startTick)
    ).apply(instance, (id, owner, seq, priority, enforcement, channels, target, fadeIn, fadeOut, easing,
                       duration, requiresAck, keepOnDeath, startTick) -> new CameraDirective(id, owner.orElse(null),
            seq, priority, enforcement, channels, target, fadeIn, fadeOut, easing, duration, requiresAck,
            keepOnDeath, startTick)));

    public static final Codec<PerspectivePolicy> POLICY = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("allow_require", true).forGetter(PerspectivePolicy::allowRequire),
            Codec.BOOL.optionalFieldOf("allow_suggest", true).forGetter(PerspectivePolicy::allowSuggest),
            Codec.INT.optionalFieldOf("max_directives", 64).forGetter(PerspectivePolicy::maxDirectives),
            Codec.FLOAT.optionalFieldOf("max_distance", 32.0F).forGetter(PerspectivePolicy::maxDistance),
            Codec.BOOL.optionalFieldOf("allow_orthographic", true).forGetter(PerspectivePolicy::allowOrthographic)
    ).apply(instance, PerspectivePolicy::new));

    private PerspectiveCodecs() {}

    private static <E extends Enum<E>> Codec<E> enumCodec(E[] values) {
        return Codec.STRING.comapFlatMap(name -> {
            for (E value : values) {
                if (value.name().equals(name)) {
                    return com.mojang.serialization.DataResult.success(value);
                }
            }
            return com.mojang.serialization.DataResult.error(() -> "unknown enum name: " + name);
        }, Enum::name);
    }

    /** 便于测试：把枚举集合展开成有序列表。 */
    public static <E extends Enum<E>> List<E> asList(EnumSet<E> set) {
        return new ArrayList<>(set);
    }
}
