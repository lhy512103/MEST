# Changelog

## Unreleased

### Workspace

- Rebuilt the layout editor as a full-canvas, AE2-styled authoring surface: the real floating
  workspace renders on a dark grid canvas surrounded by AE2 chrome (toolbar, module palette,
  property inspector), and every drag/splice/resize/detach gesture works directly on the canvas.
- Added press-and-drag palette placement: dragging a sidebar module entry reveals and places the
  module, detaching it from composite splits when needed, with the same undo/save semantics as
  in-canvas gestures.
- Added visual-programming splice affordances: while a root is dragged, the four edge zones of
  the hovered leaf light up and the zone that would receive the drop is emphasized.
- Replaced the flat snap-based panel layout with floating recursive split trees,
  including edge drop zones, nested joining, divider resizing and branch detach.
- Added z-order-aware rendering and input, effective leaf visibility, panel slot
  ownership, click-through blocking and viewport clamping.
- Added the v3 layout format with per-module visibility/movement/resize policies,
  v1/v2 migration, strict validation, atomic replacement and dirty-revision saves.
  Module policy is now the only visibility and interaction authority; legacy leaf
  visibility is migration-only compatibility data.
- Added a session-only layout lock, one-step undo, compact floating preset and
  reset-to-default control.
- Restyled the workspace with AE2-native panel backgrounds, search fields,
  buttons, scrollbars, slots, toolbars and crafting artwork.
- Removed repeated per-frame slot activation and geometry writes when the
  projected layout has not changed.
- Fixed floating panels keeping their item icons above other panels by moving slot rendering back to each root's own z layer.
- Fixed revealing a hidden panel leaving it at the bottom of the stack; the terminal module toggle now raises the panel it shows.

### Terminal Interaction

- Restored AE2-style ME list actions for container fill/empty, space-click region
  moves, shift-wheel insertion/extraction and pick-item autocrafting.
- Added craftable-only behavior and craftable indicators for both stocked and
  zero-stock entries while retaining view-cell filtering.
- Added separate crafting-grid clear actions for ME storage and player inventory.

### Pattern Workflows

- Added crafting, processing, smithing-table and stonecutting encoding modes to
  the floating pattern module.
- Added scrollable processing inputs, a scrollable full stonecutting recipe
  picker with native selection sound and an AE2-style processing amount editor.
- Added item/fluid quantity hints, craftable indicators, fluid substitution and
  fluid-container-aware interactions.
- Kept the encoded output intact when clearing pattern inputs and corrected
  processing and stonecutting scrolling.
- Rebuilt pattern-provider synchronization around menu epochs, epoch-scoped
  provider IDs, per-provider revisions, bounded chunks and client
  resynchronization.
- Added stale-action rejection, subscription cleanup, request/packet limits and
  conserving exchange and quick-move transactions for provider slots.
- Made provider request, action and packet budgets advance from server game time
  so repeated menu broadcasts cannot reset the per-tick limits.
- Cleared client provider-session state on terminal close, screen replacement
  and disconnect, while preserving updates during the processing-amount
  sub-screen.
- Removed client UI linkage from common packet classes by routing S2C handling
  through a client-installed bridge, keeping dedicated-server class loading safe.
- Corrected menu-derived stonecutting selection and fluid-substitution state
  refreshes, and reduced provider chunk preparation to one defensive copy.

### Integrations And Acquisition

- Added JEI and EMI transfer bridges that target the last-focused crafting or
  pattern-encoding module. Crafting uses AE2 transfer; encoding accepts crafting,
  smithing, stonecutting and generic processing recipes.
- Added independent `mestRunJei` and `mestRunEmi` Gradle development-runtime
  switches, with both viewers enabled by default.
- Isolated the launch-only client runtime from compile-only optional integrations
  and kept JEI-specific AE2/character-search addons out of EMI-only and
  viewer-disabled runs.
- Added a survival smithing upgrade from the wireless universal terminal,
  component preservation, recipe advancement, Curios tag and dedicated 16x16
  item texture.

### Security And Hardening

- Tightened the pattern-access remote-UI flow: menus open directly instead of simulating a right-click, and the keep-alive re-checks every second that the player still holds a linked terminal.
- Aligned the cache, pick-block and picker packets with the provider-action protocol: safe decoding, trailing-data rejection, menu validation and per-player per-tick rate limits.
- Raised the mixin require level so a failed injection fails fast instead of silently disabling the remote-menu keep-alive or restock overlay.
- Gated the pin-button sprite behind ExtendedAE and declared ExtendedAE and ClientSort as optional dependencies.
- Made `-PmestRunEmi=true` actually install the EMI runtime; EMI stays off by default.
- Extracted `WorkspaceUndoHistory` and `DropCandidate` from `DockManager` so the bounded undo ring is independently testable.

### Verification

- Added focused tests for recursive layout editing and persistence, v1 migration,
  ME interaction policy, recipe-transfer targeting, provider protocol state,
  provider chunk planning, resource consistency and lossless inventory transfer
  behavior.
- Corrected the recipe advancement resource path, removed unused client style and
  configuration files, and pruned orphaned translations.
- Added dependency locking, reproducible archive settings, bounded dependency
  metadata and Java 21 CI wrapper validation with retained build artifacts.
- Full in-game GUI, reconnect/provider-churn and JEI/EMI combination checks remain
  part of the release verification matrix.

### 中文

- 工作区：修复浮窗物品图标始终盖在其它面板之上，槽位渲染移回各自根的 z 层。
- 工作区：修复显示隐藏面板后仍留在最底层，终端模块切换改为显示的同时置顶。
- 安全：收紧样板访问远程开 UI，直接打开菜单而非模拟右键，保活每秒重检玩家仍持有已链接终端。
- 安全：将缓存、取物、供应器选择三个包的校验对齐到样板供应器动作协议，含安全解码、拒绝尾部数据、校验菜单与每玩家每 tick 限流。
- 安全：提高 mixin 的 require 级别，注入失败不再静默退化。
- 依赖：钉住按钮贴图按 ExtendedAE 加载状态守卫，ExtendedAE 与 ClientSort 声明为可选依赖。
- 依赖：`-PmestRunEmi=true` 现在真正安装 EMI runtime，默认仍关闭。
- 重构：从 `DockManager` 抽出 `WorkspaceUndoHistory` 与 `DropCandidate`，撤销环独立可测。
