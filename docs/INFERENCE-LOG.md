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

## Open questions

- Which Tensor G6 path the runtime accepts (TENSOR vs NPU backend) — sweep running.
- EmbeddingGemma latency on device, and whether photo embeddings separate same-place from
  different-place pairs — needs photo pairs taken at the demo route.
- `SpotRetriever` and `AlignmentMeter` thresholds are placeholders until calibrated here.
