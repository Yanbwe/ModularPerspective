package org.yanbwe.modularperspective;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.yanbwe.modularperspective.server.PerspectiveCommand;

/**
 * Modular Perspective 主入口（common，单 {@code @Mod}）。
 *
 * <p>端隔离约定见 docs/design.md §3.3：不要在 {@code @Mod} 上写 {@code dist = Dist.CLIENT}，
 * 否则专用服务器上整个模组不加载。客户端初始化在 dist 判断后交给 {@link ModularPerspectiveClient}，
 * 该类只会在客户端被类加载。
 */
@Mod(ModularPerspective.MODID)
public final class ModularPerspective {
    /** modid，必须与 neoforge.mods.toml / gradle.properties 一致。 */
    public static final String MODID = "modularperspective";

    public static final Logger LOGGER = LogUtils.getLogger();

    public ModularPerspective(IEventBus modEventBus, ModContainer modContainer) {
        // 配置分三份：common 双端、server 每世界、client 仅客户端（§13）。
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);

        // 客户端初始化：mod bus 监听器由构造器直接注册，避免使用已废弃的 EventBusSubscriber$Bus（§3.3）。
        if (FMLEnvironment.dist.isClient()) {
            modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
            ModularPerspectiveClient.init(modEventBus, modContainer);
        }

        // 命令注册在 game bus（RegisterCommandsEvent，§12）。
        NeoForge.EVENT_BUS.addListener(PerspectiveCommand::register);

        LOGGER.info("[{}] common entry loaded (dist={})", MODID, FMLEnvironment.dist);
    }
}
