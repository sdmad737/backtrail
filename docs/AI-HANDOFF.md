# Travis AI handoff

For the teammates building the AR, UI and UX around the AI. This doc covers **what each
AI feature does, how to call it, what the screen must do with each result, and what is
left to build.** It contains no visual-design advice.

All numbers come from `docs/INFERENCE-LOG.md`. They were measured on the Pixel 11 Pro in
airplane mode, with Gemma 4 E2B and EmbeddingGemma 2 running on the Tensor G6 NPU.

---

## 1. Status at a glance

| Feature | Call | Time on Pixel (NPU) | Status | On a screen yet? |
|---|---|---|---|---|
| Describe a saved photo | `LocalVisionService.describe` | 6.6–8.9 s | Verified on Pixel | Yes, runs after Save photo |
| Find a saved place ("take me back to…") | `LocalVisionService.resolveDestination` | not measured | Built (teammate's prompt, on the shared engine) | Yes, the Ask box |
| Same spot? (live photo vs saved photo) | `LocalVisionService.compare` | ~8.5 s | Verified on Pixel: 0 of 25 false matches, found 1 of 3 real matches | Yes, for the expected retrace spot |
| Ask about my trip | `LocalVisionService.askAboutTrip` | answer 7.9–9.4 s | Verified on Pixel | Yes, limited to four recent places; no semantic ranking |
| Ask about a photo ("what tree is this?") | `LocalVisionService.askAboutPhoto` | 8.8–10.2 s | Verified on Pixel | Yes |
| Safety refusal (eat, drink, touch, dangerous) | built into `askAboutPhoto` | instant for typed questions | Verified on Pixel | Yes |
| Reminder from a note | `LocalVisionService.parseReminder` | 4.6–5.9 s | Verified on Pixel (3 of 3 correct) | Yes, user confirms Add or Dismiss |
| Voice questions | `Question.Audio(wav)` into any of the above | ~8.6 s | Verified on Pixel with a generated WAV | **No** (no mic recording yet) |
| Am I going the right way? | `RightWayCheck.check` | instant, no model | Unit-tested (8 tests), **not run on a walk** | Yes, deterministic GPS/compass check in retrace mode |
| Lining-up meter while aiming the camera | `LocalEmbedder.embedImage` + `AlignmentMeter.update` | ~0.3 s per frame | Embedder verified; meter thresholds **not calibrated** | **No** |

"Verified on Pixel" means a benchmark test ran the real model on the phone. UI wiring is
implemented for the marked features; a benchmark result does not replace app-flow testing.

---

## 2. Setup (do this first)

1. Pull `main`.
2. Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:assembleDebug`.
3. Models: the test Pixel already has all four in the app's `no_backup/models/`. For another
   phone, use the in-app model import (it accepts either Gemma file and checks the size and
   hash), or copy the files the way `docs/INFERENCE-LOG.md` → "Reproduce" shows.
   - `gemma-4-E2B-it_Google_Tensor_G6.litertlm`: Gemma on the NPU. Preferred.
   - `gemma-4-E2B-it.litertlm`: Gemma on the GPU. Used automatically when the G6 file is absent.
   - `embeddinggemma-2-740m_Google_Tensor_G6.litertlm`: EmbeddingGemma on the NPU.
4. The app picks its model through `ModelStore.preferred`: the G6 file on the NPU, otherwise
   the generic file on the GPU. `TravisViewModel` already does this.
5. Installing a debug build signed on a different laptop fails with "signatures do not
   match". Uninstall first; that deletes the app's models, so copy them again afterwards.

---

## 3. Rules every AI screen must follow

1. **Show the user's own saved photo next to every AI suggestion:** a destination, the
   sources of a trip answer, the reference photo in a compare. The photo is the evidence.
2. **The user confirms.** No AI result changes route progress, saves a reminder or marks a
   spot by itself. Only the user's tap does, through `TravisAction.Confirm` or an explicit Add.
3. **Undo is available after every confirmation** (`TravisAction.Undo`).
4. **"Not sure" is a normal outcome, not an error.** Each feature below lists its not-sure
   state, and each one needs a next step for the user.
5. **Never present AI output as location fact.** Gemma never says "you are here" or "turn
   left". Location and direction come from GPS and the recorded trail (`RightWayCheck`,
   `TrailMath`).
6. **Disable every AI button while a request is running.** The engine runs one request at
   a time, and a second call fails immediately with "Another local inference is still running
   on this phone."
7. **Every AI wait needs a Cancel** (`TravisAction.CancelInference`), because vision calls
   take 5–10 s.
8. **Show a Failure's `message` as-is.** It's already written for the user.

---

## 4. Features: how to call them and what to do with each result

Every `LocalVisionService` call returns `Flow<InferenceState<T>>`: first `Loading`, then
exactly one `Success(value)` or `Failure(kind, message)`. Use the RequestContext and
RequestGate pattern that `describe()` and `ask()` in `TravisViewModel` already use (see
section 6).

### 4.1 Describe a saved photo (already wired)
- **Call:** `describe(ctx, ImageRef(memory.id, photoPath, capturedAt))`
- **Success:** `CheckpointDescription(label, fixedFeatures)`. The ViewModel saves the
  features into `memory.description` and uses the label as the title when the user's note is
  blank.
- **Not sure:** label `"Unclear view"` with an empty feature list. Ask the user to add their
  own note.
- **Failure:** the photo is already saved. Keep it and offer one retry.
- **Known issue:** some features are sentence fragments ("Foreground, extending into the middle
  ground"). Don't use a feature as the spot's title.

### 4.2 Find a saved place (already wired, the Ask box)
- **Call:** `resolveDestination(ctx, query, checkpoints)`: at most 12 checkpoints and 500
  characters.
- **Success:**
  - `Target(id)`: show that memory's photo and ask "Is this the place?" with Confirm and Not it.
  - `AskClarification`: several places fit. Show the candidates and ask for a detail.
  - `NoRecordedMatch`: "I don't see that in your saved places."
- **Next step:** with more than 12 memories, call `TripMemoryIndex.rank(query, memories, limit = 12)`
  first and pass those, instead of only the latest 12 as the ViewModel does now.

### 4.3 Same spot? (camera at a junction)
- **Call:** `compare(ctx, reference = expected spot's saved photo, current = a fresh photo)`
- **Compare only against the spot the recorded route expects next.** That spot is
  `outing.retraceIndex` → the nearest memory. Never compare against every saved spot: on
  look-alike spots the model can't tell them apart.
- **Success:**
  - `SUPPORTS_MATCH`: "Looks like the same spot." Show `matchingFeatures` as the reasons and
    a Confirm button.
  - `INSUFFICIENT_EVIDENCE` or `DIFFERENT_PLACE`: "Not sure this is the same spot." **Show no
    feature reasons** (on non-matches the model lists features that aren't there). Offer: take
    another photo, follow the trail line, or "This is the spot" (user override).
- **Measured:** 0 of 25 wrong "same spot", but only 1 of 3 real matches found. Expect "not
  sure" often; the UX must make that state useful.

### 4.4 Ask about my trip
- **Current product path:** `askAboutTrip(ctx, Question.Text(questionText), memories)` with at most
  the four most recent saved places (`MAX_TRIP_SPOTS`). The product excludes a second embedding
  model, so this UI does not claim semantic ranking or a whole-trip search.
- If the product later selects and ships `TripMemoryIndex`, rank by the typed question first;
  `withinHours` can limit search to the last *h* hours (for example `0.5` for 30 minutes).
- **Success:** `TripAnswer(answer, sourceIds, grounded)`
  - `grounded = true`: show `answer` plus the photos of `sourceIds` (real memory ids), each
    opening that memory.
  - `grounded = false`: show `answer` (for example "I don't see that in your saved places")
    and **no photos as evidence**. This is the not-sure state.
- **Index ahead of time:** call `TripMemoryIndex.index(outing.memories)` after each Save photo,
  so the first question doesn't wait for embedding.
- **Measured answers:**
  - "Where did I leave my water bottle?" → the right spot.
  - "Where is the swimming pool?" → "I don't see that in your saved places."

### 4.5 Ask about a photo
- **Call:** `askAboutPhoto(ctx, ImageRef(...), Question.Text("What kind of tree is this?"))`
- **Success:** `PhotoAnswer(answer, confidence, safetyRefusal)`
  - `confidence` is `HIGH`, `MEDIUM` or `LOW`. Label `LOW` as a guess. Don't present any
    level as certain.
  - `safetyRefusal = true`: show the `answer` text, which is the fixed refusal: "Travis can't
    tell from a photo whether something is safe to eat, drink, touch or approach. Don't rely
    on a photo for that." Don't offer a retry.
- Typed safety questions are refused before the model runs. Spoken ones are caught after the
  model answers.

### 4.6 Reminder from a note
- **Call:** `parseReminder(ctx, Question.Text(noteText))` when the user saves a note at a spot.
- **Success:** `ReminderIntent(remindOnReturn, task)`
  - `remindOnReturn = true`: ask "Remind me on the way back: <task>?" with Add and Dismiss.
    On Add, set that memory's `pickup` to `PickupPhase.WAITING_TO_LEAVE`, the same as the
    existing pickup checkbox. **Never add it silently.**
  - `remindOnReturn = false`: save the note as written.
- **Route reminder-style speech here, not to `askAboutTrip`.** Asked as a trip question,
  "Remind me to close the gate on the way back" gives an awkward answer.

### 4.7 Voice
- Any `Question` can be `Question.Audio(wavBytes)`: 16 kHz, mono, 16-bit WAV, up to about 30 s.
  Gemma listens to it directly; there's no separate speech-to-text.
- **Not built:** microphone recording. Record with `AudioRecord` at 16 kHz mono and wrap the
  bytes in a WAV header. That needs the `RECORD_AUDIO` permission, which isn't in the
  manifest yet.
- **For trip questions by voice:** you can't rank by text. Use `withinHours` or the most
  recent 4 memories as the pool.
- **Choosing the feature:** decide from the button the user pressed (Ask about trip / Ask
  about this photo / Add note), not by guessing from the audio.

### 4.8 Am I going the right way?
- **Call:** `RightWayCheck.check(Input(...))`. It's pure code, so it's instant and never calls
  a model.
  - `trail = outing.points`
  - `targetIndex = outing.retraceIndex`
  - `targetName = nearestMemory?.title`
  - `recentFixes`: the last 30 s of `LiveSensors.fix` values, oldest first. Keep a ring buffer
    in the ViewModel.
  - `fixQuality`: the quality the ViewModel already computes.
  - `compassHeading = LiveSensors.heading.value`
  - `compassReliable = LiveSensors.headingReliable.value`
  - Optional `visual`: the verdict of a `compare` between a live photo and `nearestMemory`'s photo.
  - Optional `stepsSinceLastSpot`: needs a step-counter sensor and the `ACTIVITY_RECOGNITION`
    permission. Not built.
- **Result:** `answer` YES / NO / NOT_SURE, a one-sentence `summary`, and `reasons`, each
  tagged GPS, COMPASS, CAMERA or STEPS.
  - Show the `summary`.
  - List the reasons with their source, so the user can tell measured facts from what the
    camera saw.
  - Don't let any model rewrite the yes/no answer.
- **Rules it applies:**
  - Weak GPS → NOT_SURE.
  - Off the recorded trail by more than max(25 m, 2× GPS accuracy) → NO.
  - Heading within 45° of the next trail point → YES. 120° or more away → NO.
  - A camera match only supports YES. A camera mismatch downgrades YES to NOT_SURE.

### 4.9 Lining-up meter (optional, AR camera)
- Every ~0.5 s while the user aims at the expected spot:
  1. `LocalEmbedder.embedImage(frameJpeg)`, about 0.3 s.
  2. Cosine against that spot's saved-photo vector (`SpotRetriever.cosine`).
  3. `AlignmentMeter.update(score)` → SEARCHING / CLOSE / ALIGNED.
- **Thresholds aren't calibrated.** Same-spot photos scored 0.91–0.94, but different spots
  reached 0.93. Use the meter only as a hint to keep aiming, then run `compare`. Never use it
  as a match.
- EmbeddingGemma and Gemma both run on the NPU. Don't run the meter while a Gemma request is
  in flight.

---

## 5. Native UI integration status

The native UI exposes local photo description, saved-place lookup, trip questions, questions
about an original saved photo, conservative two-photo comparison, and reminder proposals
parsed from a saved note. Results use request/session/trip revision gates and remain proposals.
The UI shows source photos for grounded answers, uses a calm card for safety refusals, hides
comparison reasons on non-matches, and requires an explicit user action to add a reminder or
continue guidance. Explore paths remain visibly labelled samples.

Retrace also exposes the deterministic `RightWayCheck`, which reports YES, NO, or NOT_SURE
with GPS, compass, and optional camera evidence sources. This is still unvalidated on a walk.

Trip questions use at most four recent places because the product excludes an extra embedding
model. Voice input is also excluded from the current product scope. The alignment meter stays
hidden because its thresholds are uncalibrated. Run the app's own flows on the Pixel separately
from benchmark and compilation evidence.

## 6. What's left to build

1. Optional: microphone recording (4.7), only if product scope adds voice input.
2. Optional: lining-up meter (4.9) and step counter; both need product selection and
   calibration/permission work.

Before the demo, tap through each wired feature once on the Pixel in airplane mode, and note
anything that behaves differently from this doc.

---

## 6. Wiring pattern (copy for every new call)

```kotlin
invalidate()                                   // cancels anything running
val request = context(memoryOrPurposeId)       // RequestContext(session, newId, outing.id, outing.revision, …)
gate.begin(request)
inferenceJob = viewModelScope.launch {
    inference.askAboutTrip(request, Question.Text(q), pool).collect { result ->
        if (!gate.accepts(result.context) || mutable.value.outing.revision != request.tripRevision) return@collect
        when (result) {
            is InferenceState.Loading -> mutable.update { it.copy(busy = true) }
            is InferenceState.Success -> if (gate.consume(request)) { /* show result */ }
            is InferenceState.Failure -> { gate.consume(request); /* show result.message */ }
        }
    }
}
```
- Use one `LocalVisionService` for the whole app, which `TravisViewModel` already has, plus one
  `LocalEmbedder` and one `TripMemoryIndex` when you add them. Close each one in `onCleared()`.
- Every request times out after 120 s and fails with `TIMEOUT`. `FailureKind` values:
  `INVALID_RESULT` (offer a retry), `UNAVAILABLE` (model missing, or busy), `TIMEOUT`,
  `CANCELLED`, `ERROR`.
- Gemma and EmbeddingGemma together use about 1.6 GB of app memory on the Pixel.

---

## 7. Known limits

- **Same-spot check:** cautious on look-alike places (found 1 of 3 real matches). It's safe
  (0 of 25 wrong) but not sharp. That's why it compares only against the expected next spot.
- **Embedding scores alone can't say "no match":** made-up places scored as high as real
  ones. Leave the no-match decision to Gemma (`grounded = false`, `NoRecordedMatch`).
- **Trip answers can be confidently wrong with a citation:** in the Mac eval, "Which spot had
  the red mailbox?" came back grounded, citing a spot that has no mailbox. Always show the
  cited photo so the user can check it (rule 1).
- **Tree species answers are hedged guesses:** "possibly a pine or fir". Show them as guesses.
- **Small test set:** all accuracy numbers come from 8 photos at one garden plus a few
  sample images.
- **Not exercised in the app:** NPU loading in the app's own screens (only the benchmark ran
  it), and `RightWayCheck` on a real walk.
