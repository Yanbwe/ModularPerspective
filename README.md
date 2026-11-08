# Modular Perspective（模块化俯视角）

面向 **NeoForge 1.21.1** 的双端俯视角 / 正交视角框架模组：服务端以"指令"描述镜头（位置、朝向、距离、投影、FOV、输入锁），
客户端做通道化仲裁后应用；不接管玩家移动逻辑、不做视觉校验。

- 设计文档：`docs/design.md`（本地文档，**不入 git**；v0.3）
- 许可证：GPL-3.0
- 当前状态：M0 骨架 + M1 相机核心 + M2 输入链 + M3 双端链首版（服务端指令栈、全量快照、attachment 持久化、策略/锁/回执上行；客户端仍为通道仲裁 + 帧级插值）+ M4 HUD 平台首版（挂件层 / 注册表 / 调试覆盖层） + M5 剔除首版（section 级近似：沿相机→玩家射线隐藏 section）

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

`/perspective camera <targets> <yaw> <pitch> [duration]`、`/perspective preset <targets> <top-down|reset> [duration]`、`/perspective lock <targets> <rotate|zoom|move|all> <on|off>`、`/perspective hud <targets> <on|off> [duration]`、`/perspective policy <targets> <allowRequire|allowSuggest|allowOrthographic> <on|off>`、`/perspective policy <targets> maxDirectives <int>`、`/perspective policy <targets> maxDistance <double>`、`/perspective policy <targets> query`、`/perspective clear <targets>`、`/perspective query <targets>`，权限等级 ≥2。
服务端栈 + 全量快照下发（登录/重生/换维度补发）+ attachment 持久化（随玩家存档保存、死亡按 `keepOnDeath` 清理）已接线；快照携带 revision/hudVisible/policy 与全量指令，客户端旧 revision 丢弃、只替换服务端来源（本地 F4/F6 预设保留）。上行链路：`CameraArrivedPayload`（到位回执）、`CameraOverriddenPayload`（SUGGEST 锁位被玩家操作顶掉）、`CameraRefusedPayload`（客户端策略禁用），服务端策略变更会发布 `PerspectivePolicyChangedEvent`。`camera`/`preset top-down` 的落位是眼睛沿视线后退 6 格的正交俯视。协议号 2（两端需同时升级）。完整设计语法与 M3 剩余项见 docs §12 / §16。

## HUD

HUD 是屏幕空间挂件层（`RegisterGuiLayersEvent#registerAboveAll`），不接管 Screen；`minecraft.screen != null`、`hideGui` 或相机通道 `hudVisible=false` 时整层跳过。内置左上角状态胶囊（点击切换调试覆盖层）与可选调试覆盖层（`debug.overlay` 配置项或点击胶囊临时开启）。

第三方模组可在客户端初始化时注册控件：

```java
HudRegistry.register(ResourceLocation.fromNamespaceAndPath("examplemod", "quest_panel"),
        ctx -> new QuestPanelWidget());
```

控件实现 `HudWidget`（锚点/偏移/尺寸/层级/是否穿透）；`clickThrough=false` 的控件会消费点击与滚轮，不触发原版攻击/挖掘；指针移出矩形后仍保持 capture 直到抬起（§10.3）。



## 剔除

v0.2 起默认 **方块级剔除**（`culling.blockLevel=true`）：沿相机→玩家 9 条视线每 0.25 格采样，命中实心方块后按 6 邻接 **BFS 把整个连通形状**从区块 mesh 中剔除（单形状上限 1024 块、全局 8192、只处理相机 48 格内、跳过玩家脚下及以下与玩家 section）；连通形状超过 1024 块（山体/屋顶）时退回“命中点周围 27 格视线隧道”，避免挖掉大片地形。重建只把状态变化的 section 按 16 个/tick 预算标脏（`RenderChunkRegionMixin` 把被剔除方块返回空气，只影响渲染，不改世界/碰撞）。被剔除方块会画 0.35 透明度的青色线框（`culling.ghostOutline`，默认开）。`culling.blockLevel=false` 退回 v0.1 section 级近似（每 2 tick 射线命中即隐藏整段 16³ section，`renderSectionLayer` 过滤）；`culling.enabled=false` 两者都关。开发期验收：五场景 section 级扫描最大 83us/过滤最大 28us（预算 2ms/0.2ms）；方块级 10×16 石墙 672 块被整体隐藏、关闭后完整恢复。房间扫描已按原模组语义重写（`RoomScanner` 4 阶段：泛洪记录 topAir → 每列求 roofBase/roofTop（跨楼层，负 y 用 `Integer.MIN_VALUE` 哨兵）→ 2D footprint dilate + 封闭空洞填充 → 不可变 Snapshot；`RenderChunkRegionMixin` 中先于方块级剔除，roofBase..roofTop 返回空气、roofTop 以上 KEEP 保护）；验收：5×5×3 房间 `INDOOR roof=9 wall=16 openSky=0`、天花板整段剔除并画线框，室外 `blocks=0`，小墙 672 块不回归。幽灵轮廓已升级为“线框 + 半透明填充体积”（`culling.ghostFill=false` 可退回纯线框，蓝图外观、按 6 邻接剔除内部面、上限 4096）；房间扫描加了 3ms 扫描硬预算（超时保留旧快照、下轮重试）与“头顶 8 格无实心直接判室外”的快速路径（室外不再每秒跑满 16000 次泛洪），调试 HUD 显示 `scan=...us`。尚未做 worker 线程化/房间图/门洞识别/房间合并；真正按方块模型/贴图的幽灵渲染留待后续；Sodium/光影兼容暂缓。

## 目录

```
src/main/java/org/yanbwe/modularperspective/
  ModularPerspective.java          主入口（common）
  ModularPerspectiveClient.java    客户端入口（mod bus 监听器、键位、HUD 层、自动验收）
  Config.java                      COMMON/CLIENT/SERVER 配置
  api/camera/                      公开类型：通道、指令、目标、快照、锁、强制级别、结束原因、缓动、投影、策略
  client/camera/                   相机栈与客户端预设（CameraStack / ClientPerspective）
  client/input/                    伪解锁光标状态机（CursorController）
  client/cull/                     剔除：BlockCuller（方块级）/ SectionCuller（section 级）/ RoomScanner（室内外）/ CullingGhostRenderer（线框+半透明填充）
  api/hud/                         公开 HUD 类型（Anchor/HudWidget/HudContext/HudLayout/HudRegistry）
  client/hud/                      HUD 宿主与内置控件（ClientHud/CameraStatusWidget/DebugHudWidget）
  client/PerspectiveClientNetwork  客户端 payload 接收（快照 → CameraStack）
  network/                         payload 定义与编解码（Snapshot / Arrived / Overridden / Refused / PerspectiveNetwork）
  server/PerspectiveCommand.java   /perspective 命令（camera / preset / lock / hud / policy / clear / query）
  server/PerspectiveServer.java    服务端指令栈与全量快照（attachment 持久化）
  server/PerspectiveAttachments.java   相机栈的 AttachmentType 注册
  server/PerspectiveAttachment.java    玩家持久化状态（Codec）
  mixin/                           相机/渲染/输入注入点
src/test/java/org/yanbwe/modularperspective/   纯逻辑单测（JUnit 5）
```
