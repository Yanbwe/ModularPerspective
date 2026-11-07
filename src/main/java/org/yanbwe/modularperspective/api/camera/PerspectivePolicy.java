package org.yanbwe.modularperspective.api.camera;

/**
 * 服务端策略（docs/design.md §6.5）：限制单个玩家可用的指令能力。
 *
 * <p>服务端在 {@code push}/{@code play} 时校验，越界直接抛 {@link IllegalArgumentException} 且不下发；
 * 客户端 {@code serverControl} 只能把策略变得更严格（§6.5、§13.2）。
 */
public record PerspectivePolicy(
        boolean allowRequire,
        boolean allowSuggest,
        int maxDirectives,
        float maxDistance,
        boolean allowOrthographic) {

    public static final PerspectivePolicy DEFAULT = new PerspectivePolicy(true, true, 64, 32.0F, true);
    public static final PerspectivePolicy STRICT = new PerspectivePolicy(false, true, 16, 16.0F, false);
}
