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
    public static final ModConfigSpec.BooleanValue CULLING_BLOCK_LEVEL;
    public static final ModConfigSpec.BooleanValue CULLING_GHOST;
    public static final ModConfigSpec.BooleanValue CULLING_GHOST_FILL;
    public static final ModConfigSpec.BooleanValue CULLING_SHAPE_ENABLED;
    public static final ModConfigSpec.DoubleValue CULLING_SHAPE_RADIUS;
    public static final ModConfigSpec.DoubleValue CULLING_SHAPE_CONE_HALF_ANGLE;
    public static final ModConfigSpec.BooleanValue CULLING_SIGHTLINE_ENABLED;
    public static final ModConfigSpec.DoubleValue CULLING_SIGHTLINE_OCCLUDE_THRESHOLD;
    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    static {
        COMMON_BUILDER.comment("剔除（culling）开关；v0.1 只做 section 级近似，见 docs/design.md §11.2").push("culling");
        CULLING_ENABLED = COMMON_BUILDER.comment("总开关；关闭后不剔除任何方块/section").define("enabled", true);
        CULLING_SECTION_APPROXIMATION = COMMON_BUILDER
                .comment("v0.1 保持 true：只按 section 判定可见性，不改区块 mesh")
                .define("sectionApproximation", true);
        CULLING_BLOCK_LEVEL = COMMON_BUILDER
                .comment("v0.2 方块级剔除：把相机-玩家视线上的实心方块从区块 mesh 中剔除（只影响渲染，不改世界）；开启时 section 级近似自动让位")
                .define("blockLevel", true);
        CULLING_GHOST = COMMON_BUILDER
                .comment("v0.2 方块级剔除时给被剔除方块画半透明线框（幽灵轮廓），避免“墙被挖洞”看起来像渲染错误")
                .define("ghostOutline", true);
        CULLING_GHOST_FILL = COMMON_BUILDER
                .comment("v0.3 幽灵方块填充：在半透明线框之外再给被剔除方块画一层半透明青色体积（debugQuads，按面剔除内部面），可在 culling.ghostFill 关闭")
                .define("ghostFill", true);
        CULLING_SHAPE_ENABLED = COMMON_BUILDER
                .comment("v0.3 形状/视线剔除首片（旧模组 GenericCuller3 的锥/柱近似）：把相机与玩家之间的实心地形从区块 mesh 剔除；室内由房间扫描负责，可与 blockLevel 独立开关")
                .define("shape", true);
        CULLING_SHAPE_RADIUS = COMMON_BUILDER
                .comment("形状剔除圆柱半径（格，对应旧模组 cullAngle 默认 6.0）")
                .defineInRange("shapeRadius", 6.0D, 0.0D, 32.0D);
        CULLING_SHAPE_CONE_HALF_ANGLE = COMMON_BUILDER
                .comment("近玩家处锥形半角（度，对应旧模组 coneHalfAngle 默认 45）")
                .defineInRange("shapeConeHalfAngle", 45.0D, 0.0D, 89.0D);
        CULLING_SIGHTLINE_ENABLED = COMMON_BUILDER
                .comment("v0.3 视线/轮廓剔除（旧模组 SightlineScanner）：按相机→玩家 27 条射线命中的连通形状整块判定，挡住视线多数的形状整体剔除、挡得少的整块保护，避免把家具/柱子挖出洞")
                .define("sightline", true);
        CULLING_SIGHTLINE_OCCLUDE_THRESHOLD = COMMON_BUILDER
                .comment("形状被判定为\"遮挡\"所需的射线比例（0..1）；高于该值整块剔除，低于则该形状对其它剔除层受保护")
                .defineInRange("sightlineOccludeThreshold", 0.35D, 0.0D, 1.0D);
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
    public static final ModConfigSpec.BooleanValue DEFAULT_PERSPECTIVE;
    public static final ModConfigSpec.BooleanValue HUD_EXAMPLES;

    static {
        SERVER_CONTROL = CLIENT_BUILDER
                .comment("玩家是否允许被服务端控制视角：ALLOW=允许，SUGGEST_ONLY=只接受建议，DISABLED=只保留本地控制（玩家权利，服务端不能覆盖）")
                .defineEnum("serverControl", ServerControl.ALLOW);

        DEFAULT_PERSPECTIVE = CLIENT_BUILDER
                .comment("进入世界时是否默认开启正交俯视（§13.1；关闭后仍可按 F4/F6 手动切换）")
                .define("defaultTopDown", true);

        HUD_EXAMPLES = CLIENT_BUILDER
                .comment("是否显示内置的第三方 HUD 示例控件（可拖拽徽标 + 世界锚定标记），§10.2/§10.4")
                .define("hudExamples", true);

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
