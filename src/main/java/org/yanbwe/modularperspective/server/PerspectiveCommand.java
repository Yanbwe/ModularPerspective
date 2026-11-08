package org.yanbwe.modularperspective.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.yanbwe.modularperspective.ModularPerspective;
import org.yanbwe.modularperspective.api.camera.CameraChannel;
import org.yanbwe.modularperspective.api.camera.CameraDirective;
import org.yanbwe.modularperspective.api.camera.CameraTarget;
import org.yanbwe.modularperspective.api.camera.Easing;
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.InputLock;
import org.yanbwe.modularperspective.api.camera.PerspectivePolicy;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.Projection;

/**
 * {@code /perspective} 命令（M3，docs/design.md §12）。
 *
 * <p>语法（命令只操作 SERVER 来源的指令；本地 F4/F6 预设不受影响）：
 * <pre>
 * /perspective camera &lt;targets&gt; [position &lt;x&gt; &lt;y&gt; &lt;z&gt;] [rotation &lt;yaw&gt; &lt;pitch&gt;]
 *                     [distance &lt;blocks&gt;] [fov &lt;degrees&gt;]
 *                     [projection &lt;perspective|orthographic&gt;] [duration &lt;ticks&gt;]
 *                     [priority &lt;0-1000&gt;] [enforcement &lt;suggest|require&gt;]
 *                     [locks &lt;rotate|zoom|move|all&gt;...]
 * /perspective preset &lt;targets&gt; &lt;top-down|reset&gt; [duration &lt;ticks&gt;]
 * /perspective lock &lt;targets&gt; &lt;rotate|zoom|move|all&gt; &lt;on|off&gt;
 * /perspective hud &lt;targets&gt; &lt;on|off&gt; [duration &lt;ticks&gt;]
 * /perspective policy &lt;targets&gt; &lt;flag&gt; ... | maxDirectives &lt;n&gt; | maxDistance &lt;d&gt; | query
 * /perspective clear &lt;targets&gt;
 * /perspective query &lt;targets&gt;
 * </pre>
 * {@code camera} 的每个子参数都是命名子参数，按上述**固定顺序**省略或给出（Brigadier 不支持无序可选参数）；
 * {@code locks} 是贪婪字符串，必须是最后一个子参数，可写多个位（空格/逗号分隔，{@code all} 展开三位）。
 * 至少给出一个 camera 选项，否则报错。{@code camera}/{@code preset top-down} 的落位与客户端本地预设一致。
 * 权限：等级 ≥ 2（{@code Commands.LEVEL_GAMEMASTERS}）；错误走 {@link CommandSyntaxException}/sendFailure。
 */
public final class PerspectiveCommand {
    /** 命令产生的指令 id：重复执行同 id → 替换旧指令（§5.2）。 */
    private static final String COMMAND_DIRECTIVE_ID = ModularPerspective.MODID + ":command";
    private static final String PRESET_DIRECTIVE_ID = ModularPerspective.MODID + ":preset";
    private static final String HUD_DIRECTIVE_ID = ModularPerspective.MODID + ":hud";
    private static final int DEFAULT_DURATION = 40;
    private static final float DEFAULT_DISTANCE = 6.0F;
    private static final int DEFAULT_PRIORITY = 100;
    private static final int PRESET_PRIORITY = 100;

    /** camera 子参数个数（顺序即 Brigadier 语法顺序，见类注释）。 */
    private static final int CAMERA_OPTION_COUNT = 9;
    private static final int OPT_POSITION = 1;
    private static final int OPT_ROTATION = 1 << 1;
    private static final int OPT_DISTANCE = 1 << 2;
    private static final int OPT_FOV = 1 << 3;
    private static final int OPT_PROJECTION = 1 << 4;
    private static final int OPT_DURATION = 1 << 5;
    private static final int OPT_PRIORITY = 1 << 6;
    private static final int OPT_ENFORCEMENT = 1 << 7;
    private static final int OPT_LOCKS = 1 << 8;

    private PerspectiveCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> cameraLiteral =
                Commands.literal("camera");
        cameraLiteral.then(cameraTargets());
        dispatcher.register(Commands.literal("perspective")
                .requires(PerspectiveCommand::mayControl)
                .then(cameraLiteral)
                .then(Commands.literal("preset")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(presetMode("top-down", true))
                                .then(presetMode("reset", false))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(lockChoices("rotate"))
                                .then(lockChoices("zoom"))
                                .then(lockChoices("move"))
                                .then(lockChoices("all"))))
                .then(Commands.literal("hud")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(hudMode("on", true))
                                .then(hudMode("off", false))))
                .then(Commands.literal("policy")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(policyFlag("allowRequire", 0))
                                .then(policyFlag("allowSuggest", 1))
                                .then(policyFlag("allowOrthographic", 2))
                                .then(Commands.literal("maxDirectives")
                                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 1024))
                                                .executes(ctx -> policyNumber(ctx,
                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                        IntegerArgumentType.getInteger(ctx, "value"), true))))
                                .then(Commands.literal("maxDistance")
                                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0D, 128.0D))
                                                .executes(ctx -> policyNumber(ctx,
                                                        EntityArgument.getPlayers(ctx, "targets"),
                                                        DoubleArgumentType.getDouble(ctx, "value"), false))))
                                .then(Commands.literal("query")
                                        .executes(ctx -> policyQuery(ctx, EntityArgument.getPlayers(ctx, "targets"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> clear(EntityArgument.getPlayers(ctx, "targets")))))
                .then(Commands.literal("query")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> query(ctx, EntityArgument.getPlayers(ctx, "targets"))))));
        ModularPerspective.LOGGER.debug("[{}] /perspective registered (M3)", ModularPerspective.MODID);
    }

    // ------------------------------------------------------------------ camera 命名子参数

    /** targets 节点：挂上 camera 的命名子参数分支（每条分支独立 executes，避免链式 builder 复用问题）。 */
    private static ArgumentBuilder<CommandSourceStack, ?> cameraTargets() {
        var targets = Commands.argument("targets", EntityArgument.players());
        targets.then(positionOptions());
        targets.then(rotationOptions());
        targets.then(Commands.literal("distance")
                .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(1.0D, 32.0D))
                        .executes(ctx -> camera(ctx, OPT_DISTANCE))));
        targets.then(Commands.literal("fov")
                .then(Commands.argument("degrees", DoubleArgumentType.doubleArg(30.0D, 110.0D))
                        .executes(ctx -> camera(ctx, OPT_FOV))));
        targets.then(Commands.literal("projection")
                .then(Commands.argument("projection", StringArgumentType.word())
                        .executes(ctx -> camera(ctx, OPT_PROJECTION))));
        targets.then(Commands.literal("duration")
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                        .executes(ctx -> camera(ctx, OPT_DURATION))));
        targets.then(Commands.literal("priority")
                .then(Commands.argument("priority", IntegerArgumentType.integer(0, 1000))
                        .executes(ctx -> camera(ctx, OPT_PRIORITY))));
        targets.then(Commands.literal("enforcement")
                .then(Commands.argument("enforcement", StringArgumentType.word())
                        .executes(ctx -> camera(ctx, OPT_ENFORCEMENT))));
        targets.then(Commands.literal("locks")
                .then(Commands.argument("locks", StringArgumentType.greedyString())
                        .executes(ctx -> camera(ctx, OPT_LOCKS))));
        // 链式写法：camera <targets> <option> <option> ...（顺序与 README 一致，locks 只吃剩余 token 所以必须最后）。
        targets.then(Commands.argument("options", StringArgumentType.greedyString())
                .executes(ctx -> cameraFromOptions(ctx, StringArgumentType.getString(ctx, "options"))));
        return targets;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> positionOptions() {
        return Commands.literal("position")
                .then(Commands.argument("x", DoubleArgumentType.doubleArg(-30_000_000.0D, 30_000_000.0D))
                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(-30_000_000.0D, 30_000_000.0D))
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-30_000_000.0D, 30_000_000.0D))
                                        .executes(ctx -> camera(ctx, OPT_POSITION)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rotationOptions() {
        return Commands.literal("rotation")
                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-1_000_000.0F, 1_000_000.0F))
                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                .executes(ctx -> camera(ctx, OPT_ROTATION))
                                .then(Commands.literal("duration")
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                                                .executes(ctx -> camera(ctx, OPT_ROTATION | OPT_DURATION))))));
    }
    private static int camera(CommandContext<CommandSourceStack> ctx, int mask) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        if (mask == 0) {
            ctx.getSource().sendFailure(Component.literal("perspective: at least one camera option is required"));
            return 0;
        }
        Position3 position = null;
        Float yaw = null;
        Float pitch = null;
        Float distance = null;
        Float fov = null;
        Projection projection = null;
        int duration = DEFAULT_DURATION;
        int priority = DEFAULT_PRIORITY;
        Enforcement enforcement = Enforcement.SUGGEST;
        EnumSet<InputLock> locks = null;
        if ((mask & OPT_POSITION) != 0) {
            position = new Position3(DoubleArgumentType.getDouble(ctx, "x"),
                    DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"));
        }
        if ((mask & OPT_ROTATION) != 0) {
            yaw = FloatArgumentType.getFloat(ctx, "yaw");
            pitch = FloatArgumentType.getFloat(ctx, "pitch");
        }
        if ((mask & OPT_DISTANCE) != 0) {
            distance = (float) DoubleArgumentType.getDouble(ctx, "blocks");
        }
        if ((mask & OPT_FOV) != 0) {
            fov = (float) DoubleArgumentType.getDouble(ctx, "degrees");
        }
        if ((mask & OPT_PROJECTION) != 0) {
            String raw = StringArgumentType.getString(ctx, "projection").toLowerCase(Locale.ROOT);
            if (raw.equals("perspective")) {
                projection = Projection.PERSPECTIVE;
            } else if (raw.equals("orthographic")) {
                projection = Projection.ORTHOGRAPHIC;
            } else {
                ctx.getSource().sendFailure(Component.literal(
                        "perspective: projection must be perspective or orthographic"));
                return 0;
            }
        }
        if ((mask & OPT_DURATION) != 0) {
            duration = IntegerArgumentType.getInteger(ctx, "ticks");
        }
        if ((mask & OPT_PRIORITY) != 0) {
            priority = IntegerArgumentType.getInteger(ctx, "priority");
        }
        if ((mask & OPT_ENFORCEMENT) != 0) {
            String raw = StringArgumentType.getString(ctx, "enforcement").toLowerCase(Locale.ROOT);
            if (raw.equals("suggest")) {
                enforcement = Enforcement.SUGGEST;
            } else if (raw.equals("require")) {
                enforcement = Enforcement.REQUIRE;
            } else {
                ctx.getSource().sendFailure(Component.literal(
                        "perspective: enforcement must be suggest or require"));
                return 0;
            }
        }
        if ((mask & OPT_LOCKS) != 0) {
            locks = parseLocks(ctx, StringArgumentType.getString(ctx, "locks"));
            if (locks == null) {
                return 0;
            }
        }

        return pushCamera(ctx, targets, position, yaw, pitch, distance, fov, projection, duration, priority,
                enforcement, locks);
    }

    /**
     * 链式 camera 选项解析：{@code camera <targets> <option> <value> <option> ...}。
     *
     * <p>用于一条命令同时下发多个通道（如 rotation + duration、distance + priority）。扁平命名分支更适合补全，
     * 贪婪字符串分支覆盖组合场景；两条路径最终都走 {@link #pushCamera}。顺序不限，{@code locks} 必须最后（它会吃掉剩余 token）。</p>
     */
    private static int cameraFromOptions(CommandContext<CommandSourceStack> ctx, String raw) throws CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        String[] tokens = raw.trim().split("\\s+");
        Position3 position = null;
        Float yaw = null;
        Float pitch = null;
        Float distance = null;
        Float fov = null;
        Projection projection = null;
        int duration = DEFAULT_DURATION;
        int priority = DEFAULT_PRIORITY;
        Enforcement enforcement = Enforcement.SUGGEST;
        EnumSet<InputLock> locks = null;
        int index = 0;
        try {
            while (index < tokens.length) {
                String option = tokens[index++].toLowerCase(Locale.ROOT);
                switch (option) {
                    case "position" -> {
                        double x = parseDouble(tokens[index++]);
                        double y = parseDouble(tokens[index++]);
                        double z = parseDouble(tokens[index++]);
                        if (Math.abs(x) > 30_000_000.0D || Math.abs(y) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D) {
                            return failure(ctx, "position coordinate out of range");
                        }
                        position = new Position3(x, y, z);
                    }
                    case "rotation" -> {
                        yaw = (float) parseDouble(tokens[index++]);
                        pitch = (float) parseDouble(tokens[index++]);
                    }
                    case "distance" -> {
                        distance = (float) parseDouble(tokens[index++]);
                        if (distance < 1.0F || distance > 32.0F) {
                            return failure(ctx, "distance must be between 1 and 32");
                        }
                    }
                    case "fov" -> {
                        fov = (float) parseDouble(tokens[index++]);
                        if (fov < 30.0F || fov > 110.0F) {
                            return failure(ctx, "fov must be between 30 and 110");
                        }
                    }
                    case "projection" -> {
                        String value = tokens[index++].toLowerCase(Locale.ROOT);
                        if (value.equals("perspective")) {
                            projection = Projection.PERSPECTIVE;
                        } else if (value.equals("orthographic")) {
                            projection = Projection.ORTHOGRAPHIC;
                        } else {
                            return failure(ctx, "projection must be perspective or orthographic");
                        }
                    }
                    case "duration" -> {
                        duration = parseInt(tokens[index++]);
                        if (duration < 1 || duration > 24000) {
                            return failure(ctx, "duration must be between 1 and 24000");
                        }
                    }
                    case "priority" -> {
                        priority = parseInt(tokens[index++]);
                        if (priority < 0 || priority > 1000) {
                            return failure(ctx, "priority must be between 0 and 1000");
                        }
                    }
                    case "enforcement" -> {
                        String value = tokens[index++].toLowerCase(Locale.ROOT);
                        if (value.equals("suggest")) {
                            enforcement = Enforcement.SUGGEST;
                        } else if (value.equals("require")) {
                            enforcement = Enforcement.REQUIRE;
                        } else {
                            return failure(ctx, "enforcement must be suggest or require");
                        }
                    }
                    case "locks" -> {
                        StringBuilder remaining = new StringBuilder();
                        while (index < tokens.length) {
                            if (!remaining.isEmpty()) {
                                remaining.append(' ');
                            }
                            remaining.append(tokens[index++]);
                        }
                        locks = parseLocks(ctx, remaining.toString());
                        if (locks == null) {
                            return 0;
                        }
                    }
                    default -> {
                        return failure(ctx, "unknown camera option '" + option + "'");
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException exception) {
            return failure(ctx, "missing value for camera option");
        } catch (NumberFormatException exception) {
            return failure(ctx, "invalid number '" + exception.getMessage() + "'");
        }
        return pushCamera(ctx, targets, position, yaw, pitch, distance, fov, projection, duration, priority,
                enforcement, locks);
    }

    /** 统一推送入口：构造通道/CameraTarget 并逐玩家 push；position..locks 全空时报错。 */
    private static int pushCamera(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                                  Position3 position, Float yaw, Float pitch, Float distance, Float fov,
                                  Projection projection, int duration, int priority, Enforcement enforcement,
                                  EnumSet<InputLock> locks) {
        EnumSet<CameraChannel> channels = EnumSet.noneOf(CameraChannel.class);
        if (position != null) {
            channels.add(CameraChannel.POSITION);
        }
        if (yaw != null || pitch != null) {
            channels.add(CameraChannel.ANGLE);
        }
        if (distance != null) {
            channels.add(CameraChannel.ZOOM);
        }
        if (fov != null) {
            channels.add(CameraChannel.FOV);
        }
        if (projection != null) {
            channels.add(CameraChannel.PROJECTION);
        }
        if (locks != null && !locks.isEmpty()) {
            // §5.3：锁位只有挂到 INPUT_LOCK 通道上才会进入仲裁并集。
            channels.add(CameraChannel.INPUT_LOCK);
        }
        if (channels.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("perspective: no camera channel given"));
            return 0;
        }
        CameraTarget value = new CameraTarget(position, yaw, pitch, distance, fov, projection, null, locks);
        int pushed = 0;
        for (ServerPlayer target : targets) {
            try {
                CameraDirective directive = PerspectiveServer.push(target, COMMAND_DIRECTIVE_ID, priority,
                        enforcement, channels, value, duration, false, true);
                ModularPerspective.LOGGER.info("[perspective] command camera -> {} id={} seq={} priority={} enforcement={} "
                                + "duration={} channels={} locks={}",
                        target.getGameProfile().getName(), directive.id(), directive.seq(), priority,
                        enforcement, duration, channels, locks);
                pushed++;
            } catch (IllegalArgumentException exception) {
                ctx.getSource().sendFailure(Component.literal("perspective: " + exception.getMessage()));
                return 0;
            }
        }
        int count = pushed;
        ctx.getSource().sendSuccess(() -> Component.literal("Pushed perspective directive to " + count + " player(s)"), false);
        return count;
    }

    private static int failure(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendFailure(Component.literal("perspective: " + message));
        return 0;
    }

    private static double parseDouble(String raw) {
        return Double.parseDouble(raw);
    }

    private static int parseInt(String raw) {
        return Integer.parseInt(raw);
    }

    /** 解析 locks 贪婪字符串（空格/逗号分隔；all 展开三位）；非法值返回 null 并 sendFailure。 */
    private static EnumSet<InputLock> parseLocks(CommandContext<CommandSourceStack> ctx, String raw) {
        EnumSet<InputLock> locks = EnumSet.noneOf(InputLock.class);
        for (String token : raw.split("[,\\s]+")) {
            if (token.isEmpty()) {
                continue;
            }
            switch (token.toLowerCase(Locale.ROOT)) {
                case "rotate" -> locks.add(InputLock.ROTATE);
                case "zoom" -> locks.add(InputLock.ZOOM);
                case "move" -> locks.add(InputLock.MOVE);
                case "all" -> locks.addAll(EnumSet.allOf(InputLock.class));
                default -> {
                    ctx.getSource().sendFailure(Component.literal("perspective: unknown lock bit '" + token + "'"));
                    return null;
                }
            }
        }
        return locks;
    }

    // ------------------------------------------------------------------ preset / hud

    private static LiteralArgumentBuilder<CommandSourceStack> presetMode(String mode, boolean topDown) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(mode)
                .executes(ctx -> preset(ctx, EntityArgument.getPlayers(ctx, "targets"), topDown, DEFAULT_DURATION));
        node.then(Commands.literal("duration")
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                        .executes(ctx -> preset(ctx, EntityArgument.getPlayers(ctx, "targets"), topDown,
                                IntegerArgumentType.getInteger(ctx, "ticks")))));
        return node;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hudMode(String mode, boolean visible) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(mode)
                .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"), visible, DEFAULT_DURATION));
        node.then(Commands.literal("duration")
                .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 24000))
                        .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"), visible,
                                IntegerArgumentType.getInteger(ctx, "ticks")))));
        return node;
    }

    /** lock 子命令：{@code ... <bit> on|off}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> lockChoices(String lockName) {
        return Commands.literal(lockName)
                .then(Commands.literal("on")
                        .executes(ctx -> lock(ctx, EntityArgument.getPlayers(ctx, "targets"), lockName, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> lock(ctx, EntityArgument.getPlayers(ctx, "targets"), lockName, false)));
    }

    private static boolean mayControl(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    private static int preset(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                              boolean topDown, int duration) {
        int affected = 0;
        for (ServerPlayer target : targets) {
            if (!topDown) {
                if (PerspectiveServer.remove(target, PRESET_DIRECTIVE_ID)) {
                    affected++;
                }
                continue;
            }
            try {
                CameraDirective directive = pushTopDown(target, PRESET_DIRECTIVE_ID, PRESET_PRIORITY,
                        Enforcement.SUGGEST, target.getYRot(), 45.0F, DEFAULT_DISTANCE,
                        EnumSet.of(InputLock.ROTATE, InputLock.ZOOM), duration, false, false);
                ModularPerspective.LOGGER.info("[perspective] command preset top-down -> {} id={} seq={}",
                        target.getGameProfile().getName(), directive.id(), directive.seq());
                affected++;
            } catch (IllegalArgumentException exception) {
                ctx.getSource().sendFailure(Component.literal("perspective: " + exception.getMessage()));
                return 0;
            }
        }
        int count = affected;
        ctx.getSource().sendSuccess(() -> Component.literal((topDown ? "Applied top-down preset to " : "Removed top-down preset from ")
                + count + " player(s)"), false);
        return count;
    }

    /** 统一的俯视落位：眼睛沿 forward 退 distance；通道 POSITION/ANGLE/ZOOM/PROJECTION。 */
    private static CameraDirective pushTopDown(ServerPlayer target, String id, int priority, Enforcement enforcement,
                                                float yaw, float pitch, float distance, EnumSet<InputLock> locks,
                                                int duration, boolean keepOnDeath, boolean requiresAck) {
        Vec3 eye = target.getEyePosition();
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Position3 position = new Position3(
                eye.x - forward.x * distance,
                eye.y - forward.y * distance,
                eye.z - forward.z * distance);
        CameraTarget value = new CameraTarget(position, yaw, pitch, distance, null,
                Projection.ORTHOGRAPHIC, null, locks);
        EnumSet<CameraChannel> channels = EnumSet.of(CameraChannel.POSITION, CameraChannel.ANGLE,
                CameraChannel.ZOOM, CameraChannel.PROJECTION);
        if (!locks.isEmpty()) {
            // §5.3：锁位只有挂到 INPUT_LOCK 通道上才会进入仲裁并集，preset 命令必须显式带上该通道。
            channels.add(CameraChannel.INPUT_LOCK);
        }
        return PerspectiveServer.push(target, id, priority, enforcement, channels, value, duration, requiresAck, keepOnDeath);
    }

    /** {@code /perspective hud <targets> <on|off> [duration <ticks>]}：只动 HUD 通道（§10.2）。 */
    private static int hud(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                           boolean visible, int duration) {
        int affected = 0;
        for (ServerPlayer target : targets) {
            CameraTarget value = new CameraTarget(null, null, null, null, null, null, visible, null);
            try {
                CameraDirective directive = PerspectiveServer.push(target, HUD_DIRECTIVE_ID, DEFAULT_PRIORITY,
                        Enforcement.SUGGEST, EnumSet.of(CameraChannel.HUD), value, duration, false, false);
                ModularPerspective.LOGGER.info("[perspective] command hud {} -> {} id={} seq={} duration={}",
                        visible ? "on" : "off", target.getGameProfile().getName(), directive.id(), directive.seq(),
                        duration);
                affected++;
            } catch (IllegalArgumentException exception) {
                ctx.getSource().sendFailure(Component.literal("perspective: " + exception.getMessage()));
                return 0;
            }
        }
        int count = affected;
        ctx.getSource().sendSuccess(
                () -> Component.literal("Set HUD visible=" + visible + " for " + count + " player(s)"), false);
        return count;
    }

    // ------------------------------------------------------------------ policy

    /** {@code /perspective policy <targets> <flag> on|off}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> policyFlag(String name, int flag) {
        return Commands.literal(name)
                .then(Commands.literal("on")
                        .executes(ctx -> policyFlag(ctx, EntityArgument.getPlayers(ctx, "targets"), flag, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> policyFlag(ctx, EntityArgument.getPlayers(ctx, "targets"), flag, false)));
    }

    private static int policyFlag(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                                  int flag, boolean value) {
        int changed = 0;
        for (ServerPlayer target : targets) {
            PerspectivePolicy policy = PerspectiveServer.policy(target);
            PerspectivePolicy updated = switch (flag) {
                case 0 -> new PerspectivePolicy(value, policy.allowSuggest(), policy.maxDirectives(),
                        policy.maxDistance(), policy.allowOrthographic());
                case 1 -> new PerspectivePolicy(policy.allowRequire(), value, policy.maxDirectives(),
                        policy.maxDistance(), policy.allowOrthographic());
                default -> new PerspectivePolicy(policy.allowRequire(), policy.allowSuggest(),
                        policy.maxDirectives(), policy.maxDistance(), value);
            };
            PerspectiveServer.setPolicy(target, updated);
            changed++;
        }
        int count = changed;
        ctx.getSource().sendSuccess(() -> Component.literal("Updated policy flag for " + count + " player(s)"), false);
        return count;
    }

    private static int policyNumber(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                                    double value, boolean directives) {
        int changed = 0;
        for (ServerPlayer target : targets) {
            PerspectivePolicy policy = PerspectiveServer.policy(target);
            PerspectivePolicy updated = directives
                    ? new PerspectivePolicy(policy.allowRequire(), policy.allowSuggest(), (int) value,
                            policy.maxDistance(), policy.allowOrthographic())
                    : new PerspectivePolicy(policy.allowRequire(), policy.allowSuggest(), policy.maxDirectives(),
                            (float) value, policy.allowOrthographic());
            PerspectiveServer.setPolicy(target, updated);
            changed++;
        }
        int count = changed;
        ctx.getSource().sendSuccess(() -> Component.literal("Updated policy limit for " + count + " player(s)"), false);
        return count;
    }

    private static int policyQuery(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer target : targets) {
            PerspectivePolicy policy = PerspectiveServer.policy(target);
            String name = target.getGameProfile().getName();
            ctx.getSource().sendSuccess(() -> Component.literal(name + ": policy=" + policy), false);
        }
        return targets.size();
    }

    // ------------------------------------------------------------------ lock

    /**
     * 修改命令创建的最上层指令的锁位（§12：只改命令创建且栈顶的指令）。
     * 若没有命令指令则返回 0 并在 query 里可见。
     */
    private static int lock(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                            String lockName, boolean on) {
        int changed = 0;
        for (ServerPlayer target : targets) {
            CameraDirective base = findLockable(target);
            if (base == null) {
                ctx.getSource().sendFailure(Component.literal("perspective: " + target.getGameProfile().getName()
                        + " has no command/preset directive"));
                continue;
            }
            EnumSet<InputLock> locks = EnumSet.copyOf(base.target().locks());
            for (InputLock lock : resolveLock(lockName)) {
                if (on) {
                    locks.add(lock);
                } else {
                    locks.remove(lock);
                }
            }
            CameraTarget value = new CameraTarget(base.target().position(), base.target().yaw(), base.target().pitch(),
                    base.target().distance(), base.target().fov(), base.target().projection(),
                    base.target().hudVisible(), locks);
            EnumSet<CameraChannel> channels = EnumSet.copyOf(base.channels());
            if (!locks.isEmpty()) {
                channels.add(CameraChannel.INPUT_LOCK);
            } else {
                channels.remove(CameraChannel.INPUT_LOCK);
            }
            CameraDirective updated = new CameraDirective(base.id(), base.owner(), PerspectiveServer.nextSeq(target),
                    base.priority(), base.enforcement(), channels, value, base.fadeInTicks(),
                    base.fadeOutTicks(), base.easing(), base.durationTicks(), base.requiresAck(),
                    base.keepOnDeath(), base.startTick());
            PerspectiveServer.replace(target, updated);
            changed++;
            ModularPerspective.LOGGER.info("[perspective] command lock {}={} -> {} id={} locks={}",
                    lockName, on, target.getGameProfile().getName(), updated.id(), locks);
        }
        int count = changed;
        ctx.getSource().sendSuccess(() -> Component.literal("Updated lock bits for " + count + " player(s)"), false);
        return count;
    }

    private static CameraDirective findLockable(ServerPlayer target) {
        List<CameraDirective> directives = PerspectiveServer.directives(target);
        for (CameraDirective directive : directives) {
            if (directive.id().equals(PRESET_DIRECTIVE_ID)) {
                return directive;
            }
        }
        for (CameraDirective directive : directives) {
            if (directive.id().equals(COMMAND_DIRECTIVE_ID)) {
                return directive;
            }
        }
        return null;
    }

    private static EnumSet<InputLock> resolveLock(String lockName) {
        return switch (lockName) {
            case "rotate" -> EnumSet.of(InputLock.ROTATE);
            case "zoom" -> EnumSet.of(InputLock.ZOOM);
            case "move" -> EnumSet.of(InputLock.MOVE);
            default -> EnumSet.allOf(InputLock.class);
        };
    }

    // ------------------------------------------------------------------ clear / query

    private static int clear(Collection<ServerPlayer> targets) {
        int total = 0;
        for (ServerPlayer target : targets) {
            total += PerspectiveServer.clear(target);
        }
        return total;
    }

    private static int query(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
        for (ServerPlayer target : targets) {
            String name = target.getGameProfile().getName();
            long revision = PerspectiveServer.revision(target);
            Collection<CameraDirective> directives = PerspectiveServer.directives(target);
            ctx.getSource().sendSuccess(() -> Component.literal(
                    name + ": revision=" + revision + " directives=" + directives.size()), false);
            for (CameraDirective directive : directives) {
                ctx.getSource().sendSuccess(() -> Component.literal("  - " + directive.id()
                        + " seq=" + directive.seq()
                        + " priority=" + directive.priority()
                        + " enforcement=" + directive.enforcement()
                        + " duration=" + directive.durationTicks()
                        + " locks=" + directive.target().locks()
                        + " channels=" + directive.channels()), false);
            }
        }
        return targets.size();
    }
}
