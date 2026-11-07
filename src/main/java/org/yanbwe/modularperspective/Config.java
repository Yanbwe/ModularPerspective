package org.yanbwe.modularperspective;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.yanbwe.modularperspective.api.camera.ServerControl;

/**
 * 配置骨架（M0）。字段按 docs/design.md §13 分三份：
 *
 * <ul>
 *   <li>{@code COMMON} —— 双端共享（剔除、调试）；</li>
 *   <li>{@code CLIENT} —— 仅客户端的显示/输入偏好（§13.1）；</li>
 *   <li>{@code SERVER} —— 每世界策略，对应 {@code PerspectivePolicy}（§6.5、§13.2）。</li>
 * </ul>
 *
 * <p>旧模组配置的迁移表见 §13.3；具体功能在 M1–M5 接入，这里先保证配置能加载、能改、能出配置界面。
 */
public final class Config {
    private Config() {}

    // ------------------------------------------------------------------ common

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CULLING_ENABLED;
    public static final ModConfigSpec.BooleanValue CULLING_SECTION_APPROXIMATION;
    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    static {
        COMMON_BUILDER.comment("剔除（culling）开关；v0.1 只做 section 级近似，见 docs/design.md §11.2").push("culling");
        CULLING_ENABLED = COMMON_BUILDER.comment("总开关；关闭后不剔除任何 section").define("enabled", true);
        CULLING_SECTION_APPROXIMATION = COMMON_BUILDER
                .comment("v0.1 保持 true：只按 section 判定可见性，不改区块 mesh")
                .define("sectionApproximation", true);
        COMMON_BUILDER.pop();

        COMMON_BUILDER.comment("调试输出").push("debug");
        DEBUG_OVERLAY = COMMON_BUILDER.comment("显示相机状态/指令栈/剔除统计的覆盖层").define("overlay", false);
        COMMON_BUILDER.pop();
    }

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ------------------------------------------------------------------ client

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<ServerControl> SERVER_CONTROL;
    public static final ModConfigSpec.BooleanValue ORTHO_SKY;
    public static final ModConfigSpec.BooleanValue ZOOM_SCROLL_ENABLED;

    static {
        SERVER_CONTROL = CLIENT_BUILDER
                .comment("玩家是否允许被服务端控制视角：ALLOW=允许，SUGGEST_ONLY=只接受建议，DISABLED=只保留本地控制（玩家权利，服务端不能覆盖）")
                .defineEnum("serverControl", ServerControl.ALLOW);

        ORTHO_SKY = CLIENT_BUILDER
                .comment("正交投影下是否绘制原版天空/云/星星（实验项，§8.2）")
                .define("orthoSky", false);

        CLIENT_BUILDER.comment("滚轮缩放").push("zoom");
        ZOOM_SCROLL_ENABLED = CLIENT_BUILDER.comment("Ctrl+滚轮是否缩放 FOV").define("scrollEnabled", true);
        CLIENT_BUILDER.pop();
    }

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ------------------------------------------------------------------ server

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue POLICY_ALLOW_REQUIRE;
    public static final ModConfigSpec.BooleanValue POLICY_ALLOW_SUGGEST;
    public static final ModConfigSpec.IntValue POLICY_MAX_DIRECTIVES;
    public static final ModConfigSpec.DoubleValue POLICY_MAX_DISTANCE;
    public static final ModConfigSpec.BooleanValue POLICY_ALLOW_ORTHOGRAPHIC;

    static {
        SERVER_BUILDER.comment("服务端策略：默认值对应 docs/design.md §6.5 的 PerspectivePolicy").push("policy");
        POLICY_ALLOW_REQUIRE = SERVER_BUILDER.comment("允许 push REQUIRE 指令").define("allowRequire", true);
        POLICY_ALLOW_SUGGEST = SERVER_BUILDER.comment("允许 push SUGGEST 指令").define("allowSuggest", true);
        POLICY_MAX_DIRECTIVES = SERVER_BUILDER
                .comment("每玩家同时存在的指令上限")
                .defineInRange("maxDirectives", 64, 1, 1024);
        POLICY_MAX_DISTANCE = SERVER_BUILDER
                .comment("相机距离上限（格）")
                .defineInRange("maxDistance", 32.0D, 1.0D, 128.0D);
        POLICY_ALLOW_ORTHOGRAPHIC = SERVER_BUILDER
                .comment("允许切换到正交投影")
                .define("allowOrthographic", true);
        SERVER_BUILDER.pop();
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
