package org.yanbwe.modularperspective.api.hud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * HUD 控件注册表（docs/design.md §10.2）。
 *
 * <p>仅客户端使用：其他模组在客户端初始化阶段调用
 * {@code HudRegistry.register(ResourceLocation.fromNamespaceAndPath("examplemod", "quest_panel"), ctx -> new QuestWidget())}。
 * 控件实例在第一次渲染时由宿主惰性创建，避免在静态初始化里碰 Minecraft 状态。
 */
public final class HudRegistry {
    private static final Map<ResourceLocation, Function<HudContext, HudWidget>> FACTORIES = new LinkedHashMap<>();

    private HudRegistry() {}

    public static synchronized void register(ResourceLocation id, Function<HudContext, HudWidget> factory) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        if (FACTORIES.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("HUD widget already registered: " + id);
        }
    }

    /** 已注册工厂的只读视图（宿主用来实例化控件）。 */
    public static synchronized Map<ResourceLocation, Function<HudContext, HudWidget>> factories() {
        return Map.copyOf(FACTORIES);
    }
}
