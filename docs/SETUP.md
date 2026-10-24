# Launch Travis and prepare the Pixel

The Android project is the repository root; its only module is `app`. Reuse it. Native iOS is not implemented; the browser Explore demo provides the iPhone product preview.

## On this prepared Mac

```sh
cd /Users/shahinmadantschi/Downloads/backtrail-team-handoff
source .tooling/env.sh
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The cached toolchain is JDK 17.0.21, Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.4.21, Android SDK 36/build-tools 35.0.0, CameraX 1.4.2 and LiteRT-LM 0.18.0. The APK is `app/build/outputs/apk/debug/app-debug.apk`. ARM64 only, Android 9+; this minimum does not guarantee model compatibility or sufficient RAM.

On another Mac, install/configure JDK17 and Android SDK36, set `ANDROID_HOME` and run the wrapper online once. `.tooling/` and Gradle caches are local, excluded from Git. `local.properties` may contain your SDK path but must remain ignored.

For the interactive product preview:

```sh
python3 -m http.server 4173 --directory explore --bind 127.0.0.1
```

Open [localhost Explore](http://127.0.0.1:4173). The Android APK is independent of this server.

## Verified assets already present

The complete model is `models/gemma-4-E2B-it.litertlm`, **2,588,147,712 bytes**:

```text
SHA-256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
Publisher litert-community/gemma-4-E2B-it-litert-lm
Pinned revision 6e5c4f1e395deb959c494953478fa5cec4b8008f
```

The bundle includes tokenizer, language weights, vision encoder/adapter and unused audio components. There are no additional model companion downloads for the chosen implementation. The exact [manifest](model-assets.json), [publisher revision](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/6e5c4f1e395deb959c494953478fa5cec4b8008f), and [actual host tests](MODEL-READINESS.md) are recorded. No fine-tuning or EmbeddingGemma is required for the bounded catalog approach.

The APK bundles 121 USGS raster tiles for the venue area, zoom12–16, 2,785,869 bytes, plus local NPS example photos for explicit Explore mode. Outside the saved region, live mode shows a recorded trace without basemap coverage. Map/photo provenance and local byte/digest manifests are under `app/src/main/assets/`. There are no remote fonts, glyphs, styles or map tokens.

## First physical phone slot

Only the integration owner installs the shared demo app. No physical phone was connected during preparation. Obtain a Pixel, USB data cable and power bank; enable USB debugging and authorize this Mac. Record model, OS, RAM and storage. Leave at least8GB free for the transfer copy, verified app-private copy and runtime cache. The host smoke test does not predict Pixel timing or thermal behavior.

```sh
source .tooling/env.sh
adb devices -l
# Set this to the physical Pixel serial shown above, not emulator-5554.
TRAVIS_SERIAL=REPLACE_WITH_PIXEL_SERIAL
adb -s "$TRAVIS_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$TRAVIS_SERIAL" push models/gemma-4-E2B-it.litertlm /sdcard/Download/gemma-4-E2B-it.litertlm
adb -s "$TRAVIS_SERIAL" shell wc -c /sdcard/Download/gemma-4-E2B-it.litertlm
adb -s "$TRAVIS_SERIAL" shell sha256sum /sdcard/Download/gemma-4-E2B-it.litertlm
adb -s "$TRAVIS_SERIAL" shell am start -n com.backtrail.app/.MainActivity
```

Both transferred values must match above. In Travis: **Prepare → Import local Gemma model**, select that file from local Downloads, and wait for the verified success message. Import copies it into persistent `no_backup/models/` app storage and independently checks full bytes/SHA-256 before rename. App uninstall clears that private copy. The source remains in Downloads.

For this debuggable APK, verify the installed private copy too:

```sh
adb -s "$TRAVIS_SERIAL" shell run-as com.backtrail.app wc -c no_backup/models/gemma-4-E2B-it.litertlm
adb -s "$TRAVIS_SERIAL" shell run-as com.backtrail.app sha256sum no_backup/models/gemma-4-E2B-it.litertlm
```

This establishes **installed-on-phone**, not offline execution. Do not skip the next gate.

## Physical offline acceptance

1. Grant precise location, camera and notifications; leave Explore. Enable airplane mode, explicitly switch Wi-Fi off, and cold-launch Travis. Keep Android Location enabled. No runtime Internet permission is present.
2. Outdoors with a fresh GPS fix, start outing, walk a short safe route, pause/resume once, and verify a segment gap rather than a fabricated line. Lock the screen briefly and verify new samples after unlock. Record observations; battery/thermal costs are still unmeasured.
3. Save three real landmarks with distinctive views and notes. Check that originals persist after process relaunch. Missing location must remain unknown. Gemma must actually describe a new image locally; note exact elapsed times and failures.
4. Ask three paraphrases and one unrelated query. Verify returned source IDs/photos, no-match/invalid/cancel states, and that recording continues during inference. The catalog is capped at the latest12 memories, displayed explicitly when relevant.
5. Head back, select another saved place, switch Map/AR, and resume retrace. Check unchanged cursor. Turn through north, portrait/landscape and phone pitch; inspect approximate bearing against the real landmark. Poor location or heading must suppress cues. Do not claim world anchoring or vision localization.
6. Return to the saved location, compare the original photo, explicitly confirm, and continue in reverse order. Test one requested pickup: leave it beyond the arming radius, return with repeated good fixes, receive one local notification, then explicitly mark collected. Close guidance/complete and verify GPS foreground service stops when the outing has ended.
7. Cold-relaunch offline again. Record device, APK commit/hash, model digest, backend, text/image outcomes and measured timings in `DECISIONS.txt`. Only then mark **proven-offline-on-phone** complete.

The runtime uses Android GPU for text and vision with no silent CPU/cloud fallback. It serializes requests, initializes/closes the engine per request and supports cancellation. Blocking native engine initialization/cleanup may outlast the coroutine timeout. GPU compatibility, memory pressure and camera contention require this Pixel trial. Two-image automated recognition and world-tracked AR are unavailable; human source-photo recognition is the baseline.

## Optional local emulator

The prepared API36 ARM64 Pixel7 AVD exists in `.tooling/avd`. This is layout/device-API evidence only:

```sh
source .tooling/env.sh
ANDROID_AVD_HOME="$PWD/.tooling/avd" "$ANDROID_HOME/emulator/emulator" -avd travis-pixel -gpu swiftshader
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.backtrail.app/.MainActivity
```

Model inference was not tested in the emulator. GPS injections and its virtual camera are simulated hardware inputs, not physical sensor measurements.

## API references

- [LiteRT-LM 0.18.0 Kotlin guide](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md)
- [LiteRT-LM configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Config.kt)
- [CameraX](https://developer.android.com/media/camera/camerax)
- [Location foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
