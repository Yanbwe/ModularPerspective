package org.yanbwe.modularperspective;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.yanbwe.modularperspective.api.camera.ServerControl;

/**
 * 配置骨架，按 docs/design.md §13 分三份：common（剔除/调试）、client（显示/输入偏好）、server（每世界策略）。
 */
public final class Config {
    private Config() {
    }

    // ------------------------------------------------------------------ common

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue CULLING_ENABLED;
    public static final ModConfigSpec.BooleanValue CULLING_GHOST;
    public static final ModConfigSpec.DoubleValue GHOST_CLEAR_SCREEN;
    public static final ModConfigSpec.DoubleValue GHOST_OPAQUE_SCREEN;
    public static final ModConfigSpec.DoubleValue GHOST_MAX_ALPHA;
    public static final ModConfigSpec.BooleanValue CULLING_SIGHTLINE_ENABLED;
    public static final ModConfigSpec.DoubleValue CULLING_SIGHTLINE_OCCLUDE_THRESHOLD;
    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    static {
        COMMON_BUILDER.comment("Culling: prototype scheme, decision in TerrainCulling (RoomScanner + SightlineScanner)").push("culling");
        CULLING_ENABLED = COMMON_BUILDER.comment("Master switch; off = no block is removed from meshes").define("enabled", true);
        CULLING_GHOST = COMMON_BUILDER.comment("Show culled blocks as translucent ghosts so the player keeps a sense of geometry").define("ghostOutline", true);
        GHOST_CLEAR_SCREEN = COMMON_BUILDER
                .comment("Ghost alpha is 0 inside this screen-space radius around the player's projection (y = +-1 full screen)")
                .defineInRange("ghostClearScreen", 0.20D, 0.0D, 4.0D);
        GHOST_OPAQUE_SCREEN = COMMON_BUILDER
                .comment("Screen distance where ghost alpha reaches ghostMaxAlpha")
                .defineInRange("ghostOpaqueScreen", 1.40D, 0.0D, 8.0D);
        GHOST_MAX_ALPHA = COMMON_BUILDER.comment("Maximum ghost alpha").defineInRange("ghostMaxAlpha", 0.90D, 0.0D, 1.0D);
        CULLING_SIGHTLINE_ENABLED = COMMON_BUILDER
                .comment("Sightline scanner: 27 rays camera->player, flood hit voxels into connected shapes, cull the shapes that block most rays")
                .define("sightline", true);
        CULLING_SIGHTLINE_OCCLUDE_THRESHOLD = COMMON_BUILDER
                .comment("Fraction of rays a shape must block to be culled (0..1); lower-scoring shapes are protected instead")
                .defineInRange("sightlineOccludeThreshold", 0.35D, 0.0D, 1.0D);
        COMMON_BUILDER.pop();

        COMMON_BUILDER.comment("Debug output").push("debug");
        DEBUG_OVERLAY = COMMON_BUILDER.comment("Show camera state / directive stack / culling stats overlay").define("overlay", false);
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
                .comment("Whether the server may control your camera: ALLOW / SUGGEST_ONLY / DISABLED")
                .defineEnum("serverControl", ServerControl.ALLOW);

        DEFAULT_PERSPECTIVE = CLIENT_BUILDER
                .comment("Enter each world with the orthographic top-down preset enabled (F4/F6 still toggle)")
                .define("defaultTopDown", true);

        HUD_EXAMPLES = CLIENT_BUILDER
                .comment("Show the built-in HUD example widgets (draggable badge + world-anchored marker)")
                .define("hudExamples", true);

        ORTHO_SKY = CLIENT_BUILDER
                .comment("Draw the vanilla sky in orthographic projection (experimental)")
                .define("orthoSky", false);

        CLIENT_BUILDER.comment("Zoom").push("zoom");
        ZOOM_SCROLL_ENABLED = CLIENT_BUILDER.comment("Ctrl+scroll zooms").define("scrollEnabled", true);
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
        SERVER_BUILDER.comment("Server policy, see docs/design.md §6.5").push("policy");
        POLICY_ALLOW_REQUIRE = SERVER_BUILDER.comment("Allow REQUIRE directives").define("allowRequire", true);
        POLICY_ALLOW_SUGGEST = SERVER_BUILDER.comment("Allow SUGGEST directives").define("allowSuggest", true);
        POLICY_MAX_DIRECTIVES = SERVER_BUILDER
                .comment("Maximum simultaneous directives per player")
                .defineInRange("maxDirectives", 64, 1, 1024);
        POLICY_MAX_DISTANCE = SERVER_BUILDER
                .comment("Maximum camera distance in blocks")
                .defineInRange("maxDistance", 32.0D, 1.0D, 128.0D);
        POLICY_ALLOW_ORTHOGRAPHIC = SERVER_BUILDER
                .comment("Allow switching to orthographic projection")
                .define("allowOrthographic", true);
        SERVER_BUILDER.pop();
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
