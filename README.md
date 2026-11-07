# Modular Perspective（模块化俯视角）

面向 **NeoForge 1.21.1** 的双端俯视角 / 正交视角框架模组：服务端以"指令"描述镜头（位置、朝向、距离、投影、FOV、输入锁），
客户端做通道化仲裁后应用；不接管玩家移动逻辑、不做视觉校验。

- 设计文档：`docs/design.md`（本地文档，**不入 git**；v0.3）
- 许可证：GPL-3.0
- 当前状态：M0 骨架（入口 / 配置 / 命令占位 / Mixin 占位 / 单测），功能从 M1 起逐步落地

## 环境

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Java | 21（本机若默认更高，请配 toolchain） |
| ModDevGradle | 2.0.141 |
| Gradle | 9.2.1（wrapper） |

## 构建

```bash
./gradlew build test      # 编译 + 单测
./gradlew runClient       # 客户端开发运行
./gradlew runServer       # 服务端开发运行（--nogui）
```

## 配置

`config/modularperspective-common.toml`（双端）、`-client.toml`（客户端）、`-server.toml`（世界）。
字段含义见设计文档 §13；旧版（DungeonsPerspective）配置的迁移表见 §13.3。

## 命令

`/perspective camera|query|clear` —— M0 仅有占位输出，权限等级 ≥2。完整语法见 §12。

## 目录

```
src/main/java/org/yanbwe/modularperspective/
  ModularPerspective.java          主入口（common）
  ModularPerspectiveClient.java    客户端入口（EventBusSubscriber）
  Config.java                      COMMON/CLIENT/SERVER 配置
  api/camera/                      公开类型：通道、强制级别、结束原因、缓动、投影、客户端策略
  server/PerspectiveCommand.java   命令骨架
  mixin/CameraMixin.java           相机落位注入点（M1 填充）
```
