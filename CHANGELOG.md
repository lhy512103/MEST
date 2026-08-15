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
