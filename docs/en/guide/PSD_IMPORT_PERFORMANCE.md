# PSD import responsiveness

Follow-up to PR #47. That PR fixes queued mesh-split requests during start presets; its reported ~220 ms stall on one 2520² PSD is an observation, not a large-file performance guarantee.

## First change: prepare privately, publish once

The desktop path is `analyze` → `startWorkspaceImport` → `WorkspaceHost.importPsd` → `WorkspaceSourceImporter`.

| Phase | Execution / change |
| --- | --- |
| Capture an owned PSD; read and decode | Existing IO worker paths; now timed separately. |
| Build linked materials; hash source rasters | Moved to a single Default worker operation. Original pixel arrays, hash algorithm, material order and links are preserved. Cancellation is checked between entries. |
| Build preview | Existing background preview builder; the import also records its wall time. |
| Compute revision; validate and prepare initial history | Moved to a Default worker. The validated installation candidate has no live-runtime side effects. |
| Project UI and publish runtime | Remains synchronous on the caller's thread, after preparation. Rechecks the expected runtime state and unsaved-change protection. Cancellation is checked before this step. |
| Notify renderer | Remains on the original path; timed separately. Notification duration does not measure asynchronous GPU upload or first-frame latency. |

There is no partial model publication or per-layer parallelism. The existing start-preset and mesh-split queue rules are unchanged. Other synchronous runtime installation callers keep their existing behavior.

## Reproduce measurements

JDK 21 and the repository's Gradle dependencies are required. From the repository root:

```sh
./gradlew test --tests '*WorkspaceSourceImporterTest' --tests '*WorkspaceInstallationTest' --tests '*WorkspaceRuntimeConcurrencyTest' --tests '*MeshSplitMenuScenario' -Ppsd2live.testForks=1

PSD2LIVE_IMPORT_PROFILE=1 PSD2LIVE_SPLIT_SCALE=6 ./gradlew test --tests '*MeshSplitMenuScenario' --rerun-tasks --info -Ppsd2live.testForks=1
```

Scale 6 makes the synthetic 420² fixture 2520². Environment variables reach the test JVM; the existing `psd2live.scenario.splitScale` system property is also recognized when set in that JVM. `PSD2LIVE_IMPORT_PROFILE=1` enables the existing opt-in `RigBuildProfile` recorder for this scenario. It prints stage sums, calls and executing thread names. `raster: SHA-256` measures cache misses, including hashes outside the import; stage totals are nested and must not be added together. The preview-build wall-time stage spans suspensions and does not claim to execute on one thread.

The scenario reports the maximum interval between Main-thread ticks scheduled every 10 ms. It now waits for the ticker to start before beginning the measured action. This is a scheduling-gap observation, not precise exclusive UI CPU time, and has no machine-dependent pass/fail limit. Its import interval may include overlapping start-preset work; use the phase timings to separate that work.

For real PSDs, profile the same desktop import path with JFR or a sampling profiler and record:

- Build SHA, OS, JDK, CPU, heap, file bytes, canvas dimensions, layer count and total raster bytes.
- Separate import and preset timings, maximum UI scheduling gap, first usable preview, total time and peak memory/GC.
- One cold-process run and repeated warm runs, reporting median and tail behavior rather than one sample.
- A 2520² baseline, larger production PSDs, and files with similar canvas sizes but different layer counts/raster totals.

No before/after speedup or absolute responsiveness limit has been established by this change alone.

## Coverage and remaining work

| Coverage | Status |
| --- | --- |
| Start-page import → scan → split → scan again | Existing `MeshSplitMenuScenario` from #47. |
| Material/revision source traversal is off caller; projection returns to caller; original raster sharing and canonical revision agree | Added `WorkspaceSourceImporterTest`. Injected reader/builder isolates orchestration, not PSD-decoder correctness. |
| Cancel during revision preparation publishes nothing | Added deterministic gate-based importer test. |
| Concurrent edit rejects prepared installation; preparation leaves runtime lock available | Added `WorkspaceInstallationTest`. |
| Dirty-state guard, projection failure, mismatched-history rejection | Added prepared-install tests. |
| Large real PSD performance, renderer/first-frame latency, memory regression | Still requires measurements on representative hardware. |

Tests added here must be run in a dependency-equipped environment; do not interpret their presence as passing results.

Next steps after measurements:

1. If synchronous projection remains dominant, reduce repeated state derivation and throttle progress updates; preserve one consistent model/document publication.
2. If renderer installation is dominant, verify SDK/GL thread ownership, move only CPU preparation to workers, and consider bounded upload batches in a private replacement session before an atomic switch. Add cancellation/failure resource cleanup tests before enabling batching.
3. Extend desktop-level regression coverage for cancel/reimport, project close/switch, failed import and deterministically gated preset/split overlap. The initial runtime state token already rejects stale prepared imports; these UI sequences still need explicit coverage.
