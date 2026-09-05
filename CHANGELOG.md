# Changelog

## Unreleased

### Workspace

- Rebuilt the layout editor as a full-canvas AE2-styled surface with palette drag-place and splice-zone highlights.
- Replaced snap-based panels with floating recursive split trees, including join, resize, detach, z-order, and viewport clamping.
- Added the v3 layout format with per-module policies, v1/v2 migration, validation, and atomic dirty-revision saves.
- Added a session-only layout lock, one-step undo, compact preset, and reset-to-default.
- Restyled the workspace with AE2-native chrome and skipped unchanged per-frame slot/geometry writes.
- Added a third splice mode that draws one outer shell with 1px section rules, while keeping stretch and compact splice.
- In shell layout editing, double-click a panel to nudge its content inside the fixed section, with centre-guide snap, reset, and close.
- Fixed panel item icons rendering above other panels, and raising a panel when its module is shown.

### Terminal Interaction

- Restored AE2-style ME list actions for fill/empty, region moves, shift-wheel transfer, and pick-item autocrafting.
- Added craftable-only filtering, craftable indicators, and separate crafting-grid clear actions for ME and player inventory.

### Pattern Workflows

- Added crafting, processing, smithing, and stonecutting encoding modes with scrolling pickers and an AE2-style amount editor.
- Added quantity hints, craftable indicators, fluid substitution, and keep-encoded-output-on-clear behavior.
- Rebuilt provider sync around menu epochs, revisions, bounded chunks, stale-action rejection, and per-tick budgets on server game time.
- Cleared client provider session state on close/disconnect and routed S2C handling through a client-only bridge.

### Integrations And Acquisition

- Added JEI and EMI transfer bridges that target the last-focused crafting or encoding module.
- Added independent `mestRunJei` and `mestRunEmi` runtime switches and isolated optional viewer integrations from compile.
- Added `runClientEmi` so the Gradle task tree can launch the client with EMI without `-P` flags.
- Added a survival smithing upgrade from the wireless universal terminal, with component preservation, advancement, Curios tag, and item texture.

### Security And Hardening

- Tightened remote pattern-access UI so menus open directly and keep-alive re-checks the linked terminal every second.
- Aligned cache, pick-block, and picker packets with the provider-action protocol, including rate limits and trailing-data rejection.
- Raised mixin require so failed injections fail fast, and gated the pin-button sprite behind optional ExtendedAE.
- Made `-PmestRunEmi=true` actually install the EMI runtime; EMI stays off by default.
- Extracted `WorkspaceUndoHistory` and `DropCandidate` from `DockManager` for independent testing.

### Verification

- Added focused tests for layout, ME interaction, recipe transfer, provider protocol, and inventory transfer.
- Corrected the recipe advancement path and pruned unused client files and translations.
- Added dependency locking, reproducible archives, and Java 21 CI wrapper validation.

### 工作区

- 布局编辑器改为全画布 AE2 风格，支持从侧栏拖放放置模块，并在拖动时高亮拼接区域。
- 用可递归拼接的浮窗树替换吸附式面板，支持拼接、分割条缩放、拆分、z 序与视口钳制。
- 新增 v3 布局格式：按模块策略控制可见/移动/缩放，含 v1/v2 迁移、校验与脏修订原子保存。
- 新增会话级布局锁定、一步撤销、紧凑预设与重置默认。
- 工作区改用 AE2 原生控件风格，布局未变化时不再每帧重复写槽位与几何。
- 新增第三种拼接模式：整窗外框一次成型、内部 1px 分隔线，保留拉伸与紧凑两种原有模式。
- 外壳布局编辑中可双击面板微调内容位置，带中线吸附、重置与叉号退出。
- 修复浮窗物品图标盖住其它面板，以及显示隐藏面板后仍留在最底层的问题。

### 终端交互

- 恢复 AE2 风格的 ME 列表操作：容器填倒、空格区域移动、Shift 滚轮存取、取物自动合成。
- 新增仅显示可合成、可合成标记，以及分别清空到 ME 或玩家背包的合成格清空操作。

### 样板流程

- 浮动样板模块支持合成、处理、锻造与切石编码，含滚动选择与 AE2 风格数量编辑。
- 新增数量提示、可合成标记、流体替代，清空输入时保留已编码输出。
- 按菜单 epoch、修订号、分块与每 tick 预算重建供应器同步，并拒绝过期操作。
- 关闭/断线时清理客户端供应器会话，S2C 经客户端桥接处理以免污染服务端类加载。

### 集成与获取

- 新增 JEI/EMI 配方传输桥，目标为最近聚焦的合成或编码模块。
- 新增独立的 `mestRunJei` / `mestRunEmi` 运行时开关，并将可选查看器与编译隔离。
- 新增 `runClientEmi`，Gradle 任务树可直接带 EMI 启动客户端，不必再传 `-P`。
- 新增由无线通用终端锻造升级的生存获取路径，保留组件、进度、Curios 标签与物品贴图。

### 安全与加固

- 收紧远程样板开 UI：直接打开菜单，保活每秒重检玩家仍持有已链接终端。
- 将缓存、取物、选择包的校验对齐到供应器动作协议，含限流与拒绝尾部数据。
- 提高 mixin require，注入失败立即报错；钉住按钮贴图按可选 ExtendedAE 守卫。
- `-PmestRunEmi=true` 现在会真正安装 EMI runtime，默认仍关闭。
- 从 `DockManager` 抽出 `WorkspaceUndoHistory` 与 `DropCandidate`，撤销环独立可测。

### 验证

- 补充布局、ME 交互、配方传输、供应器协议与物品转移相关测试。
- 修正配方进度资源路径，并清理无用客户端文件与孤立翻译。
- 增加依赖锁定、可复现归档与 Java 21 CI wrapper 校验。
