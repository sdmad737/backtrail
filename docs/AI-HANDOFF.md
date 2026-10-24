# Travis AI handoff for UI and integration

For the people wiring screens in `TravisViewModel.kt` and `ui/`. Source of truth for numbers:
`docs/INFERENCE-LOG.md` (Pixel 11 Pro, airplane mode). Anything not written there is marked
"not measured".

**Status words.** "Verified on Pixel" = ran on the phone and is in the log. "Compiles only" =
code exists, never run on the phone, so expect surprises.

## 1. Feature table

All functions are on `LocalVisionService` and return `Flow<InferenceState<T>>`
(Loading, then one Success or Failure). Time is generation time on the NPU (Tensor G6 build),
warm engine, from the log.

| Feature | Call | Inputs | Success returns | NPU time | Status |
|---|---|---|---|---|---|
| Describe a saved photo | `describe(ctx, ImageRef)` | `RequestContext`, the photo | `CheckpointDescription(label, fixedFeatures)` | 6.6-8.7 s on real photos; 7.7 s warm, 9.4 s cold (+1.5 s load) | Verified on Pixel |
| "Is this the same spot?" | `compare(ctx, reference, current)` | saved photo, live photo | `VisionAssessment(verdict, matchingFeatures, conflictingFeatures)` | 7.9-10.8 s on real photos (v2 prompt); 3.1-6.9 s on the synthetic set | Verified on Pixel (different-place pairs only; no real same-spot pair yet) |
| "Take me to the place where..." | `resolveDestination(ctx, query, List<Checkpoint>)` | text query, at most 12 checkpoints, at most 500 chars | `DestinationProposal`: `Target(id)`, `AskClarification`, `NoRecordedMatch` | not measured | Compiles only (called from `ask()` in the ViewModel) |
| Ask about my trip | `askAboutTrip(ctx, Question, memories)` | `Question.Text` or `.Audio`, at most 4 `Memory` | `TripAnswer(answer, sourceIds, grounded)` | not measured | Compiles only |
| Ask about a photo just taken | `askAboutPhoto(ctx, ImageRef, Question)` | photo, `Question` | `PhotoAnswer(answer, confidence HIGH/MEDIUM/LOW, safetyRefusal)` | not measured | Compiles only |
| Reminder from a note | `parseReminder(ctx, Question)` | typed or spoken note | `ReminderIntent(remindOnReturn, task)` | not measured | Compiles only |
| Rank spots for a question | `TripMemoryIndex.rank(query, memories, withinHours?, limit=3)` | text query, all memories | `List<Ranked(memory, score)>`, best first | not measured end to end (one query embed is 13-16 ms; indexing a photo is 228 ms each, once) | Embedder verified on Pixel; `TripMemoryIndex` compiles only |
| Embedder primitives | `LocalEmbedder.embedQuery / embedDocument / embedImage` | text or JPEG bytes | 256-dim `FloatArray` | text query 13-16 ms, document 13-18 ms, one photo 228 ms (NPU, G6 file); load 1.1 s | Verified on Pixel |
| Camera "lining up" meter | `AlignmentMeter.update(similarity)` | cosine of live frame vs saved photo | `Level` SEARCHING / CLOSE / ALIGNED | not measured | Compiles only. Thresholds (0.55 / 0.70) are placeholders, not calibrated |
| Safety screen | `SafetyTopics.isSafetyQuestion(text)` | text | Boolean | instant | Unit logic, not run on the phone |

Notes on the table: `askAboutPhoto` already short-circuits a typed safety question into a
Success with `safetyRefusal = true` before the model runs, so a refusal arrives as a
Success, not a Failure. `SpotRetriever` and its thresholds (floor 0.30) are not calibrated: do not use its
`NoMatch`.

## 2. UI states to design, per feature

Every feature needs four states: Loading, Success, "not sure", Failure. Loading is
`InferenceState.Loading`. Failure is `InferenceState.Failure(kind, message)`; the `message`
is already user-safe copy and can be shown as is. Expect 7-11 s for vision calls, so a plain
spinner is not enough: show a labelled, cancellable state (`TravisAction.CancelInference`
exists) and say it is on the phone.

**Describe (after Save photo).**
- Loading: "Reading your photo on this phone..." (photo is already saved; never block the save).
- Success: label and feature chips, editable. The label is only applied when the user left the
  note blank (ViewModel already does this). Mark as "Gemma 4 - local result".
- Not sure: label "Unclear view" with no features. Show "Travis couldn't pick out landmarks.
  Add your own note."
- Failure: "Photo preserved." plus the message. No retry loop; offer one "Try again".
- Watch: some features are malformed fragments ("Foreground, extending into the middle
  ground"). Do not render them as headline text; keep them in a collapsed list.

**Compare (same spot?).**
- Loading: "Comparing with your saved photo..."
- Success, `SUPPORTS_MATCH`: "This looks like the same spot." Show the matching reasons. User
  taps Confirm.
- Success, `INSUFFICIENT_EVIDENCE` or `DIFFERENT_PLACE`: "Not sure this is the same spot."
  Show no reasons at all (rule below). Offer "Take another photo" and "It is the spot" (the
  user can still override).
- Failure: message plus retry.

**Find a place (`resolveDestination`).**
- Loading: "Searching your saved places..."
- `Target`: show the saved photo large with title; "Is this the place?" Confirm / Not it.
- `AskClarification`: "A few places could fit. Add a detail." Show the candidates' photos.
- `NoRecordedMatch`: "I don't see that in your saved places." Not a fact about the world.
- Failure: message.

**Ask about my trip.**
- Loading: "Looking through your saved places..."
- Success `grounded = true`: answer text plus the photos of `sourceIds`, each tappable.
- Success `grounded = false`: show the answer ("I don't see that in your saved places") in a
  muted style with no photos. This is the "not sure" state.
- Failure: message. If memories are empty the service itself returns UNAVAILABLE "No saved
  places match yet"; better to hide the ask box until one place exists.

**Ask about a photo.**
- Loading: "Looking at your photo..."
- Success: answer plus a confidence label. `LOW` should read "A guess"; `HIGH` still reads as
  "Travis thinks", never as certainty.
- `safetyRefusal = true`: show `SafetyTopics.REFUSAL` as a calm, neutral card (no red, no
  warning icon, no retry button). Text: "Travis can't tell from a photo whether something is
  safe to eat, drink, touch or approach. Don't rely on a photo for that."
- Failure: message.

**Reminder from a note.**
- Success with `remindOnReturn = true`: a confirmation row "Remind me on the way back:
  `<task>`" with Add / Dismiss. Never schedule it silently.
- `remindOnReturn = false` or Failure: save the note as written, show nothing alarming.

### UX rules (all features)

1. Show the user's own saved photo next to every AI suggestion (destination, trip answer
   sources, compare reference). The photo is the evidence; the model text is commentary.
2. Show compare reasons (`matchingFeatures`, `conflictingFeatures`) only for
   `SUPPORTS_MATCH`. On non-matches the model invented "matching" features (buildings, road)
   and conflicts that were not there (INFERENCE-LOG, compare entries).
3. Never present AI output as location fact. Use "looks like", "your saved photo", "Travis
   thinks". Location and direction come from GPS and the recorded route, not from Gemma.
4. The user confirms. Model results are proposals; route progress changes only on the user's
   tap (`TravisAction.Confirm`). A Success never advances anything by itself.
5. Undo is always visible after a confirmation (`TravisAction.Undo`).
6. Safety refusals are information, not errors: calm card, no alarm styling.
7. Label model output "Gemma 4 - local result" and keep Explore-demo samples labelled
   "Sample", as the ViewModel already does.

## 3. Wiring notes

- **One `LocalVisionService` for the whole app** (the ViewModel already holds one). The
  engine loads once (cold load 1.5 s on NPU) and stays loaded; do not create a second.
  Same for `LocalEmbedder` and `TripMemoryIndex`: one instance each.
- **Requests are serialized.** If one is running, a second call returns
  `Failure(UNAVAILABLE, "Another local inference is still running on this phone.")`
  immediately; it does not queue. So disable every AI button while `state.busy` is true, and
  do not fire describe-after-save and an ask in the same moment.
- **RequestContext + RequestGate**, as `describe()` and `ask()` do today: build a
  `RequestContext(session, newRequestId, outingId, outing.revision, checkpointId)`, call
  `gate.begin(ctx)`, then in `collect` ignore results unless `gate.accepts(result.context)`
  and the outing id and revision still match, and use `gate.consume(ctx)` on the final result
  so it applies once. Call `invalidate()` on any navigation or mode change. Copy this shape
  for every new call; do not apply a result directly.
- **Lifecycle.** Call `inference.close()` (and `embedder.close()`) in `onCleared()`. Today
  `onCleared()` only closes the camera. `close()` does not block: an in-flight request frees
  the engine when it ends.
- **Timeout.** Each request is bounded at 120 s, then Failure `TIMEOUT`. `FailureKind`:
  `INVALID_RESULT`, `TIMEOUT`, `CANCELLED`, `UNAVAILABLE`, `ERROR`. Show the message; treat
  `INVALID_RESULT` as "try again", `UNAVAILABLE` as "model missing or busy".
- **Trip questions: rank first, max 4 spots.** Call `TripMemoryIndex.rank(query, memories,
  limit = 4)` and pass `ranked.map { it.memory }` to `askAboutTrip`. More than 4
  (`MAX_TRIP_SPOTS`) returns `INVALID_RESULT`. For spoken questions there is no text to rank
  by; transcribe is not available, so either rank by recency (`withinHours`) or ask for a
  typed query. Call `TripMemoryIndex.index(memories)` after a save so the first question is
  warm.
- **Question input.** `Question.Text(text)`: non-blank, at most 500 chars.
  `Question.Audio(wav)`: 16 kHz mono WAV bytes, 1 to 1,000,000 bytes (about 30 s). Gemma takes
  the audio directly. The safety screen only sees text, so spoken questions rely on the
  post-answer screen (`SafetyTopics.isSafetyVerdict`), which also yields a refusal Success.
- **Integration changes needed (integration owner only).**
  1. The ViewModel builds `LocalVisionService(models.file, app.cacheDir)`, which is the generic
     Gemma file on GPU. The measured NPU configuration is the Tensor G6 build with
     `backend = InferenceBackend.NPU` and `nativeLibraryDir = applicationInfo.nativeLibraryDir`.
     `ModelStore` pins the generic file's size and SHA-256, so the G6 file (3,313,938,293 B) is
     not accepted by import. GPU is the working fallback (describe about 8.6-11.7 s).
  2. `LocalEmbedder` defaults to `TENSOR`, which the embedder rejects. Pass `NPU` explicitly
     (GPU as fallback), and pass `nativeLibraryDir`.
  3. `askAboutTrip`, `askAboutPhoto`, `parseReminder`, `TripMemoryIndex` and `AlignmentMeter`
     are not called from any screen yet.

## 4. Known limits (from the log)

- **Embedding scores cannot decide "no match".** On the demo trip every score, including the
  two unanswerable queries, sat at 0.66-0.78 (NPU: 0.69-0.71 for unanswerable). Use
  `TripMemoryIndex.rank` only to narrow candidates; "no match" must come from Gemma
  (`grounded = false`, `NoRecordedMatch`), never from a score threshold. A margin of 0.002
  separated first and second for "back to where I parked", too small to act on.
- **Compare v2 prompt is conservative.** On 6 real different-place pairs v2 gave 6 correct,
  0 false "same spot" (v1 gave 2 false matches on adjacent spots). The cost: the true
  synthetic pair dropped from `SUPPORTS_MATCH` to `INSUFFICIENT_EVIDENCE`. Expect "not sure"
  often; the miss rate on real same-spot pairs is not measured yet. Design "not sure" as a
  normal outcome with an easy "take another photo".
- **Reasons are unreliable on non-matches**; see UX rule 2.
- **Photo embeddings are not calibrated.** Different-spot cosines were 0.836-0.903, highest
  for the same path seen from opposite directions. No same-spot pairs measured, so the
  `AlignmentMeter` levels and any "embeddings agree" gate are not ready.
- **Describe sometimes emits fragment features** (for example "Foreground, extending into the
  middle ground") and is generic on some photos. The G6 build did read a shop sign; the
  generic GPU build did not.
- **Latency.** Vision calls take about 7-11 s on NPU. Not measured: destination, trip
  question, photo question, reminder, and end-to-end rank-then-ask.
- **Not measured at all:** battery and thermals, behaviour under repeated back-to-back calls
  beyond the logged runs, and accuracy of any feature on photos outside the one garden route
  and three sample images.
