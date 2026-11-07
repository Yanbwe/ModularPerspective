package org.yanbwe.modularperspective.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.yanbwe.modularperspective.ModularPerspective;

/**
 * {@code /perspective} 命令骨架（M0）。完整语法在 M1–M3 按 docs/design.md §12 接入。
 *
 * <p>权限：等级 ≥ 2（{@code Commands.LEVEL_GAMEMASTERS}）。
 */
public final class PerspectiveCommand {
    private PerspectiveCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("perspective")
                        .then(Commands.literal("camera")
                                .requires(PerspectiveCommand::mayControl)
                                .executes(ctx -> notImplemented(ctx.getSource(), "camera")))
                        .then(Commands.literal("query")
                                .executes(ctx -> notImplemented(ctx.getSource(), "query")))
                        .then(Commands.literal("clear")
                                .requires(PerspectiveCommand::mayControl)
                                .executes(ctx -> notImplemented(ctx.getSource(), "clear")))
        );
        ModularPerspective.LOGGER.debug("[{}] /perspective registered", ModularPerspective.MODID);
    }

    private static boolean mayControl(CommandSourceStack source) {
        return source.hasPermission(Commands.LEVEL_GAMEMASTERS);
    }

    private static int notImplemented(CommandSourceStack source, String subcommand) {
        // M0 只确认双端加载与命令挂载；M1 换成真实逻辑，参数不足时抛 CommandSyntaxException。
        source.sendSuccess(() -> Component.literal("/perspective " + subcommand + " —— M0 骨架，M1 实现"), false);
        return 1;
    }
}
