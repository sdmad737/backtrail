# Preparation for Saturday, October 24

Checked October 23, 2026 Pacific against the full organizer resource hub, technical primer, event page, linked LiteRT Developer Handbook and Android sample guide. The app's current name remains Backtrail; Travis was discussed but has not been applied to the app or repository.

## What is prepared on this Mac

- One Kotlin/Compose Android app, commit `6c6dbaa`, published to the private [repository](https://github.com/sdmad737/backtrail).
- JDK 17, Android SDK 36, ADB, Gradle wrapper and resolved dependencies installed locally. `source .tooling/env.sh` puts the local Java and ADB on PATH on this Mac.
- A fresh forced rebuild with `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --rerun-tasks` succeeded: 8 tests passed; lint had 0 errors and 11 warnings. This checks development dependencies cached on the Mac, not phone execution.
- A copy of the debug APK is in ignored `local-data/event-kit/backtrail-bootstrap-6c6dbaa.apk`.
- Native Gemma 4 E2B downloaded to ignored `models/gemma-4-E2B-it.litertlm`: **2,588,147,712 bytes**, SHA-256 matched the pinned publisher checksum on October 23 at 10:39 p.m. Pacific. Local verification receipt: `models/download-receipt.json`. It has not been transferred to or executed on a phone.

The main remaining risk is **phone feasibility**, not repository setup. The app currently imports a model and photo and requests a local description. Camera capture, saved trips, comparison, navigation and checkpoint confirmation/undo are still unimplemented. No emulator/phone launch or inference has been measured.

The APK copy's SHA-256 is `1ceb9c4c659349c94d37115648dfa81126f32ad42c16ffa6b2e1345958f59328`. It contains app code from `6c6dbaa`; preparation-document commits do not add app functionality.

## The model to take

Use **gemma-4-E2B-it.litertlm** for the current app. The organizer-linked [LiteRT Handbook](https://docs.google.com/document/d/1h7ntx1p6p4zqu8HU3wuVlSBXmjGXY3ideg5GE64jfNo/edit) lists this file for CPU/GPU, and the [Android guide](https://github.com/google-ai-edge/litert-samples/blob/main/samples/platforms/android/README.md) recommends it as the general phone model. It is a public download. We use the exact publisher revision already pinned in the app; details and checksum are in [MODEL-RESEARCH](MODEL-RESEARCH.md).

The file belongs on the laptop tonight and on the Pixel before the offline test. Model weights remain excluded from Git. Our app streams a copy into private storage and verifies it; the Flutter sample's file-picker limitation does not apply to this Kotlin streaming importer.

There is no need to install Ollama, Flutter, Xcode, Python ML frameworks, or download E4B/12B/26B/31B for this starter. EmbeddingGemma is optional future retrieval work, absent from the current app. Tensor G6 NPU acceleration is a separate experiment needing a matching artifact/runtime; the downloaded native file is for the existing GPU path.

Plan for **at least 8 GB free storage** on the phone as a working allowance: the source and app copy alone total about 5.18 GB, before caches and photos. This is our planning margin, not a measured peak. Google's Gallery configuration recommends an 8 GB RAM class device for this model; confirm the actual phone. RAM and storage are different requirements.

## What you need to do tonight

1. Confirm your event registration is approved; the [event page](https://www.agihouse.org/events/the-google-gemma-hardware-hackathon) requires host approval. Your personal approval status was not verified here.
2. Confirm the three-to-four-person team and assign UI, inference, and device/integration ownership. Make sure teammates can access the private repository and have their own checkout and build tools. No collaborators have been invited by this chat.
3. Pack the Mac and charger, a **USB-C data cable**, your phone, and preferably a power bank. A charge-only cable will not support ADB.
4. If you already have the Pixel, connect it and perform the smoke test below tonight. If borrowing, plan to arrive at doors opening and request a Pixel immediately.
5. Prepare a short demo outline and a few real landmark/photo examples. A prepared photo or route must be labelled as recorded earlier. Do not prepare fake model answers.

The [resource hub](https://www.agihouse.org/blog/google-gemma-hardware-builder-resource-hub) explicitly asks builders to predownload models because Wi-Fi is shared. It lists Pixel 11 Pro loaners, allocated first come, first served and returned that evening. The Flutter demo is optional. Our existing native app can qualify without changing frameworks. Jetson flash drives and Pi/Coral setup are outside our selected phone project.

## First phone session: integration owner only

On the Pixel, enable Developer options (tap Build number seven times), enable USB debugging, connect the data cable, and approve the computer's debugging prompt. This requires you to interact with the phone. Then, from this repository on the prepared Mac:

```sh
source .tooling/env.sh
adb devices -l
```

Use the exact listed device serial, especially if other teams' devices are connected. Record its model and OS. Coordinate the installation slot so another teammate does not overwrite the shared app.

```sh
adb -s SERIAL shell getprop ro.product.model
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell df -h /sdcard
adb -s SERIAL install -r local-data/event-kit/backtrail-bootstrap-6c6dbaa.apk
adb -s SERIAL push models/gemma-4-E2B-it.litertlm /sdcard/Download/gemma-4-E2B-it.litertlm
adb -s SERIAL shell am start -n com.backtrail.app/.MainActivity
```

In the app: import the model from Downloads; wait for checksum verification; select a local photo. Turn on airplane mode and explicitly turn Wi-Fi off. Request a description. Test a different photo, an unclear photo, cancellation, and an app restart. Confirm that loading/failure is visible and no result is presented unless the real runtime produced and validated it. Record observed results in `DECISIONS.txt`.

If model loading fails, first inspect the actual phone/runtime error with an AI Edge engineer. The official [AI Edge Gallery](https://github.com/google-ai-edge/gallery) is a useful independent hardware check; installing or running it has not been done here. A successful Gallery test is not proof that our own app works.

## Constraints for the build and demo

- Gemma-based inference runs entirely on the phone; no cloud dependency in the runtime loop. Pre-event asset downloads and development tooling are separate from demo execution. No telemetry, online map fetches, hidden laptop inference, or silent remote fallback in our app.
- Enter **Mobile AI**, one main track. Eligible special awards can stack; prize decisions belong to the judges. [Organizer rules](https://www.agihouse.org/blog/google-gemma-hardware-builder-resource-hub#tracks-judging-and-prizes).
- Keep one app and the canonical interfaces. Model proposals are validated for shape, allowed IDs and fresh request/session/revision context. Explicit user controls own progress; invalid or stale output changes nothing.
- Do not claim automatic localization, safety assessment, or dependable wilderness navigation. Unknown and failed states are part of the demonstration. These are our project's [product constraints](PRODUCT.txt), not additional organizer judging criteria.

## The day's schedule

Published times below are local Pacific for October 24. Source: [event page](https://www.agihouse.org/events/the-google-gemma-hardware-hackathon).

| Time | Published event milestone |
| --- | --- |
| 10 a.m. | Doors open |
| 11 a.m. | Keynote starts |
| Noon | Project pitches and team formation |
| 12:15 p.m. | Lunch and building |
| 4 p.m. | Project check-ins |
| 6 p.m. | Dinner |
| **7 p.m.** | **Save project draft; finalist selection begins from saved drafts** |
| 8 p.m. | Demos, judging and awards |

The hub gives the venue as AGI House, 1868 Floribunda Ave, Hillsborough, CA 94010. Confirm submission fields, any later final cutoff, demo duration, and repo/video requirements at check-in. None of those details should be invented. The public page describes workbench judging and six finalists on stage.

## What the primer means for our scope

The full [technical primer](https://www.agihouse.org/blog/google-gemma-primer#build-day-project-ideas) includes camera assistance and local multimodal recall ideas. It emphasizes hardware/runtime constraints, event-driven perception, validation, sustained performance, and measurements with device/model/runtime/protocol attached. It is inspiration, not a formal scoring rubric.

Our interpretation: demonstrate one short return task with real photos and user-controlled confirmation. First prove real single-image inference on the Pixel; next connect capture, persistence, comparison and confirmation. Then add GNSS and a route plot if time permits. Avoid adding voice, embeddings, robots, multiple models or NPU work before that loop works. Measure actual end-to-end behavior; do not repeat vendor benchmarks as our results.
