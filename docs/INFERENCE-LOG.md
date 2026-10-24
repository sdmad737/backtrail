# Travis inference log

Owner: inference (`codex/inference`). Every number here was measured on the phone below,
in airplane mode, by the command shown. Numbers from model cards are labelled as such.

## Device

| | |
|---|---|
| Phone | Pixel 11 Pro (`grizzly`), Google Tensor G6, arm64-v8a |
| OS | Android 17 (SDK 37), build `CD1A.260618.001.C2` |
| RAM | 11.9 GB total (6.8 GB available at idle, 2026-10-24 13:45) |
| Network | Airplane mode on (`settings get global airplane_mode_on` = 1) for every run |

## Models on the phone (`no_backup/models/`, never in Git)

| File | Size | Use |
|---|---|---|
| `gemma-4-E2B-it.litertlm` | 2,588,147,712 B, SHA-256 `181938105e…a63c` (verified on device) | Gemma 4 E2B, CPU/GPU |
| `gemma-4-E2B-it_Google_Tensor_G6.litertlm` | 3,313,938,293 B | Gemma 4 E2B, Tensor G6 accelerator build |
| `embeddinggemma-2-740m_Google_Tensor_G6.litertlm` | 615,713,460 B | EmbeddingGemma 2 (text+image+audio), Tensor G6 |
| `embeddinggemma-2-740m.litertlm` | 484,622,336 B | EmbeddingGemma 2, CPU/GPU |

## Reproduce

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# models: adb push <file> /data/local/tmp/ then
adb shell "cat /data/local/tmp/<file> | run-as com.backtrail.app sh -c 'cat > no_backup/models/<file>'"
# photos: same, into files/bench-photos/<name>.jpg
adb shell cmd connectivity airplane-mode enable
adb shell am instrument -w -e class com.backtrail.app.bench.GemmaDeviceBench#describe \
  -e backend GPU -e runs 3 -e tag gpu-v2 com.backtrail.app.test/androidx.test.runner.AndroidJUnitRunner
adb shell "run-as com.backtrail.app cat files/bench/describe-gpu-v2.json"
```

Arguments: `backend` CPU|GPU|TENSOR|NPU, `vision` (defaults to backend), `model` (file name),
`prompt` v1|v2, `runs`, `tag`. `#embed` takes the same backend/model/tag arguments.

## Results

### 2026-10-24 13:48 · app smoke test · GPU · bootstrap code (`1a12a74`)
Per-request engine. Load 20,787 ms, generation 9,531 ms, outcome **invalid**: the model
wrapped its JSON in Markdown fences (reproduced on the Mac with Ollama `gemma4:e2b`), so the
strict validator rejected it. The same answer listed a tram, parked cars and pedestrians.

### 2026-10-24 13:58 · bench · GPU · prompt v2 · `7f39d31`
Engine kept loaded, output constrained to the JSON schema. Test photo: one street junction
(`junction-amsterdam.jpg`, sample image from litert-samples).

| Run | Engine | Load ms | Generate ms | Outcome |
|---|---|---|---|---|
| 1 | cold | 6,848 | 11,696 | success |
| 2 | warm | 0 | 8,716 | success |
| 3 | warm | 0 | 8,569 | success |

Peak process memory (VmRSS) 1,338 MB. The first ever load (20.8 s) included one-time GPU
setup now held in the app cache; later cold loads were 6.4–6.8 s.

Answer (v2 prompt): label "Red brick buildings and street scene"; features include tram
tracks, overhead tram wires, white facade on the left, shop fronts. No vehicles or people.
Still generic: it does not read the teal "Lalibela" sign. Prompt tuning needs real photos.

### 2026-10-24 14:00 · bench sweep · describe, one photo, 2 runs each · `7f39d31`

| Config | Load ms | Generate ms (cold / warm) | Peak RSS | Outcome |
|---|---|---|---|---|
| GPU, prompt v1 | 3,876 | 9,913 / 15,374 | 1,293 MB | success ×2 |
| GPU, prompt v2 | 6,848 | 11,696 / 8,716 / 8,569 | 1,338 MB | success ×3 |
| CPU, prompt v2 | 12,079 | 8,924 / 9,089 | 3,154 MB | success ×2 |
| Tensor G6 build, TENSOR backend | – | – | – | `LiteRtLmJniException` ×2 |
| Tensor G6 build, NPU backend | – | – | – | `LiteRtLmJniException` ×2 |
| Tensor G6 build, TENSOR + GPU vision | – | – | – | `LiteRtLmJniException` ×2 |

GPU and CPU generate at about the same speed for this request; GPU loads faster and uses
far less app memory. **Decision: GPU** for the demo. The accelerator builds do not run with
LiteRT-LM 0.18.0 here (0.18.1 was published 2026-10-20; untested).

### 2026-10-24 14:00 · bench · EmbeddingGemma 2 740M · `7f39d31`

- TENSOR backend (G6 file): `Unsupported encoder backend: 5`. NPU backend (G6 file):
  `Failed to allocate tensors`. **GPU (generic file) works.**
- GPU: load 19,185 ms; text query 95–106 ms; document 104–128 ms; one photo 918 ms;
  256-dim vectors; process memory 181 → 716 MB. (Model card's 8.3 ms text / 49 ms
  text+vision figures are for the Tensor TPU path, which did not run here.)
- Retrieval on the synthetic 4-spot demo trip (labels + notes as text, no photos):
  top-1 correct 6 of 7 answerable queries; "back to where I parked" ranked the mailbox
  first and the car park third.
- **All scores sit in 0.66–0.78, including the two unanswerable queries** ("helicopter pad"
  0.707, "train station" 0.699). An absolute floor cannot decide "no saved spot matches"; the
  gap between first and second is the usable signal. Current `SpotRetriever` floor (0.30) is
  therefore meaningless, and "no match" must not be claimed from embeddings alone.

### 2026-10-24 ~14:03 · bench · compare · GPU · `052f14b`
Synthetic smoke set, not accuracy evidence: `junction-a` (sample photo), `junction-b`
(a crop of the same photo), `porch-a` (unrelated sample photo of a cat on a porch).

| Pair | Expected | Verdict | Generate ms |
|---|---|---|---|
| junction-a vs junction-b | same | SUPPORTS_MATCH | 9,423 (load 2,135) |
| junction-a vs porch-a | different | INSUFFICIENT_EVIDENCE | 10,094 |
| junction-b vs porch-a | different | INSUFFICIENT_EVIDENCE | 16,883 |

No false "same spot". But on the mismatched pairs the model still listed "buildings, road,
tracks" as **matching** features (the porch photo has none) and a cat and blue car as
conflicts. **The feature lists are not reliable evidence on non-matches**: the UI should show
reasons only with SUPPORTS_MATCH, and a match should also require photo-embedding agreement
once that is calibrated on real pairs.

### 2026-10-24 ~14:07 · Tensor G6 accelerator enabled · NPU backend

Root causes, in order: (1) no `libLiteRtDispatch_GoogleTensor.so` in the APK; (2) the copy
from litert-samples `sample_app_tpu` was built for an older runtime ("Unsupported dispatch
runtime version"); (3) without the manifest's `uses-native-library libedgetpu_litert.so`
the driver could not reach the vendor runtime ("Failed to initialize SB"). Fix: dependency
`com.google.ai.edge.litert:litert-npu-runtime-google-tensor:2.3.0` (published 2026-10-06,
matches the LiteRT runtime in litertlm 0.18.0), which ships the driver and the manifest
entries, plus uncompressed JNI packaging. LiteRT-LM 0.18.1 alone did not help. The
`TENSOR` backend is still rejected by the embedding engine ("Unsupported encoder backend:
5"); use `NPU`. The log shows compiler-plugin errors ("ODC mode does not support multiple
subgraphs") on some subgraphs; runs still succeed.

Gemma 4 E2B, `gemma-4-E2B-it_Google_Tensor_G6.litertlm`, NPU, describe, `junction-a`:

| Run | Engine | Load ms | Generate ms | Peak RSS |
|---|---|---|---|---|
| 1 | cold | 1,493 | 9,383 | 851 MB |
| 2 | warm | 0 | 7,733 | |

Answer: label "Red brick buildings and street scene"; features now include "Shop front with
'Lalibela'", white window frames, balcony, street lamp post. The G6 build read the sign; the
generic GPU build never did on this photo.

EmbeddingGemma 2 740M, G6 file, NPU: load 1,108 ms; text query 13–16 ms; document
13–18 ms; one photo 228 ms (GPU: 19,185 / ~100 / ~110 / 918 ms). Demo queries: top-1
correct 7 of 7 answerable ("back to where I parked": car 0.711 vs mailbox 0.709, a margin
too small to act on without Gemma). Unanswerable queries still score 0.69–0.71.

**Decision: NPU for both models**, GPU as fallback.

### 2026-10-24 ~14:08 · compare · NPU (G6 build) · same synthetic smoke set

| Pair | Expected | Verdict | Generate ms |
|---|---|---|---|
| junction-a vs junction-b | same | SUPPORTS_MATCH | 6,938 (load 1,132) |
| junction-a vs porch-a | different | INSUFFICIENT_EVIDENCE | 3,481 |
| junction-b vs porch-a | different | INSUFFICIENT_EVIDENCE | 3,148 |

Same verdicts as GPU at roughly a third of the time. The mismatched pairs again list
invented "matching" features ("buildings", "road"), and the matched pair lists a bus as a
conflict, so the reasons still need the UI rule above. Real photo pairs are still needed.

### 2026-10-24 ~14:20 · first real photos · NPU (G6 build)
Four photos taken on the Pixel's camera at 14:14 along one garden route (private property:
kept on the device and the Mac only, never committed): `pergola-a`, `pine-a` (pine tree,
pergola beam overhead), `gatepath-a` (tiled path toward an iron gate), `gateback-a` (through
the gate, looking back up the same path). Four different spots, so every pair is
"different place". Resized to 1536 px like the app's importer.

**describe** (one run each, 6.6–8.7 s warm): "Wooden pergola structure over patio" (pergola,
patio paving, water feature, tree trunk, planter); "Dense evergreen tree in centre";
"Stone walkway and lamp post"; "Stone archway and walkway". Some features are malformed
location fragments ("Foreground, extending into the middle ground").

**compare**, 6 different-place pairs:

| Prompt | Correct | False "same spot" | Unsure | Generate ms (warm) |
|---|---|---|---|---|
| v1 | 0 | **2** (gateback/gatepath, pergola/pine) | 4 | 4,498–10,820 |
| v2 (shared materials are not evidence) | **6** | 0 | 0 | 7,931–10,822 |

Both v1 false matches were adjacent spots sharing paving, pergola and trees.
v2 on the synthetic set: the true pair (junction-a vs its crop) dropped from SUPPORTS_MATCH
to INSUFFICIENT_EVIDENCE; the porch pairs stayed INSUFFICIENT_EVIDENCE. **v2 trades missed
matches for no false matches; kept as default** because a wrong "same spot" misleads a walker
and "not sure" costs one more look. Real same-spot pairs are needed to measure the miss rate.

**Photo embeddings** (NPU, 227–309 ms each): different-spot cosines 0.836–0.903, highest for
gateback/gatepath (same path, opposite directions). Same-spot pairs needed before any
threshold can be set.

### 2026-10-24 ~14:40 · 8 real photos · NPU (G6 build) · `1f8d728`
Second batch taken at 14:25: `pine-b`, `pergola-b` (same spots as the first batch, new
angle), `redwood-a`, `redwood-b` (a large tree, wide shot and close-up with a stone
lantern; paired by the main session from the photos, not labelled by the photographer).
8 photos → 28 pairs: 3 same-spot, 25 different-spot.

**compare**

| Prompt | Same-spot found (of 3) | False "same spot" (of 25) | Unsure | Correct "different" | Warm ms (min / median / max) |
|---|---|---|---|---|---|
| v1 | **3** | **9** (36%) | 16 | 0 | 5,240 / 7,953 / 10,490 |
| v2 (default) | 1 (pine) | **0** | 1 | 24 | 4,935 / 8,471 / 16,202 |

v1 calls nearly every pergola/pine/tree pair a match; v2 never does, but called the
pergola and the redwood pairs DIFFERENT_PLACE. On this garden — one property where every
spot shares the same pergola, paving and trees — neither prompt is good enough on its own.
**Product rule:** compare only against the *expected next spot* on the recorded route
(ordinary code: route order + GPS distance), show "not sure" as the honest fallback, and
the user decides. A wrong "same spot" never advances the route by itself.

**Photo embeddings** (8 photos, 278–305 ms each): same-spot cosines 0.944 (pine),
0.938 (pergola), 0.914 (redwood); different-spot cosines 0.795–0.930. The two highest
pairs are both same-spot, but one different pair (pine-b/redwood-a, 0.930) beats the
redwood pair. Good enough to drive a "warmer/colder" lining-up meter against one known
saved photo; not good enough to identify which spot you are at.

**describe** (warm 3.0–8.9 s): "Wooden pergola structure over water", "Large tree trunk
and stone steps", "Large tree trunk left", "Stone pergola structure and dense evergreen
trees" (pine-b: the pergola is wood, not stone).

## Open questions

- EmbeddingGemma latency on device, and whether photo embeddings separate same-place from
  different-place pairs — needs photo pairs taken at the demo route.
- `SpotRetriever` and `AlignmentMeter` thresholds are placeholders until calibrated here.
