# Travis model readiness — October 24, 2026

The selected Gemma 4 E2B bundle is fully downloaded and integrity-verified. Real text, photo, and memory requests ran from its local path on the M4 Mac. **No physical phone was connected; phone installation and offline phone execution remain pending.**

## Three separate milestones

| Milestone | Result |
|---|---|
| A. Downloaded and verified on Mac | Complete; full bytes and SHA-256 match publisher metadata |
| B. Installed in persistent phone app storage | Pending; no physical phone available |
| C. Loaded and exercised offline on phone | Pending; Mac evidence does not establish phone readiness |

The model file is `/Users/shahinmadantschi/Downloads/backtrail-team-handoff/models/gemma-4-E2B-it.litertlm`.

- Publisher: `litert-community/gemma-4-E2B-it-litert-lm`
- Pinned file revision: `6e5c4f1e395deb959c494953478fa5cec4b8008f`
- Exact bytes: **2,588,147,712**
- Full SHA-256: `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`
- The existing download from revision `1caa527c…` is byte-identical to this handoff revision. No redundant multi-gigabyte download was needed.
- Checked `LITERTLM` magic, exact file size and full SHA-256; this is not a pointer, HTML error, or partial file.
- [Authoritative pinned metadata](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/revision/6e5c4f1e395deb959c494953478fa5cec4b8008f?blobs=true) supplies the LFS SHA-256, not the different Xet storage hash.

The machine-readable [asset manifest](model-assets.json) also records the verified native Android AAR and the macOS arm64 Python wheel. All weights and downloaded runtimes remain in ignored directories.

## Required runtime and companion assets

Android stays on **LiteRT-LM 0.18.0**, GPU text/vision. This unified `.litertlm` contains tokenizer/metadata, LLM weights, vision encoder and adapter, and unused audio components. Native `ModelInfo` reports text, vision and audio inputs, no video, and a maximum vision budget of 280 tokens. No external projector, tokenizer download, embedding model, or network service is required by the implemented flow. Small saved-memory catalogs are supplied as bounded JSON.

The host runtime was downloaded from PyPI, verified against its published size and SHA-256, then installed from the local wheel with `--no-index`:

- `.tooling/model-readiness/wheels/litert_lm_api-0.18.0-py3-none-macosx_12_0_arm64.whl`: **21,430,649 bytes**, SHA-256 `9fd0c55835e469a035c1b75cde4797b26292963c2c36d9fcdfceb965ffa08a37`.
- Environment: `.tooling/litert-python`; Python 3.12.14, no extra package dependencies.
- Cached Android AAR: **20,905,807 bytes**, SHA-256 `706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f`, matching [Google Maven checksum](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar.sha256) and its HTTP content length. The ARM64 JNI library is present. Both runtime ZIP archives passed integrity checks.

## What actually ran

Hardware: Apple M4 Mac, arm64, 16 GB unified memory, macOS 27.0.1. Runtime: official Python 0.18.0. Context limit 4,096; output limit 384; thinking disabled; deterministic sampler (`top_k=1`, temperature 0, seed 17). Each request used a fresh conversation. Timings include conversation creation and request completion; they are single observed runs, not benchmarks.

A successful GPU run executed under macOS `sandbox-exec` with **all network operations denied**, reading model and photo from disk. The machine's Wi-Fi was not turned off; the inference process itself was denied network access.

| Network-denied Mac GPU test | Observed result | Seconds |
|---|---|---:|
| Engine initialization, final cached run | Success | 0.694 |
| Short text JSON | `{"status":"ready"}` | 0.733 |
| Real NPS photo, exact app prompt with JSON constraints | Valid label + eight bounded features | 3.519 |
| “Where did I leave my warm red layer?” | Recorded `junction` | 1.778 |
| “Find the spot where I topped up my drinking bottle.” | Recorded `creek` | 1.641 |
| “Take me to where we parked.” | Recorded `car` | 1.626 |
| “Where is the stone cabin with a purple door?” | `NO_RECORDED_MATCH` | 1.600 |

The first successful GPU invocation initialized in 2.160 seconds; the final run reused its on-disk compilation cache. Raw outputs and timings are retained in [the final successful run](model-tests/host-gpu-final.json) and [the earlier unconstrained-photo run](model-tests/host-gpu-network-denied.json). The three-record catalog is a **synthetic test fixture**, not a recorded outing. These four retrieval checks do not establish general accuracy.

The real image is an NPS public-domain photograph, [Boulder Bridge](https://npgallery.nps.gov/AssetDetail/FF52F1CC-F2B0-94C2-76208D18B140202A), credit NPS, supplied as `explore/assets/stone-bridge.jpg`. It is a newly supplied image test, **not a fresh phone camera capture**. A separately generated synthetic diagnostic image also ran on CPU and returned the actual sign text and shapes. Fresh phone capture remains an acceptance check.

### Failures retained

- The exact Android photo prompt initially returned JSON inside Markdown fences; strict validation rejected it. Adding the runtime JSON schema constraint produced the valid structured image result above. The rejected response is retained in `host-gpu-structured-photo.json`.

- Initial CPU unconstrained retrieval emitted `{"action":"creek"}` for one paraphrase; the stricter prompt still produced `{"action":"creek","checkpointId":"creek"}`. Both are invalid and the app rejects them.
- CPU constrained decoding produced valid JSON but a false-negative `NO_RECORDED_MATCH` for that same bottle query. The other two paraphrases and no-match passed. CPU is therefore not a claim of identical semantic behavior.
- The first constrained Python attempt omitted the required `LL_GUIDANCE` provider and failed before inference. Correcting the SDK configuration resolved it; no model assets changed.
- The first GPU attempt ran inside the restrictive tool sandbox and failed with `No adapters found`. GPU access outside that sandbox, while explicitly denying networking to the process, succeeded. This is not a phone driver result.
- Every run, including failures, is retained in `docs/model-tests/`. Local verbose logs and cache are under `.tooling/model-readiness/`.

## Android integration delivered

`LocalVisionService.resolveDestination` now makes a real local model request with a maximum 12-record catalog, 500-character query and 8,000-character encoded payload. It passes original note text separately from model-observed features, bounds prompt fields, uses JSON-schema constrained decoding limited to supplied IDs, and still performs strict semantic validation afterward. Empty or oversized catalogs fail explicitly. No result writes route state; the caller must validate `RequestContext`, show the source photo and obtain user confirmation.

Image description now also uses a bounded JSON schema at generation time and strict validation afterward; two-image comparison is visibly unavailable. A single mutex serializes inference; expensive work stays on `Dispatchers.IO`. The 120-second coroutine budget includes queue waiting, but native initialization/cleanup may still block past it. Each request closes its engine. No warm-engine or battery measurement is claimed. GPU phone compatibility, initialization latency, cancellation and memory pressure remain unverified.

Offline Kotlin compilation and **12 unit tests** passed, including escaped input, duplicate/oversized catalog rejection and rejection of the observed malformed output. The existing request/revision tests remain passing.

## Reproduce host verification

From the repository root:

```sh
python3 scripts/model-verify.py --check-source
```

To provision the exact model on a clean machine, add `--download`; it downloads/resumes a `.partial` file and renames only after complete verification. An existing corrupt destination is rejected rather than overwritten silently.

The already-installed host environment can repeat the successful GPU smoke test (allow up to 120 seconds externally):

```sh
/usr/bin/sandbox-exec -p '(version 1)(allow default)(deny network*)' \
  .tooling/litert-python/bin/python scripts/model-smoke.py \
  --model "$PWD/models/gemma-4-E2B-it.litertlm" \
  --image "$PWD/explore/assets/stone-bridge.jpg" \
  --backend gpu --case all --constrained --structured-image \
  --cache "$PWD/.tooling/model-readiness/gpu-cache" \
  --output "$PWD/.tooling/model-readiness/repeat-gpu.json"
```

Use `--constrained --structured-image --case image` to check the exact Android description prompt and its JSON bounds. `model-smoke.py` exits nonzero for failed checks. The checked-in historical reports preserve the original results, including earlier script versions that returned zero despite a failed semantic assertion.

A clean host can create a Python 3.10+ virtualenv and install `litert-lm-api==0.18.0` from the pinned, verified wheel. See [official Python API](https://developers.google.com/edge/litert-lm/python). Host testing is optional for phone operation; the phone does not contact this Python environment.

## Phone transfer and first offline proof

The integration owner should verify ARM64, phone model/OS, available memory and roughly 8 GB free disk (source and app-private copies together occupy about 5.18 GB, before caches). Then:

```sh
source .tooling/env.sh
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push models/gemma-4-E2B-it.litertlm /sdcard/Download/gemma-4-E2B-it.litertlm
adb shell sha256sum /sdcard/Download/gemma-4-E2B-it.litertlm
```

In the app's local model-import control, select that Download file. `ModelStore` verifies all bytes and full SHA-256 while copying into persistent private storage: `/data/user/0/com.backtrail.app/no_backup/models/gemma-4-E2B-it.litertlm`. Do not mark B complete merely because the file reached Downloads.

Cold-launch with cellular and Wi-Fi disabled. Capture a new real photo; run description, all three paraphrased requests and no-match on actual saved records. Record model loading, first response, repeated request, cancellation/error and memory behavior. Keep route recording active during inference. Only that physical-device run can complete C. No NPU, phone GPU, voice, GPS accuracy or AR hardware performance has been proven by the host tests.
