OK: README camera syntax # Modular Perspective（模块化俯视角）

面向 **NeoForge 1.21.1** 的双端俯视角 / 正交视角框架模组：服务端以"指令"描述镜头（位置、朝向、距离、投影、FOV、输入锁），
客户端做通道化仲裁后应用；不接管玩家移动逻辑、不做视觉校验。

- 设计文档：`docs/design.md`（本地文档，**不入 git**；v0.3）
- 许可证：GPL-3.0
- 当前状态：M0 骨架 + M1 相机核心 + M2 输入链 + M3 双端链首版（服务端指令栈、全量快照、attachment 持久化、策略/锁/回执上行；客户端仍为通道仲裁 + 帧级插值）+ M4 HUD 平台（挂件层 / 注册表 / 调试覆盖层 / 可拖拽与世界锚定示例控件） + M5 剔除首版（section 级近似：沿相机→玩家射线隐藏 section）

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

`/perspective camera <targets> [position <x> <y> <z>] [rotation <yaw> <pitch>] [distance <blocks>] [fov <degrees>] [projection <perspective|orthographic>] [duration <ticks>] [priority <n>] [enforcement <suggest|require>] [locks <rotate|zoom|move|all>...]`（链式解析：选项可任意顺序组合、可省略；`locks` 贪婪吞掉剩余参数，必须放在最后；也可只写单个选项，便于命令补全）、`/perspective preset <targets> <top-down|reset> [duration]`、`/perspective lock <targets> <rotate|zoom|move|all> <on|off>`、`/perspective hud <targets> <on|off> [duration]`、`/perspective policy <targets> <allowRequire|allowSuggest|allowOrthographic> <on|off>`、`/perspective policy <targets> maxDirectives <int>`、`/perspective policy <targets> maxDistance <double>`、`/perspective policy <targets> query`、`/perspective clear <targets>`、`/perspective query <targets>`，权限等级 ≥2。
服务端栈 + 全量快照下发（登录/重生/换维度补发）+ attachment 持久化（随玩家存档保存、死亡按 `keepOnDeath` 清理）已接线；快照携带 revision/hudVisible/policy 与全量指令，客户端旧 revision 丢弃、只替换服务端来源（本地 F4/F6 预设保留）。上行链路：`CameraArrivedPayload`（到位回执）、`CameraOverriddenPayload`（SUGGEST 锁位被玩家操作顶掉）、`CameraRefusedPayload`（客户端策略禁用），服务端策略变更会发布 `PerspectivePolicyChangedEvent`。`camera`/`preset top-down` 的落位是眼睛沿视线后退 6 格的正交俯视。协议号 2（两端需同时升级）。完整设计语法与 M3 剩余项见 docs §12 / §16。

## 更新日志

见 `CHANGELOG.md`；wiki（中英）位于 `C:\Resources\YanbweWiki\doc\modularperspective`。

## HUD

HUD 是屏幕空间挂件层（`RegisterGuiLayersEvent#registerAboveAll`），不接管 Screen；`minecraft.screen != null`、`hideGui` 或相机通道 `hudVisible=false` 时整层跳过。内置左上角状态胶囊（点击切换调试覆盖层）与可选调试覆盖层（`debug.overlay` 配置项或点击胶囊临时开启）。

第三方模组可在客户端初始化时注册控件：

```java
HudRegistry.register(ResourceLocation.fromNamespaceAndPath("examplemod", "quest_panel"),
        ctx -> new QuestPanelWidget());
```

控件实现 `HudWidget`（锚点/偏移/尺寸/层级/是否穿透）；`clickThrough=false` 的控件会消费点击与滚轮，不触发原版攻击/挖掘；指针移出矩形后仍保持 capture 直到抬起（§10.3）。M4 收尾新增：`draggable()` 让宿主转发拖拽（按下/位移/松开回调），`worldAnchor(HudContext)` 让控件跟随世界坐标（仅正交俯视时投影成 GUI 坐标）；内置两个示例控件（可拖拽徽标、头顶世界锚定标记），配置项 `hudExamples` 可关闭，`defaultTopDown` 控制进世界是否默认俯视。



## 剔除

v0.5 起剔除系统整体换成原型 `DungeonsPerspective` 的语义：区块网格构建时，被剔除方块直接按空气参与（`RenderChunkRegionMixin` 返回 `Blocks.AIR`），方块模型烘焙时也把被剔除方块模型替换为空 quad（`ModelEvent.ModifyBakingResult` + `BakedModelWrapper`）。两级判定：先按房间扫描（`RoomScanner`，屋顶/墙壁/门洞计数；判定偏保守，任一开阔天空列即室外），再按视线轮廓扫描（`SightlineScanner`，27 条相机→玩家射线、按 `OccluderClass` 把连通遮挡体分组并按“挡住多少射线”整块剔除或保护；树叶/原木也算遮挡体）。被剔除方块以方块贴图的半透明体积显示：角色投影点周围完全透明，随屏幕距离增加逐渐显形（`ghostClearScreen=0.20`、`ghostOpaqueScreen=1.40`、`ghostMaxAlpha=0.90`），无边框、跳过内部面。旧 section 级/方块级/锥形/视线体积策略已删除。相关配置：`culling.enabled`、`culling.sightline`、`culling.sightlineOccludeThreshold`、`culling.ghostOutline`、`ghostClearScreen`、`ghostOpaqueScreen`、`ghostMaxAlpha`。
## 目录

```
src/main/java/org/yanbwe/modularperspective/
  ModularPerspective.java          主入口（common）
  ModularPerspectiveClient.java    客户端入口（mod bus 监听器、键位、HUD 层、自动验收）
  Config.java                      COMMON/CLIENT/SERVER 配置
  api/camera/                      公开类型：通道、指令、目标、快照、锁、强制级别、结束原因、缓动、投影、策略
  client/camera/                   相机栈与客户端预设（CameraStack / ClientPerspective）
  client/input/                    伪解锁光标状态机（CursorController）
  client/cull/                     剔除：TerrainCulling（门面）/ RoomScanner（房间）/ SightlineScanner（视线轮廓）/ CullingModels（模型烘焙剔除）/ CullingGhostRenderer（屏幕空间半透明幽灵）
  api/hud/                         公开 HUD 类型（Anchor/HudWidget/HudContext/HudLayout/HudRegistry）
  client/hud/                      HUD 宿主与内置控件（ClientHud/CameraStatusWidget/DebugHudWidget/ExampleBadgeWidget/WorldAnchorWidget）
  client/PerspectiveClientNetwork  客户端 payload 接收（快照 → CameraStack）
  network/                         payload 定义与编解码（Snapshot / Arrived / Overridden / Refused / PerspectiveNetwork）
  server/PerspectiveCommand.java   /perspective 命令（camera / preset / lock / hud / policy / clear / query）
  server/PerspectiveServer.java    服务端指令栈与全量快照（attachment 持久化）
  server/PerspectiveAttachments.java   相机栈的 AttachmentType 注册
  server/PerspectiveAttachment.java    玩家持久化状态（Codec）
  mixin/                           相机/渲染/输入注入点
src/test/java/org/yanbwe/modularperspective/   纯逻辑单测（JUnit 5）
```

幽灵现在按原型屏幕空间曲线渐隐（`ghostClearScreen`/`ghostOpaqueScreen`/`ghostMaxAlpha`），不再有蓝色线框；默认策略为原型主路径（视线轮廓 + 房间扫描），`sightlineVolume` 默认关闭可手动开；`sectionApproximation` 默认关闭（旧“整段消失”的大块剔除需要显式开启）。
