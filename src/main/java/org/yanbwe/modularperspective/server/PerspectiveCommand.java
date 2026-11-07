package org.yanbwe.modularperspective.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.EnumSet;
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
import org.yanbwe.modularperspective.api.camera.Enforcement;
import org.yanbwe.modularperspective.api.camera.Position3;
import org.yanbwe.modularperspective.api.camera.Projection;

/**
 * {@code /perspective} 命令（M3，docs/design.md §12）。
 *
 * <p>语法（M3 首版，命令只操作 SERVER 来源的指令；本地 F4/F6 预设不受影响）：
 * <pre>
 * /perspective camera &lt;targets&gt; &lt;yaw&gt; &lt;pitch&gt; [duration]
 * /perspective clear &lt;targets&gt;
 * /perspective query &lt;targets&gt;
 * </pre>
 * 权限：等级 ≥ 2（{@code Commands.LEVEL_GAMEMASTERS}）；错误走 {@link CommandSyntaxException}/sendFailure。
 * {@code camera} 的落位与客户端本地预设一致：从玩家眼睛沿 forward 退 distance，正交投影、pitch 45° 默认观察。
 */
public final class PerspectiveCommand {
    /** 命令产生的指令 id：重复执行同 id → 替换旧指令（§5.2）。 */
    private static final String COMMAND_DIRECTIVE_ID = ModularPerspective.MODID + ":command";
    private static final int DEFAULT_DURATION = 40;
    private static final float DEFAULT_DISTANCE = 6.0F;
    private static final int DEFAULT_PRIORITY = 100;

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
                .then(Commands.literal("clear")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> clear(EntityArgument.getPlayers(ctx, "targets")))))
                .then(Commands.literal("query")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> query(ctx, EntityArgument.getPlayers(ctx, "targets"))))));
        ModularPerspective.LOGGER.debug("[{}] /perspective registered (M3)", ModularPerspective.MODID);
    }

    private static boolean mayControl(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    /** 给每个目标压一条“俯视相机”指令；落位按当前眼睛位置计算（静态相机，不跟随）。 */
    private static int camera(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets,
                              float yaw, float pitch, int duration) {
        int pushed = 0;
        for (ServerPlayer target : targets) {
            Vec3 eye = target.getEyePosition();
            Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
            Position3 position = new Position3(
                    eye.x - forward.x * DEFAULT_DISTANCE,
                    eye.y - forward.y * DEFAULT_DISTANCE,
                    eye.z - forward.z * DEFAULT_DISTANCE);
            CameraTarget value = new CameraTarget(position, yaw, pitch, DEFAULT_DISTANCE, null,
                    Projection.ORTHOGRAPHIC, null, null);
            EnumSet<CameraChannel> channels = EnumSet.of(CameraChannel.POSITION, CameraChannel.ANGLE,
                    CameraChannel.ZOOM, CameraChannel.PROJECTION);
            try {
                CameraDirective directive = PerspectiveServer.push(target, COMMAND_DIRECTIVE_ID, DEFAULT_PRIORITY,
                        Enforcement.SUGGEST, channels, value, duration, false, false);
                ModularPerspective.LOGGER.info("[perspective] command camera -> {} id={} seq={} duration={}",
                        target.getGameProfile().getName(), directive.id(), directive.seq(), duration);
                pushed++;
            } catch (IllegalArgumentException exception) {
                ctx.getSource().sendFailure(Component.literal("perspective: " + exception.getMessage()));
                return 0;
            }
        }
        final int count = pushed;
        ctx.getSource().sendSuccess(() -> Component.literal("Pushed perspective directive to " + count + " player(s)"), false);
        return count;
    }

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
                        + " channels=" + directive.channels()), false);
            }
        }
        return targets.size();
    }
}
