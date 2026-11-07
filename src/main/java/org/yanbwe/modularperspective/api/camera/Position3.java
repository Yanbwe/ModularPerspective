package org.yanbwe.modularperspective.api.camera;

/**
 * 与 Minecraft 解耦的三维位置/向量（core 模型不引用 {@code net.minecraft.world.phys.Vec3}，
 * 这样相机仲裁与指令模型可以纯 JUnit 测试；渲染边界处再转成 {@code Vec3}）。
 *
 * <p>坐标系与原版相机一致：世界坐标，单位方块；y 向上。
 */
public record Position3(double x, double y, double z) {
    public static final Position3 ORIGIN = new Position3(0.0, 0.0, 0.0);

    public Position3 add(double dx, double dy, double dz) {
        return new Position3(x + dx, y + dy, z + dz);
    }

    public Position3 subtract(double dx, double dy, double dz) {
        return new Position3(x - dx, y - dy, z - dz);
    }

    public Position3 scale(double scale) {
        return new Position3(x * scale, y * scale, z * scale);
    }

    public double distanceSquared(Position3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
