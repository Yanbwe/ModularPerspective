package org.yanbwe.modularperspective.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
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
 * /perspective camera &lt;targets&gt; &lt;yaw&gt; &lt;pitch&gt; [duration]
 * /perspective preset &lt;targets&gt; &lt;top-down|reset&gt; [duration]
 * /perspective lock &lt;targets&gt; &lt;rotate|zoom|move|all&gt; &lt;on|off&gt;
 * /perspective hud &lt;targets&gt; &lt;on|off&gt; [duration]
 * /perspective clear &lt;targets&gt;
 * /perspective query &lt;targets&gt;
 * </pre>
 * 权限：等级 ≥ 2（{@code Commands.LEVEL_GAMEMASTERS}）；错误走 {@link CommandSyntaxException}/sendFailure。
 * {@code camera}/{@code preset top-down} 的落位与客户端本地预设一致：从玩家眼睛沿 forward 退 distance，
 * 正交投影、pitch 45° 默认观察；{@code preset} 额外锁 ROTATE+ZOOM（§12）。
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

    private PerspectiveCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("perspective")
                .requires(PerspectiveCommand::mayControl)
                .then(Commands.literal("camera")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("yaw", FloatArgumentType.floatArg(-180.0F, 180.0F))
                                        .then(Commands.argument("pitch", FloatArgumentType.floatArg(-90.0F, 90.0F))
                                                .executes(ctx -> camera(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                        FloatArgumentType.getFloat(ctx, "yaw"),
                                                        FloatArgumentType.getFloat(ctx, "pitch"), DEFAULT_DURATION))
                                                .then(Commands.argument("duration",
                                                                IntegerArgumentType.integer(1, 24000))
                                                        .executes(ctx -> camera(ctx,
                                                                EntityArgument.getPlayers(ctx, "targets"),
                                                                FloatArgumentType.getFloat(ctx, "yaw"),
                                                                FloatArgumentType.getFloat(ctx, "pitch"),
                                                                IntegerArgumentType.getInteger(ctx, "duration"))))))))
                .then(Commands.literal("preset")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.literal("top-down")
                                        .executes(ctx -> preset(ctx, EntityArgument.getPlayers(ctx, "targets"), true,
                                                DEFAULT_DURATION))
                                        .then(Commands.argument("duration", IntegerArgumentType.integer(1, 24000))
                                                .executes(ctx -> preset(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                        true, IntegerArgumentType.getInteger(ctx, "duration")))))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> preset(ctx, EntityArgument.getPlayers(ctx, "targets"), false,
                                                DEFAULT_DURATION)))))
                .then(Commands.literal("lock")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(lockChoices("rotate"))
                                .then(lockChoices("zoom"))
                                .then(lockChoices("move"))
                                .then(lockChoices("all"))))
                .then(Commands.literal("hud")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.literal("on")
                                        .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"), true,
                                                DEFAULT_DURATION))
                                        .then(Commands.argument("duration", IntegerArgumentType.integer(1, 24000))
                                                .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                        true, IntegerArgumentType.getInteger(ctx, "duration")))))
                                .then(Commands.literal("off")
                                        .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"), false,
                                                DEFAULT_DURATION))
                                        .then(Commands.argument("duration", IntegerArgumentType.integer(1, 24000))
                                                .executes(ctx -> hud(ctx, EntityArgument.getPlayers(ctx, "targets"),
                                                        false, IntegerArgumentType.getInteger(ctx, "duration")))))))
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

    /** lock 子命令：{@code ... <bit> on|off}。 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> lockChoices(String lockName) {
        return Commands.literal(lockName)
                .then(Commands.literal("on")
                        .executes(ctx -> lock(ctx, EntityArgument.getPlayers(ctx, "targets"), lockName, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> lock(ctx, EntityArgument.getPlayers(ctx, "targets"), lockName, false)));
    }

    private static boolean mayControl(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    // ------------------------------------------------------------------ camera / preset

    private static int camera(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                              float yaw, float pitch, int duration) {
        int pushed = 0;
        for (ServerPlayer target : targets) {
            try {
                CameraDirective directive = pushTopDown(target, COMMAND_DIRECTIVE_ID, DEFAULT_PRIORITY,
                        Enforcement.SUGGEST, yaw, pitch, DEFAULT_DISTANCE, EnumSet.noneOf(InputLock.class),
                        duration, false, true);
                ModularPerspective.LOGGER.info("[perspective] command camera -> {} id={} seq={} duration={}",
                        target.getGameProfile().getName(), directive.id(), directive.seq(), duration);
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

    // ------------------------------------------------------------------ hud

    /** {@code /perspective hud <targets> <on|off> [duration]}：只动 HUD 通道（§10.2 整层跳过验收用）。 */
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
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> policyFlag(String name, int flag) {
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
