# Backend handoff — October 24, 2026

Start from `origin/main`. This combines inference/evaluation work (`423dbfc`),
native assistant/UI integration (`a4eeda3`), and device handoff (`fe09daa`). The
runtime backend is local Kotlin/LiteRT-LM inside the Android app. There is no
hosted backend or configured production deployment pipeline.

## Start working

```sh
git clone https://github.com/sdmad737/backtrail.git travis
cd travis
git switch -c codex/backend-next
# Configure JAVA_HOME for JDK 17 and ANDROID_HOME for Android SDK 36.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Use the checked-in wrapper. A fresh machine needs dependency downloads. Model
weights, SDK paths, signing keys, recordings, and generated APKs are excluded
from Git. See [setup](SETUP.md) and [AI handoff](AI-HANDOFF.md) for model import
and API calls. Coordinate phone installs with the project lead: a different
debug key cannot replace the installed app, and uninstalling deletes private data.

## Current integration

Paths below are under `app/src/main/java/com/backtrail/app/`:

- `ai/`: local inference, validation, model selection, safety refusals, and
  deterministic `RightWayCheck`.
- `TravisViewModel.kt`: screen actions, request/session/revision gates, and
  explicit confirmation of proposals.
- `contracts/`: shared interfaces and state.
- `trip/` and `device/`: persistence, route progress, GPS/sensors, and reminders.

The repository's `evals/` contains the Mac inference harness and recorded results.
Native screens expose trip/photo questions, expected-retrace-spot comparison,
reminder proposals, description editing/retry, and direction checks. Trip questions
use four recent places without semantic ranking. Voice capture and the uncalibrated
alignment meter remain disabled. AI results do not advance the route.

## Combined-source validation

Run on this Mac with JDK 17 and Android SDK 36:

```sh
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
node explore/model.test.mjs
node --check explore/app.js
git diff --check
```

All passed: **80 JVM tests, zero failures/errors/skips; lint zero errors and 14
warnings**. App and instrumentation APKs compiled. APK inspection confirmed no
`INTERNET` permission. Instrumentation was not executed. This handoff did not
install or run the combined build on an emulator or phone. Prior Pixel benchmarks
are in [INFERENCE-LOG.md](INFERENCE-LOG.md); the previous integration install/launch
is in [DECISIONS.txt](DECISIONS.txt). Neither establishes full physical outdoor
acceptance for this combined build.

## Remaining backend work

1. Check cached-engine lifecycle: initial model/backend selection persists after
   a later import (relaunch after importing another model), and native close can
   block its caller. See the earlier [integration review](INTEGRATION-READY.txt).
2. Exercise integrated UI flows offline on the Pixel, including cancellation,
   late results, confirmation/undo, and recording during inference.
3. Validate direction checks and the full record/retrace/reminder loop outdoors.
   Benchmarks and simulated GPS tests do not measure field reliability.
4. Preserve model limitations: comparison missed real matches in the small test
   set; the Mac trip evaluation includes a confidently wrong cited answer. Keep
   source photos visible and user confirmation explicit.

The older `claude/ui-comparison` component is excluded: it is disconnected and
shows reasons for non-matches, contrary to current AI guidance. The current
retrace comparison UI is included. Local agent configuration, old product notes,
design exports, and videos are outside this source handoff.

Follow [AGENTS.md](../AGENTS.md); coordinate shared contracts/build changes with
the project lead. No shared interface change is required to begin work.
