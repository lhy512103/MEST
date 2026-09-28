# Compatibility Verification

## Automated checks

`checkViewerLaunchClasspaths` inspects the declared dependencies of the two client launch
source sets. It verifies the JEI and EMI flags, the always-EMI `runClientEmi` path, and that
compile-only viewer APIs have not leaked into launch dependencies. It intentionally does not
resolve CurseForge, JEI, EMI, or their transitive dependency graphs.

CI runs this declaration check for all four combinations:

| `mestRunJei` | `mestRunEmi` | Expected default client |
| --- | --- | --- |
| `true` | `false` | JEI only |
| `true` | `true` | JEI + EMI |
| `false` | `false` | neither viewer |
| `false` | `true` | EMI only |

The check is therefore a wiring check, not proof that downloaded artifacts load together.

## Manual game checklist

Run the corresponding client task with a backed-up or separate test game directory when changing
viewer versions or compatibility code. Do not delete existing worlds or player configuration.

- Start `runClient` with JEI enabled and EMI disabled; confirm the terminal opens and JEI transfer targets the focused crafting and encoding modules.
- Start `runClientEmi`; confirm EMI loads, the terminal opens, and EMI transfer targets the focused module without duplicate or missing recipe actions.
- Start each disabled-viewer combination; confirm startup completes and no optional viewer class is loaded by MEST.
- Open the terminal at Minecraft GUI scales 1, 2, 3, 4 and Auto where supported; inspect nested splits, floating windows, scrolling panels, outside rails, and the pattern-encoding tab at 800x600, 1280x720 and 1920x1080.
- Exercise crafting, processing, smithing, stonecutting, fluid substitution, output cycling, and clearing inputs while a viewer transfer is pending.
- Place and remove upgrades, including more than three available upgrade slots from another wireless-terminal provider; confirm all dynamic slots remain usable.
- Create more than one layout edit, undo repeatedly up to the bounded history, reload the screen, reconnect, and verify session-only lock and undo behavior.
- Check a dedicated-server build and a client with no viewer mods installed; neither should require client-only viewer classes.

Record the Minecraft, NeoForge, AE2, AE2WTLib, JEI, EMI, and ExtendedAE Plus versions with each manual result.

## Current status and rollout risk

- Automated: Java 21 compilation, unit tests, resource checks, and declaration-level viewer matrix checks are covered by CI.
- Not automated: a real Minecraft client startup, recipe transfer against installed JEI/EMI, GUI-scale rendering, reconnect behavior, and the full optional-mod stack.
- Dependency locking is not enabled in this repository; version properties and CurseForge file IDs are the current source of truth.
- The README's upgrade wording is dynamic with a three-slot fallback, and layout undo is bounded to 32 snapshots rather than one step.

Before treating a viewer or ExtendedAE Plus update as release-ready, complete the manual checklist for both client launch paths and retain the versioned logs/screenshots with the release evidence.

## Implementation checkpoint

Implemented and locally verified with `test build` and the four declaration combinations:

- Batch root-bound updates replace repeated workspace validation/copying during Ctrl-drag.
- Outside-rail and rightmost-panel placement are extracted into `DockChromeLayout`, preserving geometry.
- `mest.debugDockTiming=true` is an opt-in game JVM property; it logs cumulative average projection time every 120 projections at DEBUG level. This is not p95 or whole-frame profiling.
- Encoding amount arithmetic has focused overflow, division and GCD tests.
- Remote menu link checks revoke permanently within a session, handle clock rollback, and clean up on logout. Required Mixin injection remains enabled.
- The declaration check participates in `check`, supports configuration caching, and runs in a CI matrix. It does not resolve transitive dependencies or verify actual JVM class loading.

Not implemented in this checkpoint:

- Drag preview/commit separation and full persistence/interaction extraction from `DockManager`.
- General `MESTMenu` and `MESTScreen` controller decomposition.
- Replacement of pairwise rightmost geometry checks with a proven equivalent faster algorithm.
- Automated Minecraft client/server startup and Mixin application tests.
- Remote menu authorization tied to the original grid. The existing generic validity override can still mask a non-distance validity failure; this needs explicit machine-specific policy, not weaker injection requirements.

For the next implementation batch, first characterize press/drag/release, Escape, resize, screen removal,
detach failure and focus-raise transactions with tests. Only then separate preview state from persistence.
Preserve canonical versus viewport-clamped geometry, 32-entry gesture undo, slot IDs, GuiSync IDs,
action names and fail-fast Mixin requirements throughout extraction.
