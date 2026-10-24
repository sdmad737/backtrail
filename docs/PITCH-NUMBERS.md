# Travis pitch numbers and script

Note: the 8-photo accuracy run results will be appended by the main session (not included here).

Sources: `docs/INFERENCE-LOG.md` (all numbers; measured on the Pixel 11 Pro, Tensor G6, Android 17, airplane mode on for every run, build `7f39d31` / `052f14b` unless stated) and `docs/research/COMPETITOR-TEARDOWN.md`. "not measured" means the log has no number. Sample sizes are tiny (1 photo, 2-3 runs); say "on our test photo", not "on average".

---

## Slide 1. Hardware: NPU vs GPU vs CPU

Gemma 4 E2B (vision LLM). NPU = `gemma-4-E2B-it_Google_Tensor_G6.litertlm`; GPU and CPU = generic `gemma-4-E2B-it.litertlm` (LiteRT-LM 0.18.0). Photo: `junction-a`.

| Gemma 4 E2B | NPU (Tensor G6) | GPU | CPU |
|---|---|---|---|
| Load (cold engine) | 1,493 ms (14:07, NPU run 1) | 6,848 ms (13:58, v2 prompt); 3,876 ms (14:00 sweep, v1); first ever load 20,787 ms (13:48, incl. one-time GPU setup) | 12,079 ms (14:00 sweep, v2) |
| Describe, 1 photo, cold | 9,383 ms | 11,696 ms | 8,924 ms |
| Describe, 1 photo, warm | 7,733 ms | 8,716 ms and 8,569 ms | 9,089 ms |
| Compare, generate (3 synthetic pairs, 14:03 GPU / 14:08 NPU) | 6,938 / 3,481 / 3,148 ms (sum 13,567) | 9,423 / 10,094 / 16,883 ms (sum 36,400) | not measured |
| Peak memory (VmRSS, whole app process) | 851 MB | 1,338 MB (v2); 1,293 MB (v1) | 3,154 MB |

EmbeddingGemma 2 740M (256-dim vectors). NPU = G6 file; GPU = generic file. CPU was never run.

| EmbeddingGemma 2 | NPU (G6 file, ~14:07) | GPU (generic file, 14:00) | CPU |
|---|---|---|---|
| Load | 1,108 ms | 19,185 ms | not measured |
| Text query | 13-16 ms | 95-106 ms | not measured |
| Document text | 13-18 ms | 104-128 ms | not measured |
| One photo | 228 ms (later runs 227-309 ms, 14:20) | 918 ms | not measured |
| Memory | not measured | 181 -> 716 MB process | not measured |
| Demo-trip retrieval, top-1 | 7 of 7 answerable | 6 of 7 answerable | not measured |

Model-card figures (NOT measured by us, do not present as ours): 8.3 ms text, 49 ms text+vision on the Tensor TPU path.

### Headline speedups (arithmetic, NPU vs GPU unless stated)

Gemma:
- Load vs GPU cold: 6,848 / 1,493 = **4.6x**. Vs CPU: 12,079 / 1,493 = **8.1x**. Vs the very first GPU load: 20,787 / 1,493 = 13.9x (that first load included one-time GPU setup, so do not lead with it).
- Describe warm vs GPU: 8,716 / 7,733 = 1.13x (8,569 / 7,733 = 1.11x). Vs CPU warm: 9,089 / 7,733 = 1.18x. Cold vs GPU: 11,696 / 9,383 = 1.25x. **Describe is only about 1.1-1.25x faster. Say so; do not claim more.**
- Compare, three pairs: 36,400 / 13,567 = **2.7x** total (pairwise 1.4x, 2.9x, 5.4x). The first pair also included a 1,132 ms load on NPU and 2,135 ms on GPU. The log calls this "roughly a third of the time" (13,567 / 36,400 = 0.37).
- Memory: 1,338 / 851 = 1.6x less than GPU; 3,154 / 851 = 3.7x less than CPU. This is process VmRSS only.

EmbeddingGemma:
- Load: 19,185 / 1,108 = **17.3x**.
- Text query: 95 / 16 = 5.9x to 106 / 13 = 8.2x, i.e. **about 6-8x**.
- Photo: 918 / 228 = **4.0x**.

Caveats to say out loud: GPU/CPU use the generic model file, the NPU uses the vendor-built G6 file, so this is "best build per backend", not a same-weights comparison. The NPU log shows compiler-plugin warnings ("ODC mode does not support multiple subgraphs") on some subgraphs; runs still succeed. Compare timings use a synthetic 3-pair set, one run each. Prompt used for the NPU describe run is not stated in the log.

Quality note: the G6 build read the "Lalibela" shop sign on `junction-a`; the generic GPU build never did on that photo.

---

## Slide 2. How we made it work

Three root causes for the NPU (log, 14:07):
1. **No dispatch driver in the APK.** `libLiteRtDispatch_GoogleTensor.so` was missing.
2. **Version mismatch.** The copy from litert-samples `sample_app_tpu` was built for an older runtime ("Unsupported dispatch runtime version").
3. **Manifest missing `uses-native-library libedgetpu_litert.so`.** Without it the driver could not reach the vendor runtime ("Failed to initialize SB").

Fix: one dependency, `litert-npu-runtime-google-tensor:2.3.0` (published 2026-10-06, matches the runtime in litertlm 0.18.0). It ships the driver and the manifest entries; plus uncompressed JNI packaging. Upgrading LiteRT-LM to 0.18.1 alone did not help. Use the `NPU` backend, not `TENSOR` (still rejected by the embedding engine).

Other fixes:
- **JSON-fence fix.** First smoke test (13:48): the model wrapped JSON in Markdown fences, the strict validator rejected it (outcome invalid; reproduced on the Mac with Ollama `gemma4:e2b`). Fix: constrain output to the JSON schema (13:58, run succeeded 3 of 3).
- **Kept-loaded engine.** Per-request engine: 20,787 ms load every time (13:48 smoke test). Engine kept loaded: warm runs show load 0 ms. Careful with the "20.8 s -> 1.5 s" line: 20.8 s was the first-ever GPU load (with one-time GPU setup); 1.5 s is the NPU cold load. Warm requests pay 0 ms. Say "20.8 s first load, 1.5 s on the NPU, 0 ms once kept loaded", not that keeping it loaded alone gave 1.5 s.
- **Cancel bug** (from code comments in `ai/LocalVisionService.kt`, not in the inference log, so no timing): cancelling the Kotlin collection does not cancel native inference in LiteRT-LM 0.18.0, so we call `conversation.cancelProcess()` but only when generation stopped early, because cancelling a finished conversation broke the next request on a reused engine (observed Pixel 11 Pro, GPU). A second request while native work is in progress is refused with a "busy" message instead of queued, since native init cannot be interrupted or timed out.

---

## Slide 3. Trust

**False-match finding** (14:20, 4 real garden photos, 6 different-place pairs, NPU):

| Prompt | Correct | False "same spot" | Unsure |
|---|---|---|---|
| v1 | 0 | **2** (gateback/gatepath, pergola/pine) | 4 |
| v2 ("shared materials are not evidence") | **6** | **0** | 0 |

Both v1 false matches were adjacent spots sharing paving, pergola and trees. v2 is the default.

**Honest trade-off:** v2 trades missed matches for no false matches. On the synthetic set the true pair (junction-a vs its crop) dropped from SUPPORTS_MATCH to INSUFFICIENT_EVIDENCE. Real same-spot pairs are not yet measured, so the miss rate is **not measured**. Reasoning: a wrong "same spot" misleads a walker; "not sure" costs one more look.

Other trust facts from the log:
- On non-matching pairs the model still lists invented "matching" features, so the UI shows reasons only with SUPPORTS_MATCH.
- Embedding scores cannot say "no match": unanswerable queries still score 0.69-0.71 (helicopter pad 0.707 on GPU), answerable ones 0.66-0.78. Different-spot photo cosines were 0.836-0.903, so no photo-embedding threshold is set yet.
- "back to where I parked" on the NPU: car 0.711 vs mailbox 0.709, a margin too small to act on without Gemma.

**Safety refusal design:** the log has no entry for this. What the repo shows: PRODUCT.txt lists route safety certification, rescue transmission and plant-safety verdicts as non-goals, and the model's answer is never auto-applied (the user confirms; INSUFFICIENT_EVIDENCE is a first-class result). Confirm the exact in-app refusal wording before presenting it.

**No internet permission:** `app/src/main/AndroidManifest.xml` contains `android.permission.INTERNET` with `tools:node="remove"`, so even a library that requests it cannot get it in the merged manifest (verify with the merged-manifest dump or `aapt dump permissions` before claiming it on stage). Every measured run was in airplane mode.

---

## Slide 4. Why not existing apps (from the teardown; caveats kept)

1. **Google Maps has no offline walking directions.** Google's help page: "When you're offline, transit, bicycling, or walking directions are unavailable." Offline maps also expire. [1] (Caveat: Travis has no map tiles and no turn-by-turn; it complements a map.)
2. **Every find-my-car feature saves a GPS pin, and GPS is weakest where you park.** "GPS signals are not available indoors or underground" [3]; urban smartphone error typically 7-13 m [17]; sub-meter not reached under tree cover even with 10-minute fixes [19]. Travis adds what the place looks like as a second cue; it does not fix GPS.
3. **AI camera assistants upload your photo and don't remember your trip.** Gemini keeps chats up to 72 h with Activity off, reviewed chats up to 3 years [10]; Visual Intelligence passes images to Google or ChatGPT [11][12]; Lens offline is translation only [9]. No competitor found compares the live view with a photo you took earlier.
4. **Fitness and trail location data has leaked.** Strava heatmap exposed military bases (2018) [13]; bodyguards' runs revealed leaders' whereabouts (2024) [14]. Strava says it does not sell personal information but shares aggregated data [15]. Travis has no account and no upload.
5. **Offline mode is a paywall and an account.** AllTrails Plus ($35.99/yr) [4]; Gaia needs an Outside account [6]; Komoot charges for navigation [7]. Be generous to **OsmAnd**: free-tier-capped, no account, fully offline, the most honest privacy competitor [25][26]. It has no AI, landmark memory or photo check.

Do not quote: AllTrails "50 m" (source page returned 403, unverified); a per-hour GPS battery figure; Komoot layoff percentages; Gaia free-tier offline storage.

---

## Slide 5 / 60-second demo script

Mark: **[verified]** = measured in INFERENCE-LOG; **[if verified by demo time]** = not yet shown working end to end in the log.

- **0:00-0:08** Swipe down, turn on **airplane mode** and show the icon. "No signal, and the app has no internet permission." **[verified: manifest line; all benchmarks in airplane mode]**
- **0:08-0:22** Take a photo of a spot, tap describe. Gemma 4 E2B on the Tensor G6 NPU returns a label and features in roughly 7-9 s warm (7,733 ms warm, 9,383 ms cold on our test photo). Fill the wait with: "NPU engine loads in 1.5 s, was 6.8 s on GPU, 12 s on CPU." **[verified in bench; in-app UI flow if verified by demo time]**
- **0:22-0:40** Later, at a junction, take a new photo and tap compare against the saved photo. Expect a verdict in 3-8 s (NPU compare 3,148-6,938 ms on the smoke set). Show an adjacent-but-different spot returning "not sure" rather than "same spot": "v1 said same spot twice; v2 said zero false matches of six." **[verified: 6 of 6 correct on different pairs; same-spot recognition on real photos NOT measured, so use the synthetic junction pair or "if verified by demo time"]**
- **0:40-0:52** Type a query ("where did I park?" / "the gate") into search. EmbeddingGemma on the NPU answers in 13-16 ms per text query, top-1 right 7 of 7 on the demo trip. Do not use "where I parked" as the showpiece (car 0.711 vs mailbox 0.709). **[verified on synthetic 4-spot trip; on-stage query if verified by demo time]**
- **0:52-1:00** Close: "Everything you just saw ran on the phone, in airplane mode, no account, no upload." Keep the airplane icon visible.

Do not demo or claim (not verified in the log): to-do reminders firing at a place, live route recording accuracy, GPS drift handling, the "return trip" guidance flow, 8-photo accuracy (main session will append).

---

## Slide 6. Likely judge questions, short honest answers

- **Is the NPU actually faster, or is this marketing?** Embeddings yes: 6-8x for text, 4x for photos, 17x load. Gemma describe only 1.1-1.25x, compare about 2.7x on a three-pair smoke set. Load is 4.6x faster than GPU (6,848 -> 1,493 ms) and memory is lower (851 vs 1,338 MB). We used each backend's own model build, one photo, few runs.
- **Why did the NPU not work out of the box?** Three things: missing dispatch driver, a driver built for an older runtime, and a missing `uses-native-library` manifest entry. One matching dependency fixed it. Upgrading LiteRT-LM did not.
- **How accurate is it?** On 6 different-place pairs from a real garden route, v2 was 6 of 6 correct with 0 false matches (v1: 2 false). The miss rate on real same-spot pairs is not measured yet. 8-photo run results: see appended section.
- **Why would you accept missed matches?** A false "same spot" misleads a walker; "not sure" costs one more look. The user always confirms.
- **Can embeddings tell you "nothing matches"?** No. Unanswerable queries still score 0.69-0.71, so we use the gap between first and second, and we do not claim "no match" from embeddings alone. Thresholds are placeholders until calibrated.
- **Does the model make things up?** Yes, on non-matches it lists invented "matching" features (and a bus as a conflict on a matched pair), so reasons are shown only with SUPPORTS_MATCH.
- **How do we know it's offline?** Airplane mode on for every benchmark; the INTERNET permission is removed from the merged manifest; models sit in `no_backup/models/`, sideloaded, not downloaded at use.
- **Why not Google Maps / AllTrails / OsmAnd?** Maps has no offline walking directions; trail apps paywall offline and need accounts; OsmAnd is the closest and honest, but is a map with no landmark memory or photo check. Travis is a memory layer next to a map, not a replacement. Travis has no map tiles and no turn-by-turn.
- **Could Google ship this tomorrow?** They have the models. Nobody has shipped it; incumbents are pulled toward cloud and accounts; we show the whole loop on the NPU today.
- **Limits?** Only tested on one Pixel 11 Pro, short routes with a few spots, one device, no backup (deleting a trip loses it). Vision may say "not sure" at night, in snow or where levels look alike. It is also 7-9 s per photo, not instant.
- **Cancelling mid-inference?** A native-side limitation in LiteRT-LM 0.18.0: collection cancel does not stop the model, so we call `cancelProcess()` and refuse overlapping requests rather than queue them.
- **Privacy of your test photos?** The garden photos are kept on the device and the Mac, never committed.
