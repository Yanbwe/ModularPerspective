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
    public static final ModConfigSpec.DoubleValue GHOST_CLEAR_SCREEN;
    public static final ModConfigSpec.DoubleValue GHOST_OPAQUE_SCREEN;
    public static final ModConfigSpec.DoubleValue GHOST_MAX_ALPHA;
    public static final ModConfigSpec.BooleanValue CULLING_SHAPE_ENABLED;
    public static final ModConfigSpec.DoubleValue CULLING_SHAPE_RADIUS;
    public static final ModConfigSpec.DoubleValue CULLING_SHAPE_CONE_HALF_ANGLE;
    public static final ModConfigSpec.BooleanValue CULLING_SIGHTLINE_ENABLED;
    public static final ModConfigSpec.DoubleValue CULLING_SIGHTLINE_OCCLUDE_THRESHOLD;
    public static final ModConfigSpec.BooleanValue CULLING_VOLUME;
    public static final ModConfigSpec.DoubleValue CULLING_VOLUME_INNER_RADIUS;
    public static final ModConfigSpec.DoubleValue CULLING_VOLUME_OUTER_RADIUS;
    public static final ModConfigSpec.DoubleValue CULLING_VOLUME_MAX_ALPHA;
    public static final ModConfigSpec.DoubleValue CULLING_ROOM_MAX_DISTANCE;
    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    static {
        COMMON_BUILDER.comment("剔除（culling）开关；v0.1 只做 section 级近似，见 docs/design.md §11.2").push("culling");
        CULLING_ENABLED = COMMON_BUILDER.comment("总开关；关闭后不剔除任何方块/section").define("enabled", true);
        CULLING_SECTION_APPROXIMATION = COMMON_BUILDER
                .comment("v0.1 保持 true：只按 section 判定可见性，不改区块 mesh")
                .define("sectionApproximation", false);
        CULLING_BLOCK_LEVEL = COMMON_BUILDER
                .comment("v0.2 方块级剔除：把相机-玩家视线上的实心方块从区块 mesh 中剔除（只影响渲染，不改世界）；开启时 section 级近似自动让位")
                .define("blockLevel", false);
        CULLING_GHOST = COMMON_BUILDER
                .comment("v0.2 方块级剔除时给被剔除方块画半透明线框（幽灵轮廓），避免“墙被挖洞”看起来像渲染错误")
                .define("ghostOutline", true);
        CULLING_GHOST_FILL = COMMON_BUILDER
                .comment("v0.3 幽灵方块填充：在半透明线框之外再给被剔除方块画一层半透明青色体积（debugQuads，按面剔除内部面），可在 culling.ghostFill 关闭")
                .define("ghostFill", true);
    GHOST_CLEAR_SCREEN = COMMON_BUILDER
            .comment("按原型 GhostRenderer：角色屏幕投影点周围这个半径内（屏幕 y 轴 ±1 为满屏）幽灵完全透明，同时丢弃与它相交的面")
            .defineInRange("ghostClearScreen", 0.20D, 0.0D, 4.0D);
    GHOST_OPAQUE_SCREEN = COMMON_BUILDER
            .comment("屏幕距离达到该值后幽灵达到最大不透明度，之间用 smoothstep 过渡")
            .defineInRange("ghostOpaqueScreen", 1.40D, 0.0D, 8.0D);
    GHOST_MAX_ALPHA = COMMON_BUILDER
            .comment("幽灵最大不透明度（原型默认 0.90）")
            .defineInRange("ghostMaxAlpha", 0.90D, 0.0D, 1.0D);
        CULLING_SHAPE_ENABLED = COMMON_BUILDER
                .comment("v0.3 形状/视线剔除首片（旧模组 GenericCuller3 的锥/柱近似）：把相机与玩家之间的实心地形从区块 mesh 剔除；室内由房间扫描负责，可与 blockLevel 独立开关")
                .define("shape", false);
        CULLING_SHAPE_RADIUS = COMMON_BUILDER
                .comment("形状剔除圆柱半径（格，对应旧模组 cullAngle 默认 6.0）")
                .defineInRange("shapeRadius", 2.5D, 0.0D, 32.0D);
        CULLING_SHAPE_CONE_HALF_ANGLE = COMMON_BUILDER
                .comment("近玩家处锥形半角（度，对应旧模组 coneHalfAngle 默认 45）")
                .defineInRange("shapeConeHalfAngle", 30.0D, 0.0D, 89.0D);
        CULLING_SIGHTLINE_ENABLED = COMMON_BUILDER
                .comment("v0.3 视线/轮廓剔除（旧模组 SightlineScanner）：按相机→玩家 27 条射线命中的连通形状整块判定，挡住视线多数的形状整体剔除、挡得少的整块保护，避免把家具/柱子挖出洞")
                .define("sightline", true);
        CULLING_SIGHTLINE_OCCLUDE_THRESHOLD = COMMON_BUILDER
                .comment("形状被判定为\"遮挡\"所需的射线比例（0..1）；高于该值整块剔除，低于则该形状对其它剔除层受保护")
                .defineInRange("sightlineOccludeThreshold", 0.35D, 0.0D, 1.0D);
        CULLING_VOLUME = COMMON_BUILDER
                .comment("v0.4 主剔除策略：以“相机→角色眼睛”连线为轴，剔除轴附近的遮挡方块；开启时会替换 blockLevel/shape/sightline 旧策略")
                .define("sightlineVolume", false);
        CULLING_VOLUME_INNER_RADIUS = COMMON_BUILDER
                .comment("视线体积内半径（格）：此范围内的方块完全透明")
                .defineInRange("volumeInnerRadius", 3.0D, 0.0D, 16.0D);
        CULLING_VOLUME_OUTER_RADIUS = COMMON_BUILDER
                .comment("视线体积外半径（格）：超出该距离的方块不再透明；内外之间透明度快速上升")
                .defineInRange("volumeOuterRadius", 8.0D, 1.0D, 32.0D);
        CULLING_VOLUME_MAX_ALPHA = COMMON_BUILDER
                .comment("视线体积外缘的幽灵方块最大不透明度（0=完全透明，1=不透明）")
                .defineInRange("volumeMaxAlpha", 0.45D, 0.0D, 1.0D);
        CULLING_ROOM_MAX_DISTANCE = COMMON_BUILDER
                .comment("房间剔除的最大水平距离（格）：离角色更远的房间方块不再剔除")
                .defineInRange("roomMaxDistance", 24.0D, 4.0D, 96.0D);
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
