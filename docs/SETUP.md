# Android setup and first Pixel test

The one Android project lives at the repository root and uses the checked-in `gradlew` / `gradlew.bat`. Use JDK 17, Android SDK platform 36 and build-tools 35.0.0. Set `ANDROID_HOME` or create an ignored `local.properties` containing your own `sdk.dir`. The build files pin Gradle 8.13, AGP 8.13.2, Kotlin 2.4.21 and LiteRT-LM 0.18.0; other dependencies are pinned in `gradle/libs.versions.toml`.

The starter targets an ARM64 phone running Android 9 or later. The exact Pixel model, Android version, GPU compatibility, RAM and model performance still need to be recorded. No emulator or phone execution is implied by a successful build.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

On the original bootstrap Mac only, a local JDK/SDK/cache were provisioned in ignored `.tooling/`. To use that local installation from this folder:

```sh
source .tooling/env.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Other teammates should use their own JDK/SDK installation. Do not commit `.tooling/`, `local.properties`, weights or build outputs.

## Prepare the model before the offline demo

Download the **native** `gemma-4-E2B-it.litertlm` from the [publisher's pinned revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/1caa527c1e88b55f046fcd8d2b4265606680663d). Review the publisher's license/access requirements. Do not choose the `-gpu`, `-web`, Tensor, Qualcomm or other variants for this baseline.

Publisher file metadata, retrieved October 23, 2026 Pacific:

```text
Repository: litert-community/gemma-4-E2B-it-litert-lm
Revision:   1caa527c1e88b55f046fcd8d2b4265606680663d
File:       gemma-4-E2B-it.litertlm
Bytes:      2588147712
SHA-256:    181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

These are publisher metadata, not a claim that the weights have been downloaded or executed here. `ModelStore` checks both size and SHA-256 when importing. Obtain the file before arriving, transfer it separately to the Pixel, and leave enough free space for the source plus the app-private copy and runtime cache. Model import never happens over the app's network.

## Run on the phone

Only the integration owner installs the shared demo application ID. Coordinate the phone slot first. Enable USB debugging on the Pixel and approve the computer's debugging prompt. With the phone attached:

```sh
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.backtrail.app/.MainActivity
```

1. Open Backtrail and import the exact model from local phone storage. Wait for checksum verification.
2. Select a real photo already on the phone. The starter normalizes orientation and copies it as a JPEG, capped at 1536 pixels on the longest side. The original capture time is unknown. This is photo selection, not live camera capture.
3. Disable Wi-Fi and cellular, then select **Describe on this phone**. Check the selected image and bounded model description, or the visible failure. A description is a suggestion, not location verification.
4. Try a different scene and an unclear image. Try Cancel. Record outcomes and the exact APK commit in `DECISIONS.txt`; do not invent accuracy or latency.

The current adapter initializes and closes an engine per request and uses GPU for text and vision. There is no CPU/remote fallback. It serializes requests and explicitly cancels native generation before closing. The 120-second coroutine timeout cannot forcibly interrupt a blocking native initialization/cleanup call; cancellation behavior must be checked on the Pixel. Warm inference measurements are not available in this starter.

The screen retains state across activity recreation through a ViewModel; a process restart requires selecting the photo again. The verified imported model remains in app-private storage. Selected smoke-test photos are temporary cache files, not saved trips. File pickers request local-only files; test in airplane mode to confirm the chosen provider has the files locally.

## Next integration slice

Implement CameraX capture and saved checkpoints through the canonical ports, then real two-image comparison, explicit confirmation and undo. `compare` and `resolveDestination` currently return `UNAVAILABLE`. `TripRepository`, `TrackRecorder`, and `ReturnController` are contracts, not implementations. Do not start maps or AR before the first integrated photo loop works on the phone.

## Primary API references

- [LiteRT-LM 0.18.0 Kotlin guide](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md)
- [LiteRT-LM 0.18.0 configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
- [LiteRT-LM 0.18.0 conversation lifecycle](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt)
- [Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Android command-line tools](https://developer.android.com/studio#command-tools)
