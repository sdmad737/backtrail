# Travis AI infrastructure, toolchain and commands

Written 2026-10-24 from `main` (`74f5b9e`). Everything here was run on the devices named;
numbers come from `docs/INFERENCE-LOG.md`, `evals/RESULTS.md` and the receipts under `docs/`.

---

## 1. The stack

| Layer | What | Version / identity |
|---|---|---|
| Phone | Google Pixel 11 Pro (`grizzly`), Tensor G6, 12 GB RAM | Android 17, build `CD1A.260618.001.C2` |
| App | Native Android, Kotlin + Jetpack Compose, CameraX | AGP 8.13.2, Kotlin 2.4.21, compileSdk 36, minSdk 28, arm64-v8a only |
| Inference runtime | LiteRT-LM (Google AI Edge), Android AAR `com.google.ai.edge.litertlm:litertlm-android` | **0.18.0** |
| NPU driver | `com.google.ai.edge.litert:litert-npu-runtime-google-tensor` (ships `libLiteRtDispatch_GoogleTensor.so`, `libLiteRtCompilerPlugin_google_tensor.so` and the manifest `uses-native-library` entries for `libedgetpu_*`) | **2.3.0** (published 2026-10-06; matches the LiteRT runtime inside litertlm 0.18.0) |
| Language + vision + audio model | Gemma 4 E2B instruction-tuned, LiteRT-LM bundle, Tensor G6 build | `gemma-4-E2B-it_Google_Tensor_G6.litertlm`, 3,313,938,293 B, SHA-256 `f86c7c19c736e9307267946edef58b0a45494a9127036d54d90dd6b7617c95b7` |
| Same model, generic build (GPU/CPU fallback) | `gemma-4-E2B-it.litertlm` | 2,588,147,712 B, SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| Embedding model | EmbeddingGemma 2 740M (text + image + audio → one 768-d space; we use 256-d) | `embeddinggemma-2-740m_Google_Tensor_G6.litertlm`, 615,713,460 B (NPU); `embeddinggemma-2-740m.litertlm`, 484,622,336 B (GPU fallback) |
| Model source | Hugging Face `litert-community/gemma-4-E2B-it-litert-lm`, `litert-community/embeddinggemma-2-740m-litert-lm` | downloaded with `hf` CLI 2.2.0, `HF_HUB_DISABLE_XET=1` |
| Where models live on the phone | app-private, never in Git, never downloaded at use | `/data/user/0/<package>/no_backup/models/` |
| Backends used | Gemma: `Backend.NPU(nativeLibraryDir)` for language + vision, `Backend.CPU()` for audio. EmbeddingGemma: `Backend.NPU`. GPU fallback when the G6 file is absent | `InferenceBackend.kt` |
| Network | None. `android.permission.INTERNET` is removed from the merged manifest (`tools:node="remove"`), verified with `aapt2 dump permissions` on the built APK | every measurement in airplane mode, Wi-Fi off |

Why these choices:
- **E2B, not E4B:** only E2B has accelerator (Tensor G6) builds; E4B is CPU/GPU only.
- **NPU backend, not `TENSOR`:** LiteRT-LM 0.18.0 exposes both `Backend.GOOGLE_TENSOR` and `Backend.NPU`; the embedding engine rejects `GOOGLE_TENSOR` ("Unsupported encoder backend: 5") and the LLM path works through `NPU` with the dispatch driver. `NPU` is used everywhere.
- **Audio on CPU:** the audio encoder needs an explicit `audioBackend`; without one `Question.Audio` has nothing to run on. CPU was the first working choice; not benchmarked against alternatives.
- **256-d embeddings:** the model card says quality holds from 768 down to 256; 128 hurts images.

---

## 2. Toolchain on the Mac

| Tool | Where | Used for |
|---|---|---|
| JDK 17 | `/opt/homebrew/opt/openjdk@17` (`export JAVA_HOME=...`) | Gradle builds, `javap` |
| Android SDK | `/opt/homebrew/share/android-commandlinetools` (`sdk.dir` in `local.properties`) | build-tools 35.0.0, platform 36, platform-tools |
| adb | `/opt/homebrew/share/android-commandlinetools/platform-tools/adb` (not on PATH) | install, model transfer, benchmarks, logcat |
| Gradle wrapper | `./gradlew` (checked in) | `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest`, `lintDebug` |
| `javap` | JDK 17 | reading the public API of `litertlm-android-0.18.0.aar` (classes.jar in `~/.gradle/caches`) when no docs existed for `EmbeddingEngine`, `ResponseFormat`, `ConversationConfig`, `Backend.NPU` |
| Ollama (`gemma4:e2b`, `e4b`, `12b`, `26b-a4b`) | `localhost:11434` | Mac-side prompt prototyping only. Ollama could not force JSON for this model ("structured output is unavailable"), so JSON forcing was validated only through LiteRT-LM |
| `hf` CLI 2.2.0 | `HF_HUB_DISABLE_XET=1 hf download ...` | model downloads (Xet backend buffers whole repos in RAM) |
| Python 3.15 venv + `litert-lm==0.18.0` (pip) | `evals/` | Mac evals on the **same runtime and model files** as the phone (`evals/lt.py`) |
| `say` + `afconvert` (macOS) | — | generating 16 kHz mono 16-bit WAV test questions for the voice path |
| `sips` | — | resizing bench photos to 1536 px, matching the app's importer |
| `curl` against `dl.google.com/android/maven2/.../group-index.xml` | — | finding the published Tensor NPU runtime artifact and its date |
| LiteRT samples (`litert-samples`, `sample_app_tpu`, `voice_assistant`) | `~/gemma-hackathon/litert-samples` | reference for `Backend.NPU(nativeLibraryDir)`, `useLegacyPackaging`, greedy `SamplerConfig`, thought channel |

---

## 3. Code map (`app/src/main/java/com/backtrail/app/ai/`)

Inference owner's files (this handoff):

| File | Purpose |
|---|---|
| `LocalVisionService.kt` | The one shared Gemma engine. `describe`, `compare`, `resolveDestination`, `askAboutTrip`, `askAboutPhoto`, `parseReminder`. One locked, timed (120 s), schema-constrained request path; engine kept loaded; greedy sampling; thinking off |
| `InferenceBackend.kt` | `CPU / GPU / TENSOR / NPU` → runtime `Backend`, with `nativeLibraryDir` for NPU |
| `ModelStore.kt` | Pins both Gemma artifacts (size + SHA-256), imports either, `preferred` = G6 + NPU else generic + GPU |
| `LocalEmbedder.kt` | EmbeddingGemma 2 engine; query/document templates from the model card; image bytes; normalized 256-d vectors |
| `SpotRetriever.kt` | cosine ranking over a spot's photo + text vectors; `Direct / AskModel / NoMatch` decision (thresholds uncalibrated) |
| `TripMemoryIndex.kt` | caches vectors per memory; ranks all saved places for a question; optional last-X-hours window |
| `AlignmentMeter.kt` | smoothed, hysteresis "lining up" level from live-frame similarity (disabled until calibrated) |
| `AssistantModels.kt` | `Question.Text / Question.Audio`, `TripAnswer`, `PhotoAnswer`, `ReminderIntent` |
| `AssistantValidator.kt` | strict JSON parsing; citations must be shown ids; grounded ⇔ cites; scrubs leaked `s1`-style ids; replaces safety verdicts |
| `ProposalValidator.kt` | strict JSON for description, comparison and destination (no fences, exact keys, bounded lengths, allowed ids only) |
| `SafetyTopics.kt` | regex guard: safety questions refused before the model; verdict phrasings replaced after |
| `RightWayCheck.kt` | pure code: YES / NO / NOT_SURE from trail geometry, GPS course, compass, optional camera verdict, optional steps; reasons tagged by source |
| `InferenceTrace.kt` | per-request timings to logcat (`TravisInference`); never carries route data |
| `CheckpointCatalog.kt`, `QueryPrefilter.kt`, `DestinationPrompt.kt` | teammate-era text retrieval for "take me back to…" (short ids, lexical gate, schema with allowed ids) |

Teammates' additions after the handoff (integration owner):

| File | Purpose |
|---|---|
| `VoiceTranscript.kt` | validates 16 kHz mono 16-bit WAV (≤ 20 s) from the microphone and parses Gemma's `{transcript}` answer; no remote recognizer |
| `RouteConversationPrompt.kt` | multi-turn text conversation grounded in the recorded route: chronology, cumulative distances, active destination kept in context |
| `TripRecordPrompt.kt` | one JSON record per memory for prompts (time, route index, title, observations, note) |
| `DescriptionCommit.kt` | applies a description to a memory only if outing id + revision still match |
| `NativeResourceCleanup.kt` | serializes native engine shutdown with requests off the UI thread |

Tests and benchmarks:

| Path | What |
|---|---|
| `app/src/test/.../AssistantTest.kt`, `SpotRetrieverTest.kt`, `RightWayCheckTest.kt`, `ProposalValidatorTest.kt`, `QueryPrefilterTest.kt`, `RequestGateTest.kt` + teammates' `ai/`, `trip/`, `device/`, `ui/` | 173 JVM unit tests on `main` (final-pass receipt) |
| `app/src/androidTest/.../bench/GemmaDeviceBench.kt` | on-phone `describe`, `compare`, `embed` with timings, memory (VmRSS) and raw outputs → `files/bench/*.json` |
| `app/src/androidTest/.../bench/AssistantDeviceBench.kt` | on-phone trip questions, photo questions, safety refusal, reminders, spoken questions |
| `evals/lt.py` + `eval_describe.py`, `eval_compare.py`, `eval_trip.py`, `eval_safety_reminder.py` | Mac evals, stock prompt vs pipeline, same model file; summaries in `evals/results/*.md`, combined in `evals/RESULTS.md` |

---

## 4. What was done, in order (problem → fix → evidence)

1. **First run on the phone failed on format, not on the model.** Bootstrap app, GPU, airplane mode: 20.8 s model load, 9.5 s generation, answer rejected because Gemma wrapped its JSON in Markdown fences (reproduced on the Mac with Ollama).
   Fix: constrain decoding to a JSON schema in the runtime (`ConversationConfig(enableResponseFormat = true)` + `ResponseFormat.json(schema)` per request). Evidence: 3/3 valid on the next run; Mac evals 0/66 → 66/66 usable.

2. **The 2.6 GB model was reloaded for every photo.** Fix: one `Engine` per `LocalVisionService`, created on first use, kept until `close()`; one `Conversation` per request so nothing leaks between photos; `Mutex.tryLock` refuses a second concurrent request with a "busy" failure instead of queueing behind an uninterruptible native init. Evidence: warm requests log `initMs=0`.

3. **The second request on a reused engine crashed.** `conversation.cancelProcess()` on a *finished* conversation broke the next one (`tensor_buffer.cc: Failed to get num packed bytes`). Fix: call it only when generation stopped early. Evidence: 3/3 warm runs after the change.

4. **Getting onto the Tensor G6 NPU.** Three distinct failures: no `libLiteRtDispatch_GoogleTensor.so` in the APK ("No dispatch library found"); the copy from `litert-samples/sample_app_tpu` was built for an older runtime ("Unsupported dispatch runtime version"); without the manifest `uses-native-library libedgetpu_litert.so` entries the driver could not reach the vendor runtime ("Failed to initialize SB"). Upgrading LiteRT-LM to 0.18.1 changed nothing.
   Fix: dependency `litert-npu-runtime-google-tensor:2.3.0` (found via the Maven group index; it ships the driver and the manifest entries) + `packaging { jniLibs { useLegacyPackaging = true } }` + `Backend.NPU(applicationInfo.nativeLibraryDir)`. Evidence: Gemma load 1.5 s (GPU 6.8 s, CPU 12.1 s), 851 MB peak (GPU 1,338, CPU 3,154); EmbeddingGemma load 1.1 s (GPU 19.2 s), text 13–16 ms (GPU 95–106), photo 228 ms (GPU 918). Compiler-plugin warnings ("ODC mode does not support multiple subgraphs") appear in logcat; runs succeed.

5. **Prompt work, measured on real photos.** Describe v2 asks for permanent features and copies sign text; the G6 build read the "Lalibela" shop sign the generic build never did. Compare v2 ("shared materials are not evidence; the same specific object in the same arrangement is") after v1 called adjacent garden spots the same place. Evidence on 8 Pixel photos / 28 pairs: v1 9/25 false "same spot", 3/3 found; v2 0/25 false, 1/3 found. v2 ships; the app compares only against the spot the route expects next.

6. **EmbeddingGemma retrieval.** `LocalEmbedder` (templates `task: search result | query: …` and `title: … | text: …`, images without prefix, normalized 256-d), `SpotRetriever`, `TripMemoryIndex`. Evidence: top-1 correct 7/7 answerable demo queries on NPU; recall@4 10/10 in the Mac trip eval. Finding: scores cannot decide "no match" (unanswerable queries score 0.69–0.71, like real ones), so "not found" is left to Gemma plus validation, never to a threshold.

7. **Assistant features on the shared engine.** `askAboutTrip` (≤ 4 spots, each as text + photo under a short id; answer must cite shown ids; grounded ⇔ cites; leaked ids scrubbed), `askAboutPhoto` (confidence enum; safety guard before and after), `parseReminder`, `Question.Audio` (needs `audioBackend`; `maxNumImages = 4`). Evidence on NPU: water-bottle question right spot 8.7 s, spoken version 8.6 s, "swimming pool" honestly not found, safety question refused with no model call, 3/3 reminders.

8. **"Am I going the right way?"** `RightWayCheck`: pure code, 8 unit tests; camera verdict is supporting evidence only.

9. **Model selection in the app.** `ModelStore.preferred` picks the G6 file + NPU when present, else generic + GPU; `TravisViewModel` builds the service lazily from it and closes it in `onCleared`.

10. **Mac evals on the phone's runtime.** `pip install litert-lm==0.18.0`; `lt.py` mirrors the app (greedy, thinking off, `ConstrainedDecodingConfig(LL_GUIDANCE)` + `ResponseFormat.json`). Four suites, stock prompt vs pipeline, same weights: usable 0/66 → 66/66; risky safety verdicts 4/10 → 0/10; wrong "same spot" 0/51; trip right-spot 8/10 → 10/10; stock better at abstaining (5/6 vs 4/6) and equal on reminders (19/20 vs 18/20). Two safety phrasings that slipped past the answer check were added to `SafetyTopics`.

11. **Teammates then wired everything into the native UI** and added microphone capture (`RECORD_AUDIO`), Gemma audio transcription (`VoiceTranscript`), a route-grounded multi-turn conversation, offline speech output and serialized native cleanup. Final-pass receipt: 173 JVM tests, lint 0 errors, APK without INTERNET permission; on the Pixel in airplane mode: 3-turn route conversation 10.9 / 5.7 / 10.8 s with validated citations, photo question 10.0 s, voice transcription init 1.45 s + 1.83 s generation (NPU language, CPU audio).

---

## 5. Command cookbook

```sh
# Build (JDK 17), unit tests, lint, instrumentation APK
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug

# Install (a debug APK signed on another laptop cannot be updated in place: see §7)
A=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
$A install -r app/build/outputs/apk/debug/app-debug.apk
$A install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Models: push over USB, move into the app's private folder, verify the hash on the phone
$A push ~/gemma-hackathon/models/litertlm-gemma4-E2B/gemma-4-E2B-it_Google_Tensor_G6.litertlm /data/local/tmp/
$A shell "run-as com.backtrail.app mkdir -p no_backup/models"
$A shell "cat /data/local/tmp/gemma-4-E2B-it_Google_Tensor_G6.litertlm | run-as com.backtrail.app sh -c 'cat > no_backup/models/gemma-4-E2B-it_Google_Tensor_G6.litertlm'"
$A shell "run-as com.backtrail.app sha256sum no_backup/models/gemma-4-E2B-it_Google_Tensor_G6.litertlm"

# Offline for every measurement
$A shell cmd connectivity airplane-mode enable
$A shell settings get global airplane_mode_on      # expect 1

# Bench inputs (app-private): photos and 16 kHz WAVs
$A shell "cat /data/local/tmp/pergola-a.jpg | run-as com.backtrail.app sh -c 'mkdir -p files/bench-photos && cat > files/bench-photos/pergola-a.jpg'"
$A shell "cat /data/local/tmp/q1.wav | run-as com.backtrail.app sh -c 'mkdir -p files/bench-audio && cat > files/bench-audio/q1.wav'"

# On-phone benchmarks (args: backend CPU|GPU|NPU, model, prompt v1|v2, runs, tag)
R=com.backtrail.app.test/androidx.test.runner.AndroidJUnitRunner
C=com.backtrail.app.bench.GemmaDeviceBench
$A shell am instrument -w -e class $C#describe -e backend NPU -e model gemma-4-E2B-it_Google_Tensor_G6.litertlm -e runs 2 -e tag npu $R
$A shell am instrument -w -e class $C#compare  -e backend NPU -e model gemma-4-E2B-it_Google_Tensor_G6.litertlm -e tag npu $R
$A shell am instrument -w -e class $C#embed    -e backend NPU -e tag npu $R
$A shell am instrument -w -e class com.backtrail.app.bench.AssistantDeviceBench#assistant -e backend NPU -e tag npu $R

# Read results
$A shell "run-as com.backtrail.app cat files/bench/describe-npu.json"
$A logcat -d | grep -E "TravisBench|TravisInference|E litert"

# Inspect the runtime API when docs are missing
/opt/homebrew/opt/openjdk@17/bin/javap -cp ~/.gradle/caches/8.13/transforms/*/transformed/litertlm-android-0.18.0/jars/classes.jar com.google.ai.edge.litertlm.EmbeddingEngine

# Mac evals on the same runtime and model files
python3 -m venv .venv && .venv/bin/pip install litert-lm==0.18.0
cd evals && ../.venv/bin/python eval_describe.py   # also eval_compare.py, eval_trip.py, eval_safety_reminder.py

# Test audio for the voice path (macOS)
say -o q1.aiff "Where did I leave my water bottle?" && afconvert -f WAVE -d LEI16@16000 -c 1 q1.aiff q1.wav

# Model downloads
export HF_HUB_DISABLE_XET=1
hf download litert-community/gemma-4-E2B-it-litert-lm --include "gemma-4-E2B-it_Google_Tensor_G6.litertlm" --local-dir ~/gemma-hackathon/models/litertlm-gemma4-E2B
```

---

## 6. Key numbers (Pixel 11 Pro, airplane mode)

| | NPU (G6 build) | GPU (generic) | CPU (generic) |
|---|---|---|---|
| Gemma load, cold | 1,493 ms | 6,848 ms | 12,079 ms |
| Describe one photo, warm | 7,733 ms | 8,716 ms | 9,089 ms |
| Compare, 3 synthetic pairs | 13.6 s | 36.4 s | not run |
| Peak app memory | 851 MB | 1,338 MB | 3,154 MB |
| EmbeddingGemma load | 1,108 ms | 19,185 ms | not run |
| EmbeddingGemma text query | 13–16 ms | 95–106 ms | not run |
| EmbeddingGemma one photo | 228 ms | 918 ms | not run |

Both models loaded together: about 1.6 GB of process memory.

---

## 7. Gotchas worth knowing

- **Signing mismatch on install.** A debug APK from another laptop fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstalling deletes the app's private models. The integration build uses a side-by-side package (`com.backtrail.app.integration`) to avoid this.
- **`TENSOR` vs `NPU`.** Use `Backend.NPU(nativeLibraryDir)`; the embedding engine rejects `GOOGLE_TENSOR`.
- **LiteRT-LM 0.18.1 alone does not enable the NPU;** the runtime dependency does.
- **`cancelProcess()` only on early stops;** on a finished conversation it corrupts the reused engine.
- **One request at a time.** Native init cannot be interrupted; the service refuses overlapping requests ("busy") rather than queueing.
- **Ollama cannot force JSON for `gemma4:e2b`;** prototype prompts there, validate format on LiteRT-LM.
- **Embedding similarity is not location.** Different garden spots scored 0.80–0.93, same spots 0.91–0.94. The meter is a hint; the compare verdict and the user decide.
- **Trip answers can cite the wrong spot with confidence** (seen once in evals); the UI always shows the cited photo.
- **Evidence scale is small:** 8 real photos from one garden, 11 photos / 55 pairs / 16 questions on the Mac, one device.
