# ME Spliced Terminal

ME Spliced Terminal is an experimental NeoForge 1.21.1 terminal for AE2 and
AE2WTLib. It combines storage, crafting and pattern workflows in a client-local
workspace whose modules can be moved, resized, split and rejoined.

## Terminal

- Registers a wireless AE2WTLib terminal item and a custom menu derived from
  AE2's `CraftingTermMenu`.
- Provides modules for the ME item list, crafting, pattern encoding, pattern
  provider access, player inventory, terminal upgrades and view cells.
- Uses a smithing transform to upgrade an AE2WTLib wireless universal terminal.
  Compatible data components from the base terminal, including link, energy and
  upgrade data, are retained by the result.
- Includes recipe advancement data, the standard Curios `curio` item tag and a
  dedicated 16x16 terminal texture.
- Exposes three terminal upgrade slots for the registered AE2WTLib energy-card
  and quantum-bridge-card upgrades.

## Recursive Workspace

- Represents each floating window as a recursive tree of horizontal or vertical
  splits rather than a flat collection of edge-snapped panels.
- Supports edge drop zones for joining trees, draggable split dividers and
  detaching a split branch back into its own floating window.
- Keeps floating windows movable and resizable, applies viewport clamping and
  routes rendering and input according to visible leaf ownership and z-order.
- Ships a full-canvas AE2-styled layout editor: the live floating workspace is
  edited on a dark grid canvas with a module palette sidebar (press-and-drag an
  entry to place or reveal a module), a property inspector and four-way
  splice-zone highlights while dragging.
- Provides module visibility toggles, a session-only layout lock, one-step undo,
  a compact floating preset and a reset-to-default action.
- Stores tree structure, split ratios, window geometry, per-module interaction
  policy and z-order in `config/mesplicedterminal/layout.json` using the v3
  format. Module policy is the sole authority for visibility, movement and
  resizing; legacy leaf visibility is migrated into that policy. Legacy
  unversioned v1 and versioned v2 layouts are migrated when loaded.
- Writes only dirty layout revisions and replaces the JSON file atomically when
  the platform supports atomic moves. The session lock and undo snapshot are
  intentionally not persisted.

## ME Storage And Crafting

- Reuses AE2's incremental item repository, sorting, display modes, view-cell
  filtering, craftable-only mode and scalable slot rendering.
- Routes ME list interactions through the inherited AE2 menu actions, including
  held-container fill/empty, space-click region moves, shift-wheel single-item
  insertion/extraction and pick-item autocrafting (middle-click by default).
- Shows craftable markers alongside stored amounts and handles entries that are
  craftable without currently being stocked.
- Keeps the normal 3x3 crafting grid and result behavior, with separate actions
  to clear the grid into ME storage or the player inventory.

## Pattern Workflows

- Embeds AE2 `PatternEncodingLogic` with crafting, processing, smithing-table and
  stonecutting modes.
- Provides scrolling for processing slots and the full stonecutting recipe list,
  plus AE2's native processing-amount editor for large item or fluid amounts.
- Supports substitution, crafting fluid substitution, processing output cycling,
  item/fluid rendering, craftable indicators and quantity hints. Clearing the
  encoding inputs leaves the already encoded output available.
- Lists visible pattern providers and their encoded pattern inventories. Left
  click exchanges a provider slot with the carried encoded pattern; right click
  transfers toward the player inventory while preserving any remainder.
- Synchronizes provider state per open menu with epochs, epoch-scoped provider
  IDs, revisions and bounded chunks. Common packet registration and server
  validation remain free of client UI classes; a client-installed bridge owns
  screen/session updates. The server rejects stale actions, rate-limits requests
  against server game time and tears down subscriptions when the menu closes.
  The client also clears its session state when leaving the terminal, including
  disconnect and screen-replacement paths.

## JEI And EMI

- Includes optional JEI and EMI recipe-transfer bridges for the MEST menu.
- The last-focused crafting or encoding module is the transfer target. Crafting
  recipes use AE2's normal crafting transfer path when crafting is focused;
  crafting, smithing, stonecutting and generic processing recipes populate the
  matching encoding mode when pattern encoding is focused.
- Local development enables JEI by default and leaves EMI opt-in. Use
  `-PmestRunJei=false` to drop JEI, or `-PmestRunEmi=true` to add EMI. EMI
  runtime was parked while the ExtendedAE Plus encoding work landed and has
  not been re-verified since, so it stays off by default to keep the default
  dev loop clean. These properties are development switches, not player-facing
  mod configuration. The launch-only client classpath is assembled from runtime
  dependencies so compile-only JEI/EMI integrations cannot leak into disabled
  combinations; JEI-only helpers are also omitted when JEI is disabled.

## Development Notes

- Prefer public AE2 and AE2WTLib APIs; the custom provider packets and optional
  viewer bridges do not require screen mixins or reflection.
- Layout persistence is client-local UI preference, while ME inventory, crafting
  and pattern mutations remain server-authoritative.
- Automated tests cover the layout model and codec, atomic storage, ME
  interaction policy, recipe-target selection, provider client state, chunk
  planning, conserving inventory transfers and resource path/key consistency.
  Dependency locks are committed, archives use deterministic ordering and
  timestamps, and CI validates the wrapper before building with Java 21.
  Compilation and unit tests do not replace in-game checks across GUI scales,
  nested layouts, reconnects and optional viewer combinations.

## Verification

Run the standard checks with:

```powershell
.\gradlew.bat processResources
.\gradlew.bat test
.\gradlew.bat build
```

Viewer dependency combinations can be inspected with the same Gradle properties,
for example:

```powershell
.\gradlew.bat dependencies --configuration runtimeClasspath -PmestRunJei=false -PmestRunEmi=true
```
