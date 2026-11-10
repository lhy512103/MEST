# Development runtime profiles

These profiles select development-launch dependencies, not what is compiled or packaged into MEST. The current configuration targets Java 21, Minecraft 1.21.1 and NeoForge 21.1.265. Versions remain in `gradle.properties`; the profile does not modify that file.

## Selection and defaults

| Property | `full` (default) | `minimal` |
| --- | --- | --- |
| `mestRuntimeProfile` | Existing compatibility pack | AE2 + GuideME + AE2WTLib and their transitive dependencies |
| `mestRunJei` | `true` | `false` |
| `mestRunEmi` | `false` | `false` |
| `mestRunMui` | `true` | `false`; requesting `true` is rejected |

Use `-PmestRuntimeProfile=minimal` or `-PmestRuntimeProfile=full`. Other profile names fail configuration. The JEI and EMI flags accept `true`/`false` and affect clients only. Minimal viewer opt-ins add the viewers, **not** AE2 JEI Integration, Just Enough Characters, optional compatibility mods, Modern UI, or input helpers. Full retains the existing dependencies, viewer addons and UI switches. It is a full development compatibility pack, not a claim that every combination has been verified in-game.

`runClientEmi` remains an explicit EMI-on shortcut: it always adds EMI, even if `mestRunEmi=false`; it otherwise follows the selected profile and JEI flag. Use `runClient` to test the exact flag combination, especially no-viewer launches. No extra source sets were introduced.

Optional integration APIs remain `compileOnly` so all integration code can compile in either profile. Client launch classpaths contain runtime configurations and outputs only, never `main.compileClasspath`. Unit tests still intentionally use the main compile classpath; a passing unit test is not evidence of optional-mod absence during a game launch. Minimal builds still download compile-time integration APIs; this is a minimal **game runtime**, not a minimal/offline compilation dependency graph.

## Save isolation

| Launch | Full game directory (unchanged) | Minimal game directory |
| --- | --- | --- |
| `runClient` / `runClientEmi` | `run/client` | `run/minimal/client` |
| `runServer` | `run/server` | `run/minimal/server` |

The profiles do not copy, remove or migrate existing saves. Never copy a full-pack world into minimal for a smoke test: missing mods can remove saved content. Use a new disposable world, and do not manually put optional mod jars into the minimal game directory's `mods` folder. Viewers within a profile share the client directory; stop the previous client before starting another. Server EULA acceptance, if required, is manual in the corresponding directory. Data generation and GameTest tasks are not the client/server smoke tests described here.

## Verification commands

From the repository root in PowerShell:

```powershell
# Current full default; old checkViewerLaunchClasspaths is an alias.
.\gradlew.bat checkRuntimeProfiles build --no-daemon --stacktrace

# Minimal no-viewer default: no optional mod/UI declarations.
.\gradlew.bat checkRuntimeProfiles build -PmestRuntimeProfile=minimal --no-daemon --stacktrace

# All eight explicit profile/viewer combinations, matching the CI intent.
foreach ($profile in @('full', 'minimal')) {
    foreach ($jei in @('false', 'true')) {
        foreach ($emi in @('false', 'true')) {
            .\gradlew.bat checkRuntimeProfiles build "-PmestRuntimeProfile=$profile" "-PmestRunJei=$jei" "-PmestRunEmi=$emi" --no-daemon --stacktrace
            if ($LASTEXITCODE -ne 0) { throw "Failed: $profile JEI=$jei EMI=$emi" }
        }
    }
}

# Separate interactive smoke tests; these are not performed by CI.
.\gradlew.bat runClient -PmestRuntimeProfile=minimal -PmestRunJei=false -PmestRunEmi=false
.\gradlew.bat runServer -PmestRuntimeProfile=minimal
.\gradlew.bat runClient -PmestRuntimeProfile=minimal -PmestRunJei=true -PmestRunEmi=false
.\gradlew.bat runClient -PmestRuntimeProfile=minimal -PmestRunJei=false -PmestRunEmi=true
.\gradlew.bat runClient -PmestRuntimeProfile=full -PmestRunJei=true -PmestRunEmi=true

# Inspect the resolved shared runtime and selected client viewer dependencies.
.\gradlew.bat dependencies --configuration runtimeClasspath -PmestRuntimeProfile=minimal
.\gradlew.bat dependencies --configuration clientLocalRuntime -PmestRuntimeProfile=minimal
```

On Linux/macOS replace `.\gradlew.bat` with `./gradlew`. Resolve dependency reports separately from the fast declaration checks; normal required transitive libraries are not disabled with `transitive=false`.

## Verification boundaries

`checkRuntimeProfiles` runs as part of `check`/`build`. It asserts the three exact minimal shared-runtime declarations, excludes compile-only configuration inheritance, checks that minimal runtime configurations do not declare optional compile-only APIs, checks full compatibility targets, and compares exact client-local dependency sets against JEI/EMI/UI selections (including the explicit EMI shortcut). It snapshots declarations as task inputs without resolving artifacts.

CI runs both this task and **build with the same matrix flags**: four full viewer combinations and four minimal combinations, with the minimal-none row also checking the no-viewer default. Artifact names distinguish all rows.

**Declaration checks and compilation are not a game startup test.** They cannot prove the resolved transitive graph, loader optional-class safety, mod metadata compatibility, mixin behavior, GUI rendering, world loading or dedicated-server startup. Inspect resolved dependencies and the loader mod list, then launch isolated disposable worlds to verify those properties. Full keeps its existing third-party dependencies and may expose pre-existing upstream incompatibilities. Record the actual versions, commands and observed results in the release checklist rather than treating these instructions as a compatibility guarantee.
