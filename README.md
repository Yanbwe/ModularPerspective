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

v0.2 起默认 **方块级剔除**（`culling.blockLevel=true`）：沿相机→玩家 9 条视线每 0.25 格采样，命中实心方块后按 6 邻接 **BFS 把整个连通形状**从区块 mesh 中剔除（单形状上限 1024 块、全局 8192、只处理相机 48 格内、跳过玩家脚下及以下与玩家 section）；连通形状超过 1024 块（山体/屋顶）时退回“命中点周围 27 格视线隧道”，避免挖掉大片地形。重建只把状态变化的 section 按 16 个/tick 预算标脏（`RenderChunkRegionMixin` 把被剔除方块返回空气，只影响渲染，不改世界/碰撞）。被剔除方块会画 0.35 透明度的青色线框（`culling.ghostOutline`，默认开）。`culling.blockLevel=false` 退回 v0.1 section 级近似（每 2 tick 射线命中即隐藏整段 16³ section，`renderSectionLayer` 过滤）；`culling.enabled=false` 两者都关。开发期验收：五场景 section 级扫描最大 83us/过滤最大 28us（预算 2ms/0.2ms）；方块级 10×16 石墙 672 块被整体隐藏、关闭后完整恢复。房间扫描已按原模组语义重写（`RoomScanner` 4 阶段：泛洪记录 topAir → 每列求 roofBase/roofTop（跨楼层，负 y 用 `Integer.MIN_VALUE` 哨兵）→ 2D footprint dilate + 封闭空洞填充 → 不可变 Snapshot；`RenderChunkRegionMixin` 中先于方块级剔除，roofBase..roofTop 返回空气、roofTop 以上 KEEP 保护）；验收：5×5×3 房间 `INDOOR roof=9 wall=16 openSky=0`、天花板整段剔除并画线框，室外 `blocks=0`，小墙 672 块不回归。幽灵轮廓已升级为“半透明方块贴图体积”（无边框，`culling.ghostFill=false` 可关闭，按距离渐变透明度、跳过内部面、上限 4096）；房间扫描加了 3ms 扫描硬预算（超时保留旧快照、下轮重试）与“头顶 8 格无实心直接判室外”的快速路径（室外不再每秒跑满 16000 次泛洪），调试 HUD 显示 `scan=...us`。房间扫描已移到独立 worker 线程（客户端线程只做 section 快照捕获与结果换入，单次捕获约 38us、总计约 1ms，仍有 3ms 预算）。形状/视线剔除首片（SHAPE tier）已按 `GenericCuller3` 语义接线：config `culling.shape`（默认开）、`culling.shapeRadius`=6.0、`culling.shapeConeHalfAngle`=45°，`ShapeCuller` 按“脚部+1 格以下不剔/相机 5 格内必剔/到相机-玩家轴线垂距 ≤ min(投影×tan 半角, 半径)”判定，`BlockCuller#carveCone` 遍历包围盒并跳过门/梯子/墙挂方块/刷怪箱/宝库与房间层 KEEP；验收 9×5×2 石墙 90 块中 86 块被剔除、玩家可见，关闭后完整恢复；另已补三项 parity（详见 docs §16）：完整 SightlineScanner 视线轮廓 tier（27 射线 + TREE/BUILT/TERRAIN 连通形状 + rayMask 评分，sightline-solid 108 块整墙剔除、拱墙 0 误剔）；门洞计数（`DoorwayCounter` 墙线 run 语义，2 宽门洞 doors=2、密封房间 0）；贴图幽灵（方块模型 + 半透明方块图集、全亮蓝图风格，覆盖方块/视线/房间三类，上限 4096）。仍留：房间图/门洞语义扩展（原型本身无房间图，合并由 flood 天然完成）、Sodium/光影兼容暂缓。

被剔除方块以方块贴图的半透明体积显示（无边框），透明度随与角色的距离渐变：越靠近角色越透明，越远越清晰；树叶/原木也会参与遮挡剔除，锥形层在相机近处有投影保护，不会把脚边地形整片抹掉。正交俯视下光标选块会跳过被剔除方块，不会误挖角色背后的半透明方块；角色头部会朝光标命中的方块方向转动（退出俯视即恢复）。

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
