# Modular Perspective（模块化俯视角）

面向 **NeoForge 1.21.1** 的双端俯视角 / 正交视角框架模组：服务端以"指令"描述镜头（位置、朝向、距离、投影、FOV、输入锁），
客户端做通道化仲裁后应用；不接管玩家移动逻辑、不做视觉校验。

- 设计文档：`docs/design.md`（本地文档，**不入 git**；v0.3）
- 许可证：GPL-3.0
- 当前状态：M0 骨架 + M1 相机核心 + M2 输入链（正交俯视、F5 第三人称、伪解锁光标、中键拖拽/滚轮、Home 吸附、WASD 相对屏幕）

## 环境

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.233 |
| Java | 21（本机若默认更高，请配 toolchain） |
| ModDevGradle | 2.0.141 |
| Gradle | 9.2.1（wrapper） |

## 构建与运行

PowerShell 下：

```powershell
.\gradlew.bat build test    # 编译 + 单测
.\gradlew.bat runClient     # 客户端开发运行
.\gradlew.bat runServer     # 服务端开发运行（--nogui）
```

## 配置

`config/modularperspective-common.toml`（双端）、`-client.toml`（客户端）、`-server.toml`（世界）。
字段含义见设计文档 §13；旧版（DungeonsPerspective）配置的迁移表见 §13.3。

## 命令

`/perspective camera <targets> <yaw> <pitch> [duration]`、`/perspective preset <targets> <top-down|reset> [duration]`、`/perspective lock <targets> <rotate|zoom|move|all> <on|off>`、`/perspective clear <targets>`、`/perspective query <targets>`，权限等级 ≥2。
服务端栈 + 全量快照下发（登录/重生/换维度补发）+ attachment 持久化（随玩家存档保存、死亡按 `keepOnDeath` 清理）已接线；`camera`/`preset top-down` 的落位是眼睛沿视线后退 6 格的正交俯视。完整设计语法与 M3 剩余项见 docs §12 / §16。

## 目录

```
src/main/java/org/yanbwe/modularperspective/
  ModularPerspective.java          主入口（common）
  ModularPerspectiveClient.java    客户端入口（mod bus 监听器、键位、HUD 层、自动验收）
  Config.java                      COMMON/CLIENT/SERVER 配置
  api/camera/                      公开类型：通道、指令、目标、快照、锁、强制级别、结束原因、缓动、投影、策略
  client/camera/                   相机栈与客户端预设（CameraStack / ClientPerspective）
  client/input/                    伪解锁光标状态机（CursorController）
  client/PerspectiveClientNetwork  客户端 payload 接收（快照 → CameraStack）
  network/                         payload 定义与编解码（CameraSnapshotPayload / CameraArrivedPayload / PerspectiveNetwork）
  server/PerspectiveCommand.java   /perspective 命令（camera / preset / lock / clear / query）
  server/PerspectiveServer.java    服务端指令栈与全量快照（attachment 持久化）
  server/PerspectiveAttachments.java   相机栈的 AttachmentType 注册
  server/PerspectiveAttachment.java    玩家持久化状态（Codec）
  mixin/                           相机/渲染/输入注入点
src/test/java/org/yanbwe/modularperspective/   纯逻辑单测（JUnit 5）
```
