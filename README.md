# Backtrail

Backtrail remembers the route, landmarks and things you meant to do on the way back, entirely on your phone.

This repository contains the team handoff and one native Android starter. The current screen is explicitly a **local image inference smoke test**, not a navigation demo. It imports a checksum-verified Gemma model and a local photo, then requests a structured description through LiteRT-LM. No model weights are included. The app has no Internet permission and no canned inference results.

Camera capture, saved trips, GNSS recording, two-image comparison, destination resolution, checkpoint confirmation and undo are not implemented yet. The shared interfaces and proposal/freshness checks are in place for those workstreams. A built APK does not establish Pixel compatibility or inference quality; see the observed results in [DECISIONS](docs/DECISIONS.txt).

## Build and first phone test

Follow [SETUP](docs/SETUP.md) for JDK/SDK setup, the exact model artifact, and the first offline phone test. From the repository root with JDK 17 and Android SDK configured:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The Android project is this repository root; its only application module is `app`. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk` and is excluded from Git.

## Team ownership

| Area | Path under `app/src/main/java/com/backtrail/app/` | Owner |
| --- | --- | --- |
| Presentation | `ui/` | UI / `codex/ui` |
| Local model, import, validation | `ai/` | Inference / `codex/inference` |
| Data, device state, request freshness | `trip/` | Device / `codex/device` |
| Canonical APIs and composition | `contracts/`, `MainActivity.kt`, `BacktrailViewModel.kt` | Integration owner |

The integration owner also owns Gradle files, manifest, shared resources, and product/decision documents. Branch from the same committed baseline in separate clones or worktrees. Verify the first real photo → inference → validated display on the Pixel before expanding the app. Do not treat the bootstrap as a device-validated integration baseline.

Read [AGENTS.md](AGENTS.md), [PRODUCT](docs/PRODUCT.txt), [CONTRACTS](docs/CONTRACTS.txt), and [WORKFLOW](docs/WORKFLOW.txt). The original role prompts are in [prompts](prompts/).
