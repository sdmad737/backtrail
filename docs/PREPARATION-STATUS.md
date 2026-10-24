# Travis — preparation handoff

Prepared on the Mac before the October 24, 2026 event. This is a substantial hackathon starting point, **not a finished or field-validated navigation product**. The complete latest brief was read and preserved in [TRAVIS-BUILD-BRIEF.txt](TRAVIS-BUILD-BRIEF.txt); AR is core. Existing Android work was preserved. This records the preparation-time evidence. For the subsequent team handoff, use `origin/main` and [UI quick start](UI-START.md). No event submission has been made.

## Open and run

- **Interactive Explore:** [running preview](http://127.0.0.1:4174). It is a local server on this Mac. To restart: `python3 -m http.server 4173 --directory explore --bind 127.0.0.1` from the repository root, then open port 4173. [Walkthrough](../explore/README.md).
- **Native Android:** `source .tooling/env.sh`, then `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`. [Pixel install/import instructions](SETUP.md).
- **Repository:** [sdmad737/backtrail](https://github.com/sdmad737/backtrail), private. Current local work is on `codex/bootstrap`. Weights and generated tools/APKs remain outside Git.

## Model readiness — three separate milestones

| Milestone | Result |
|---|---|
| Downloaded and verified on Mac | **Complete.** Full file, 2,588,147,712 bytes; full SHA-256 matches pinned publisher metadata. |
| Installed on a physical phone | **Pending. No physical phone connected.** |
| Proven offline on a physical phone | **Pending.** Requires cold launch and real text/image/navigation trials on the Pixel. |

Actual local model path:

```text
/Users/shahinmadantschi/Downloads/backtrail-team-handoff/models/gemma-4-E2B-it.litertlm
SHA-256: 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c
```

Gemma 4 E2B was loaded from that local path with LiteRT-LM 0.18.0. Text, structured description of a real NPS photograph, three memory-query paraphrases and a no-match case passed on the Mac GPU with network denied by the OS. These were actual inference calls. The final image call took 3.519 seconds on this Mac in that run; it is not a Pixel benchmark. Earlier malformed responses and CPU retrieval failures remain in the logs. No model quality percentage is claimed.

The unified model includes tokenizer, weights and vision encoder/adapter; unused audio components are also present. No separate projector, tokenizer or embedding download is needed. Runtime wheel/AAR sizes and authoritative checksums were verified too. [Exact manifest](model-assets.json), [test logs and caveats](MODEL-READINESS.md). No fine-tuning or distillation was performed or required for this implementation.

## What is live and what is replayed

| Surface | Implemented behavior | Evidence and limits |
|---|---|---|
| Native Android live mode | Android GPS foreground service, segmented route, atomic outing storage, original CameraX photos, notes, local model import/description/retrieval, approximate camera bearing, ordered retrace, explicit confirmation/undo, reminders | Built and launched in emulator. Camera/GPS APIs exercised with **simulated hardware inputs**. Physical Pixel behavior pending. |
| Native Android Explore | Sample outing/photos/answers, replay position/heading, Map/AR, Find/retrace, scenario controls | Explicit opt-in; separate from saved live outings. Not model or sensor proof. |
| Browser Explore | Polished responsive interaction, geometry, data adapters, saved-memory selection, Map/AR, replayed return/reminder | Entirely fixture-backed sensors and sample answers; still camera previews. No OS notifications, persistence, Gemma or native iOS implementation. |
| Host model tests | Real local model and actual image/text generation under network denial | Mac CPU/GPU only. No NPU or physical-phone evidence. |

Native AR is a camera/compass/location baseline: approximate direction, straight-line distance, saved original photo and quality gating. It has no world anchors, automatic visual localization or automatic recognition. The user compares and confirms the landmark. Changing Find target preserves retrace progress. The next waypoint follows recorded order; gaps are left disconnected.

Maps are real bundled USGS rasters: 121 tiles, zooms 12–16, 2,785,869 bytes, small Hillsborough venue-area coverage. No remote map style/font/glyph dependency. Outside coverage the app labels the missing map and retains the trace. All map/photo byte counts and full local hashes match manifests and packaged APK bytes. USGS/NPS do not publish independent per-image hashes; those receipts establish local integrity, not an independent publisher signature.

## Verification actually completed

- Offline Android assemble, unit tests and lint: **success; 17 tests; 0 lint errors, 22 warnings**. Warnings are dependency/tool updates and compatibility/style suggestions, recorded in [DECISIONS](DECISIONS.txt). No suppressed errors.
- Final APK installed and launched in API 36 ARM64 Pixel 7 emulator. Manifest inspection confirms no Internet permission; LiteRT JNI/maps/photos are packaged.
- Emulator airplane mode on and Wi-Fi off: three injected GPS fixes persisted through the real location service, including a recording gap. CameraX saved a 60,097-byte original virtual-camera JPEG and note. With no fresh fix at capture, its location remained null. Process relaunch preserved the outing/photo and correctly showed recording paused.
- Native Map/AR, capture keyboard/scroll, 130% font/dark appearance, missing-map and weak-GPS states inspected. Weak GPS suppresses active AR direction and confirmation. [Native screenshots](qa/).
- Browser 390×844, 360×800, 320×568 and 1280×900 renders inspected, including loading, model error/no-match, larger text and uncertainty. Pure state tests check preserved cursor, ordered arrival, stale-result/ID rejection and deduplicated reminders.
- Actual iPhone SE Simulator Safari Ready/AR and software keyboard inspected. A clipped heading/bottom gap was fixed and retested. [Before/after and full browser QA](../explore/QA.md).

No physical phone was available, so the full **capture → Gemma → retrieve → physical camera guidance → retrace** chain has not run end-to-end on a phone. The host and emulator tests establish separate components only. Native screen-locked recording, orientation calibration/rotation, battery/thermal behavior, phone memory pressure, background notification delivery and model cancellation under load remain device gates. The current inexact time reminder replaces the previous one and is not restored after reboot. Two-image model comparison, voice and native iOS are unimplemented. World-tracked AR is deferred until suitable hardware is present.

## Next three actions at the hackathon

1. **Secure the Pixel and prove inference first.** Bring the data cable/power bank. Install this APK, transfer/import the verified model, verify both transferred and private copies, and run new real photo/text requests after a cold launch with Wi-Fi/cellular off. Record exact device/backend/timings.
2. **Complete one short outdoor loop.** Three distinctive saved landmarks, one requested pickup, screen lock, Map→Find in AR→Resume retrace, true-heading/rotation checks, one actual notification and original-data relaunch. Fix only blockers revealed by this trial.
3. **Rehearse and package the honest demo.** Replace sample landmarks with the measured walk, record a short backup video, record what was prepared before the event, and confirm submission details at check-in. The published page says save a draft by 7 p.m.; judging starts 8 p.m. [Organizer evidence](EVENT-RULES-CHECK.md).

## A 90-second demo

**Map:** show offline status, measured accuracy and recorded orange route. **Save:** capture a memorable fork and note “collect my bottle here”; request the return reminder. **Ask:** use a paraphrase, then show Gemma's returned original source photo. **AR:** choose Find in AR, turn the camera and show the approximate cue plus straight-line distance; recognize the saved landmark yourself. **Retrace:** resume the unchanged route cursor, receive the requested local reminder on return, mark collected explicitly and confirm the final waypoint. Use Explore tonight to rehearse this story, clearly announcing replay/sample answers. Use the physical flow for a live capability claim only after its acceptance checks pass.
