# Travis

**Remember the way out. Find the way back.** Travis records an outing, saves photo landmarks and notes, retrieves those memories with local Gemma, and guides you toward them with synchronized map and camera views.

This repository contains one **native Android application** and a polished **interactive browser Explore demo** for iPhone/Android-sized screens. The native app now includes CameraX, GPS recording, persistent outings, offline raster maps, ordered retrace, approximate camera guidance and local reminders. Native iOS is not implemented.

## Start UI work on another laptop

```sh
git clone https://github.com/sdmad737/backtrail.git travis
cd travis
git switch -c codex/ui
```

Open the checkout in Codex and ask it to read and execute [prompts/01-ui.txt](prompts/01-ui.txt).
[UI quick start](docs/UI-START.md) covers the immediate browser preview, fresh Android
setup, editable files and handoff checks. Gemma weights are not needed for UI work.

## Experience the product

```sh
# From the repository root:
python3 -m http.server 4173 --directory explore --bind 127.0.0.1
```

Open [Travis Explore](http://127.0.0.1:4173). No package install or internet connection is needed. The browser explicitly labels replayed movement, still-camera previews and sample answers. Try Start → Save → Ask → Find in AR → Resume retrace. [Explore instructions and scenario controls](explore/README.md).

## Build the Android app

On this prepared Mac:

```sh
source .tooling/env.sh
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb devices -l
# Replace PIXEL_SERIAL with the physical device's serial.
adb -s PIXEL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s PIXEL_SERIAL shell am start -n com.backtrail.app/.MainActivity
```

Use **Prepare** to grant permissions and import the verified model. Use **Explore demo** for isolated, labelled replay; exit it before a real walk. [Exact model transfer and physical-phone acceptance steps](docs/SETUP.md).

## What is proven

- Gemma 4 E2B is fully downloaded, size/SHA-256 verified, and loaded from a local Mac path. Actual text, image description and bounded-memory retrieval passed on the Mac GPU with network access denied.
- The Android app builds offline. Unit tests and lint pass. It launches in an API 36 ARM64 emulator; simulated GPS goes through Android's location service and persists. CameraX produced a saved original JPEG. Process relaunch preserved the outing/photo and correctly paused recording.
- Native Android and browser layouts were inspected, including keyboard and larger-text/error states. iPhone SE Simulator Safari was used to fix and retest a real software-keyboard issue.
- **Physical-phone validation is pending.** A Pixel is now connected, but model installation, Pixel inference, sensor alignment, screen-locked GPS, battery cost and the full physical offline loop remain unverified. Emulator inputs are not field measurements.

Read the [preparation handoff](docs/PREPARATION-STATUS.md) for actual results, limitations, next three actions and demo plan. [Model evidence](docs/MODEL-READINESS.md) and [organizer rules](docs/EVENT-RULES-CHECK.md) distinguish requirements from suggestions. No fine-tuning or extra embedding model is required by this implementation.

## Project boundaries

Android is Kotlin/Compose, CameraX 1.4.2 and LiteRT-LM 0.18.0. App ID remains `com.backtrail.app` to preserve the starter. The runtime has no Internet permission. Weights, SDKs, recordings and generated APKs are excluded from Git. The prepared map pack covers a small Hillsborough region, not the world. Outside coverage, recorded traces remain visible.

AR is a core screen: real camera + location + rear-camera orientation, approximate bearing, straight-line distance and the original saved photo. It has no ground anchors or automatic visual localization. Gemma proposes saved IDs and descriptions; the user recognizes/ confirms a landmark and controls route progress. GPS recording remains independent of inference.

Read [AGENTS.md](AGENTS.md), [PRODUCT](docs/PRODUCT.txt), [CONTRACTS](docs/CONTRACTS.txt) and [DECISIONS](docs/DECISIONS.txt). Earlier bootstrap documents are preserved under `docs/history/`; latest user requirements are copied in [TRAVIS-BUILD-BRIEF](docs/TRAVIS-BUILD-BRIEF.txt). Private GitHub repository: [sdmad737/backtrail](https://github.com/sdmad737/backtrail). Use `main` as the shared starting point and separate branches for each teammate.
