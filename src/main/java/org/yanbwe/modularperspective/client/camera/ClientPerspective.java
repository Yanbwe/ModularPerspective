package org.yanbwe.modularperspective.client.camera;

import com.mojang.blaze3d.vertex.VertexSorting;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.yanbwe.modularperspective.Config;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.client.input.CursorController;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.EndReason;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicy;
import org.yanbwe.modularperspective.api.camera.Projection;
import org.yanbwe.modularperspective.api.camera.ResolvedCameraState;
import org.yanbwe.modularperspective.api.camera.ServerControl;
import org.yanbwe.modularperspective.network.CameraSnapshotPayload;
import org.yanbwe.modularperspective.network.PerspectiveNetwork;

/**
 * 客户端相机状态（docs/design.md §5.6、§8.1、§8.2）。
 *
 * <p>职责：每个客户端 tick 解析一次仲裁 → 对连续通道做平滑 → 在正交时切第三人称（背面，
 * 让本地玩家可见）并让相机绕到实体上方。渲染期各 mixin 只读 {@link #current()}，
 * 不在 mixin 里各自仲裁（§5.6：帧首快照、单一来源）。
 *
 * <p>M1 的 F6 俯视预设是本地层（source=LOCAL，不持久化）的最小验证：
 * {@code POSITION + ANGLE + ZOOM + PROJECTION} 四个通道，pitch=70°、distance=16、正交投影。
 * 服务端指令栈与快照属于 M3（§7）。
 */
public final class ClientPerspective {
    /** 本地俯视预设的指令 id（同 id push = 替换，见 §5.2）。 */
    public static final String PRESET_ID = "modularperspective:top_down";

    /** §4.1 默认预设 / §12 preset top-down：pitch 固定 45°。 */
    public static final float DEFAULT_TOP_DOWN_PITCH = 45.0F;
    /** §12 preset top-down：distance = 正交视口半高 6。 */
    public static final float DEFAULT_TOP_DOWN_DISTANCE = 6.0F;
    /** §9.3 玩家本地缩放的偏好范围（Ctrl+滚轮）。 */
    private static final float MIN_DISTANCE = 2.0F;
    private static final float MAX_DISTANCE = 10.0F;

    public static final float NEAR_PLANE = 0.05F;
    /**
     * M1 起默认进世界就开俯视（docs §16 M1 验收：“进游戏即俯视且能看到角色”）。
     * 正式开关由 §13 的配置接管在 M4；现在先写死，方便 POC/验收；F6 仍可切回原版。
     */
    private static final boolean ENABLED_BY_DEFAULT = true;
    /** 每 tick 平滑系数：0 = 不动，1 = 瞬时（M1 先用指数平滑，M2 再按 §5.6 接入 fade/easing）。 */
    private static final float SMOOTHING = 0.35F;

    private static final CameraStack STACK = new CameraStack();

    @Nullable
    private static ResolvedCameraState applied;
    /** 上一个 tick 的输出，用于渲染期按 partialTick 插值（m02619 平滑修复）。 */
    @Nullable
    private static ResolvedCameraState previous;
    private static boolean topDown;
    private static long presetSeq;
    private static long lastResolvedTick = Long.MIN_VALUE;
    /** 中键拖拽叠加的相机 yaw 偏移（§9.1 中键拖动），只影响本地预设的 ANGLE 通道。 */
    private static float yawOffset;
    /** Ctrl+滚轮调整后的正交半高（§9.1），始终夹在 [MIN_DISTANCE, MAX_DISTANCE]。 */
    private static float presetDistance = DEFAULT_TOP_DOWN_DISTANCE;
    /** 中键拖拽纵向叠加的 pitch 偏移（§9.1），最终 pitch 夹在本地偏好 15°..90°。 */
    private static float pitchOffset;

    private static boolean cameraTypeSaved;
    private static CameraType savedCameraType = CameraType.FIRST_PERSON;
    /** 每次进入世界只应用一次默认开关（退出世界 {@link #reset()} 时清掉）。 */
    private static boolean initialisedForWorld;
    /** 当前快照里“服务端来源”的指令 id（快照全量替换时只移除本集合，本地预设保留，§7.3）。 */
    private static final Set<String> serverDirectiveIds = new HashSet<>();
    /** 已应用的快照 revision（丢弃更旧的包；相等视为强制重发，§7.3）。 */
    private static long snapshotRevision = -1L;
    /** 服务端下发的 HUD 可见性（HUD 通道在 M4 接线，这里先存）。 */
    private static Boolean serverHudVisible;
    /** 服务端快照携带的策略（§6.5）；只用于客户端本地降级/拒绝判断。 */
    private static PerspectivePolicy serverPolicy = PerspectivePolicy.DEFAULT;
    /** 已发送到位回执的指令键（id#seq），避免重复回执（§5.5）。 */
    private static final Set<String> arrivedSent = new HashSet<>();

    private ClientPerspective() {}

    // ------------------------------------------------------------------ 本地预设

    public static void toggleTopDown() {
        setTopDown(!topDown);
    }

    public static void setTopDown(boolean enabled) {
        if (topDown == enabled) {
            return;
        }
        topDown = enabled;
        yawOffset = 0.0F;
        pitchOffset = 0.0F;
        presetDistance = DEFAULT_TOP_DOWN_DISTANCE;
        if (enabled) {
            presetSeq = STACK.allocateSeq();
        } else {
            STACK.remove(PRESET_ID);
            lastResolvedTick = Long.MIN_VALUE;
        }
        ModularPerspective.LOGGER.info("[perspective] top-down preset {}", enabled ? "ENABLED" : "DISABLED");
        // 默认俯视 = 自由光标（§16 M2）：F6 开关同时切换伪解锁；F4 仍可单独再切换。
        CursorController.setUnlocked(enabled);
    }

    public static boolean isTopDown() {
        return topDown;
    }

    /** 中键拖拽：叠加相机 yaw 偏移（度，正 = 向左转）。 */
    public static void addYawOffset(float degrees) {
        yawOffset = Mth.wrapDegrees(yawOffset + degrees);
    }

    /** Ctrl+滚轮缩放（§9.1）：改正交半高，夹在本地偏好范围 2..10。 */
    public static void addDistance(float delta) {
        presetDistance = Mth.clamp(presetDistance + delta, MIN_DISTANCE, MAX_DISTANCE);
    }

    /** 中键拖拽：叠加 pitch 偏移（度，正 = 更俯视），最终夹在 15..90（§9.3）。 */
    public static void addPitchOffset(float degrees) {
        pitchOffset = Mth.clamp(pitchOffset + degrees,
                15.0F - DEFAULT_TOP_DOWN_PITCH, 90.0F - DEFAULT_TOP_DOWN_PITCH);
    }

    /** 当前应用的俯仰角（度，正 = 朝下）。 */
    public static float currentPitch() {
        return Mth.clamp(DEFAULT_TOP_DOWN_PITCH + pitchOffset, 15.0F, 90.0F);
    }

    /** 相机 yaw 相对玩家 yaw 的偏移（度）；§9.2 用它把 WASD 输入旋到屏幕坐标系。 */
    public static float yawOffsetDegrees() {
        return yawOffset;
    }

    /** Home 吸附（§9.1）：yaw 吸附到 ceil(yaw/90)*90 - 45，pitch 固定 45°。 */
    public static void snapAngles() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!topDown || minecraft.player == null) {
            return;
        }
        float cameraYaw = Mth.wrapDegrees(minecraft.player.getViewYRot(1.0F) + yawOffset);
        float snappedYaw = (float) (Math.ceil(cameraYaw / 90.0F) * 90.0F - 45.0F);
        yawOffset = Mth.wrapDegrees(snappedYaw - minecraft.player.getViewYRot(1.0F));
        pitchOffset = 0.0F;
        ModularPerspective.LOGGER.info("[perspective] Home snap: yaw={} offset={}", snappedYaw, yawOffset);
    }

    /** INPUT_LOCK 查询（§9.2：MOVE 只锁水平输入）。 */
    public static boolean isInputLocked(InputLock lock) {
        ResolvedCameraState state = applied;
        return state != null && state.locks(lock);
    }

    /**
     * 玩家尝试操作某个被锁的轴（§5.3、§5.4）：
     * REQUIRE → 返回 true（拦截输入）；SUGGEST → 移除该锁位并上报 Overridden，返回 false（玩家操作生效）。
     * 没有锁来源时返回 false。
     */
    public static boolean consumeLockedInput(InputLock lock) {
        CameraDirective source = STACK.lockSource(lock);
        if (source == null) {
            return false;
        }
        if (source.enforcement() == Enforcement.REQUIRE) {
            return true;
        }
        EnumSet<InputLock> remaining = EnumSet.copyOf(source.target().locks());
        remaining.remove(lock);
        STACK.put(source.withTarget(source.target().withLocks(remaining)));
        lastResolvedTick = Long.MIN_VALUE;
        PerspectiveNetwork.sendOverridden(source.id(), EnumSet.of(lock));
        ModularPerspective.LOGGER.debug("[perspective] SUGGEST lock {} overridden on {} (seq={})",
                lock, source.id(), source.seq());
        return false;
    }

    // ------------------------------------------------------------------ tick / 渲染读取

    /** 客户端 tick 调用（{@code ClientTickEvent.Post}）：解析仲裁、平滑、切相机类型。 */
    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        long tick = minecraft.level.getGameTime();
        if (!initialisedForWorld) {
            initialisedForWorld = true;
            if (ENABLED_BY_DEFAULT) {
                setTopDown(true);
            }
        }
        if (tick == lastResolvedTick) {
            return;
        }
        lastResolvedTick = tick;
        if (topDown) {
            STACK.put(topDownDirective(minecraft, tick));
        }
        ResolvedCameraState target = STACK.resolve(tick);
        previous = applied;
        applied = smooth(applied, target);
        syncCameraType(applied != null && applied.orthographic());
        // F5 保护（M1 验收）：正交下原版 F5 的切换会破坏第三人称背面构图，强制拉回。
        if (isOrtho() && minecraft.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        sendArrivalsIfNeeded();
    }

    /** 当前生效输出（渲染期唯一来源；null = 完全原版）。 */
    @Nullable
    public static ResolvedCameraState current() {
        return applied;
    }

    /**
     * 渲染期读取（m02619 平滑修复）：在上一个 tick 与当前 tick 的输出之间按 partialTick 插值。
     *
     * <p>渲染帧率通常远高于 20 Hz 的客户端 tick；如果 {@code Camera#setup} 直接套用 tick 快照，
     * 旋转/移动会呈现 20 Hz 台阶（“顿一下动一下，两动之间没有过渡”）。这里对连续量做帧级插值：
     * position/pitch/distance/fov 线性，yaw 走最短弧；离散量（投影/锁/HUD）保持当前 tick 值。
     */
    @Nullable
    public static ResolvedCameraState frameState(float partialTick) {
        ResolvedCameraState to = applied;
        if (to == null) {
            return null;
        }
        ResolvedCameraState from = previous;
        if (from == null) {
            return to;
        }
        float delta = Mth.clamp(partialTick, 0.0F, 1.0F);
        Position3 position = to.position() == null ? null
                : from.position() == null ? to.position() : lerp(from.position(), to.position(), delta);
        Float yaw = to.yaw() == null ? null
                : from.yaw() == null ? to.yaw() : smoothAngle(from.yaw(), to.yaw(), delta);
        Float pitch = to.pitch() == null ? null
                : from.pitch() == null ? to.pitch() : lerp(from.pitch(), to.pitch(), delta);
        Float distance = to.distance() == null ? null
                : from.distance() == null ? to.distance() : lerp(from.distance(), to.distance(), delta);
        Float fov = to.fov() == null ? null
                : from.fov() == null ? to.fov() : lerp(from.fov(), to.fov(), delta);
        return new ResolvedCameraState(position, yaw, pitch, distance, fov, to.projection(),
                to.hudVisible(), to.locks());
    }

    /**
     * 应用服务端全量快照（§7.3）：只替换 SERVER 来源的指令，本地预设（F4/F6/Home）保留。
     * 丢弃比已应用更旧的 revision；相等视为强制重发照常应用。
     */
    public static void applySnapshot(CameraSnapshotPayload payload) {
        if (payload.revision() < snapshotRevision) {
            ModularPerspective.LOGGER.debug("[perspective] drop stale snapshot revision={} (applied={})",
                    payload.revision(), snapshotRevision);
            return;
        }
        snapshotRevision = payload.revision();
        serverHudVisible = payload.hudVisible();
        serverPolicy = payload.policy();
        for (String id : serverDirectiveIds) {
            STACK.remove(id);
        }
        serverDirectiveIds.clear();
        ServerControl control = clientServerControl();
        for (CameraDirective directive : payload.directives()) {
            if (control == ServerControl.DISABLED && directive.enforcement() == Enforcement.REQUIRE) {
                PerspectiveNetwork.sendRefused(directive.id(), EndReason.POLICY_REFUSED);
                ModularPerspective.LOGGER.info(
                        "[perspective] refused REQUIRE directive {} (client serverControl=DISABLED)", directive.id());
                continue;
            }
            if (control == ServerControl.SUGGEST_ONLY && directive.enforcement() == Enforcement.REQUIRE) {
                directive = directive.withEnforcement(Enforcement.SUGGEST);
            }
            STACK.put(directive);
            serverDirectiveIds.add(directive.id());
        }
        lastResolvedTick = Long.MIN_VALUE;
        ModularPerspective.LOGGER.info("[perspective] snapshot applied revision={} directives={}",
                payload.revision(), payload.directives().size());
    }
    /** 每 tick 检查到位回执（§5.5）：仍是各通道赢家、误差内、此前未发过。 */
    private static void sendArrivalsIfNeeded() {
        ResolvedCameraState state = applied;
        if (state == null || STACK.directives().isEmpty()) {
            return;
        }
        for (CameraDirective directive : STACK.directives()) {
            if (!directive.requiresAck()) {
                continue;
            }
            String key = directive.id() + "#" + directive.seq();
            if (arrivedSent.contains(key) || !isWinnerForAllChannels(directive)
                    || !withinTolerance(directive.target(), state)) {
                continue;
            }
            arrivedSent.add(key);
            PerspectiveNetwork.sendArrived(directive.id(), directive.seq(), snapshotRevision);
            ModularPerspective.LOGGER.info("[perspective] arrived sent id={} seq={} revision={}",
                    directive.id(), directive.seq(), snapshotRevision);
        }
    }

    private static boolean isWinnerForAllChannels(CameraDirective directive) {
        for (CameraChannel channel : directive.channels()) {
            if (channel == CameraChannel.INPUT_LOCK) {
                continue;
            }
            CameraDirective winner = STACK.winner(channel);
            if (winner == null || !winner.id().equals(directive.id()) || winner.seq() != directive.seq()) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinTolerance(CameraTarget target, ResolvedCameraState state) {
        if (target.position() != null
                && (state.position() == null || state.position().distanceSquared(target.position()) > 1.0E-4)) {
            return false;
        }
        if (target.yaw() != null
                && (state.yaw() == null || Math.abs(Mth.wrapDegrees(state.yaw() - target.yaw())) > 0.1F)) {
            return false;
        }
        if (target.pitch() != null && (state.pitch() == null || Math.abs(state.pitch() - target.pitch()) > 0.1F)) {
            return false;
        }
        if (target.distance() != null
                && (state.distance() == null || Math.abs(state.distance() - target.distance()) > 0.01F)) {
            return false;
        }
        if (target.fov() != null && (state.fov() == null || Math.abs(state.fov() - target.fov()) > 0.1F)) {
            return false;
        }
        if (target.projection() != null && state.projection() != target.projection()) {
            return false;
        }
        if (target.hudVisible() != null && !target.hudVisible().equals(state.hudVisible())) {
            return false;
        }
        return target.locks() == null || state.locks().containsAll(target.locks());
    }

    private static ServerControl clientServerControl() {
        try {
            return Config.SERVER_CONTROL.get();
        } catch (IllegalStateException exception) {
            return ServerControl.ALLOW;
        }
    }

    public static boolean isOrtho() {
        return applied != null && applied.orthographic();
    }

    /**
     * HUD 层是否可见（§10.2）：服务端快照的 {@code hudVisible=false} 时整层跳过；
     * 没有相机状态视为原版 HUD，始终可见。
     */
    public static boolean hudVisible() {
        ResolvedCameraState state = applied;
        if (state == null) {
            return true;
        }
        Boolean visible = state.hudVisible();
        return visible == null || visible;
    }

    /** 仲裁栈当前指令的只读视图（HUD/调试面板展示用，§10.2）。 */
    public static List<CameraDirective> directiveView() {
        return List.copyOf(STACK.directives());
    }

    /** 已应用的服务端快照 revision（-1 = 从未收到，§7.3）。 */
    public static long snapshotRevision() {
        return snapshotRevision;
    }

    /** 服务端快照携带的策略（§6.5），HUD/调试面板只读。 */
    public static PerspectivePolicy serverPolicy() {
        return serverPolicy;
    }

    /** 退出世界/切换维度时清掉本地状态并恢复相机类型。 */
    public static void reset() {
        STACK.clear();
        serverDirectiveIds.clear();
        snapshotRevision = -1L;
        serverHudVisible = null;
        serverPolicy = PerspectivePolicy.DEFAULT;
        arrivedSent.clear();
        applied = null;
        previous = null;
        topDown = false;
        initialisedForWorld = false;
        lastResolvedTick = Long.MIN_VALUE;
        yawOffset = 0.0F;
        pitchOffset = 0.0F;
        presetDistance = DEFAULT_TOP_DOWN_DISTANCE;
        restoreCameraType();
    }

    // ------------------------------------------------------------------ 渲染接线

    /**
     * 正交投影矩阵：halfH = ZOOM 通道 distance，halfW = aspect*halfH，near=0.05，far = 渲染距离区块（§8.2）。
     *
     * <p>far 原按 {@code getDepthFar()}（renderDistance*64）取，正交盒沿视线过长会让转视角时渲染的 section
     * 数量暴涨而掉帧（m02434）；这里只覆盖实际渲染距离，远处由雾/清屏色收边。
     */
    public static Matrix4f projectionMatrix(ResolvedCameraState state) {
        Minecraft minecraft = Minecraft.getInstance();
        double aspect = (double) minecraft.getWindow().getWidth() / (double) minecraft.getWindow().getHeight();
        float halfHeight = state.distance() != null ? state.distance() : DEFAULT_TOP_DOWN_DISTANCE;
        float halfWidth = (float) (aspect * halfHeight);
        // m02434 性能修正：原用 getDepthFar()（=renderDistance*64，12 区块时 768），正交盒沿视线拉得过长，
        // 包含大量 section 导致转视角掉帧；正交只需要看到渲染距离内的地形，far 收到 renderDistance 区块。
        float far = Math.max(minecraft.options.getEffectiveRenderDistance() * 16.0F, halfHeight + 32.0F);
        return new Matrix4f().ortho(-halfWidth, halfWidth, -halfHeight, halfHeight, NEAR_PLANE, far);
    }

    /**
     * 按相机视线深度排序（docs/design.md §8.2、A-14）。
     *
     * <p>{@link VertexSorting#byDistance} 是“排序值降序 = 远的先画”，所以取 {@code -dot(forward, vertex)}；
     * 顶点是相机相对坐标，因此不能用固定世界 -Z 的 {@code ORTHOGRAPHIC_Z}。
     */
    public static VertexSorting viewSorting() {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f forward = camera.getLookVector();
        return VertexSorting.byDistance(vector -> -forward.dot(vector));
    }

    /** FOV 通道接线（§8.2：不 mixin getFov，走 NeoForge 事件）；正交下仍然生效但对画面无意义。 */
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        ResolvedCameraState state = applied;
        if (state != null && state.hasFov()) {
            event.setFOV(state.fov());
        }
    }

    // ------------------------------------------------------------------ 内部

    private static CameraDirective topDownDirective(Minecraft minecraft, long tick) {
        Entity player = minecraft.player;
        float yaw = Mth.wrapDegrees(player.getViewYRot(1.0F) + yawOffset);
        float pitch = currentPitch();
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 eye = player.getEyePosition(1.0F);
        Position3 cameraPosition = new Position3(
                eye.x - forward.x * presetDistance,
                eye.y - forward.y * presetDistance,
                eye.z - forward.z * presetDistance);
        CameraTarget target = new CameraTarget(cameraPosition, yaw, pitch,
                presetDistance, null, Projection.ORTHOGRAPHIC, null, null);
        EnumSet<CameraChannel> channels = EnumSet.of(CameraChannel.POSITION, CameraChannel.ANGLE,
                CameraChannel.ZOOM, CameraChannel.PROJECTION);
        return new CameraDirective(PRESET_ID, player.getUUID(), presetSeq, 100, Enforcement.SUGGEST, channels, target,
                0.0F, 0.0F, Easing.LINEAR, CameraDirective.UNLIMITED, false, false, tick);
    }

    /**
     * 帧间平滑：连续通道向目标靠近；目标通道为 null 时立即释放（回到原版）。
     * 投影/锁/HUD 为离散量，直接取目标。
     */
    @Nullable
    private static ResolvedCameraState smooth(@Nullable ResolvedCameraState previous, @Nullable ResolvedCameraState next) {
        if (next == null) {
            return null;
        }
        if (previous == null) {
            return next;
        }
        Position3 position = next.position() == null ? null : lerp(previous.position(), next.position());
        Float yaw = next.yaw() == null ? null : smoothAngle(previous.yaw(), next.yaw());
        Float pitch = next.pitch() == null ? null : lerp(previous.pitch(), next.pitch());
        Float distance = next.distance() == null ? null : lerp(previous.distance(), next.distance());
        Float fov = next.fov() == null ? null : lerp(previous.fov(), next.fov());
        Projection projection = next.projection() != null ? next.projection() : previous.projection();
        Boolean hudVisible = next.hudVisible() != null ? next.hudVisible() : previous.hudVisible();
        EnumSet<InputLock> locks = next.locks();
        return new ResolvedCameraState(position, yaw, pitch, distance, fov, projection, hudVisible, locks);
    }

    private static Position3 lerp(@Nullable Position3 from, Position3 to) {
        if (from == null) {
            return to;
        }
        return new Position3(Mth.lerp(SMOOTHING, (float) from.x(), (float) to.x()),
                Mth.lerp(SMOOTHING, (float) from.y(), (float) to.y()),
                Mth.lerp(SMOOTHING, (float) from.z(), (float) to.z()));
    }

    /** 帧级插值（partialTick 0..1）：position 线性（m02619）。 */
    private static Position3 lerp(Position3 from, Position3 to, float delta) {
        return new Position3(Mth.lerp(delta, (float) from.x(), (float) to.x()),
                Mth.lerp(delta, (float) from.y(), (float) to.y()),
                Mth.lerp(delta, (float) from.z(), (float) to.z()));
    }

    private static float lerp(@Nullable Float from, float to) {
        return from == null ? to : Mth.lerp(SMOOTHING, from, to);
    }

    /** 帧级插值（partialTick 0..1）：标量通道线性（m02619）。 */
    private static float lerp(float from, float to, float delta) {
        return Mth.lerp(delta, from, to);
    }

    /** 角度走最短弧（§5.6：ANGLE 通道插值取最短弧）。 */
    private static float smoothAngle(@Nullable Float from, float to) {
        if (from == null) {
            return to;
        }
        return from + Mth.wrapDegrees(to - from) * SMOOTHING;
    }

    /** 帧级插值（partialTick 0..1）：yaw 走最短弧（m02619）。 */
    private static float smoothAngle(float from, float to, float delta) {
        return from + Mth.wrapDegrees(to - from) * delta;
    }

    /**
     * 正交时切第三人称背面（让本地玩家可见，§8.2、A-11）：先 {@code checkEntityPostEffect} 再
     * {@code needsUpdate}，顺序与原版 F5 路径一致；退出时恢复原相机类型。
     */
    private static void syncCameraType(boolean ortho) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ortho) {
            if (!cameraTypeSaved) {
                savedCameraType = minecraft.options.getCameraType();
                cameraTypeSaved = true;
                minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                minecraft.gameRenderer.checkEntityPostEffect(minecraft.getCameraEntity());
                minecraft.levelRenderer.needsUpdate();
            }
        } else {
            restoreCameraType();
        }
    }

    private static void restoreCameraType() {
        if (!cameraTypeSaved) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        cameraTypeSaved = false;
        minecraft.options.setCameraType(savedCameraType);
        minecraft.gameRenderer.checkEntityPostEffect(
                minecraft.options.getCameraType().isFirstPerson() ? minecraft.getCameraEntity() : null);
        minecraft.levelRenderer.needsUpdate();
    }
}
