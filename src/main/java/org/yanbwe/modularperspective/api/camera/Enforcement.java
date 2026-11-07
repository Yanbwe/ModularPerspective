package org.yanbwe.modularperspective.api.camera;

/**
 * 指令的强制级别（docs/design.md §5.4）。
 */
public enum Enforcement {
    /** 建议：玩家操作可以把该通道/该轴顶掉（仍会发一次 Overridden 回执）。 */
    SUGGEST(0),
    /** 强制：玩家不能顶掉；客户端禁用服务端控制时回复 Refused(DISABLED)。 */
    REQUIRE(1);

    private final int weight;

    Enforcement(int weight) {
        this.weight = weight;
    }

    /** 仲裁用权重：同 priority 下 {@link #REQUIRE} 优于 {@link #SUGGEST}（docs/design.md §5.2）。 */
    public int weight() {
        return weight;
    }
}
