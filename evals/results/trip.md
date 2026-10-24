# Trip Q&A eval: stock Gemma vs Travis pipeline

Same model file for both: `gemma-4-E2B-it.litertlm` (LiteRT-LM 0.18.0, Mac GPU, greedy, thinking off). Retrieval: `embeddinggemma-2-740m.litertlm`, 256-d, normalized, **image+text** spot vectors (spot score = max cosine over photo and text vector, as SpotRetriever). Synthetic trip: 8 garden photos, titles/descriptions from the app's own DESCRIBE_PROMPT + DESCRIPTION_SCHEMA, 4 with notes, 10 min apart. 16 questions: 10 answerable, 6 unanswerable. n is small; treat as a demo, not a benchmark.

| | Baseline (all 8 memories as text, plain prompt) | Ours (EmbeddingGemma top-4 + TRIP_PROMPT + photos + tripSchema + validator) |
|---|---|---|
| Answerable, correct | 9/10 | 10/10 |
| Unanswerable, abstained | 3/6 | 4/6 |
| Unanswerable, made-up answer | 3/6 | 2/6 |
| Machine-checkable output | free text (citations not checkable) | 16/16 valid JSON, ids checked |
| Retrieval recall@4 (answerable) | n/a (sees all 8) | 10/10 |
| Latency per question, median / max | 432 / 1432 ms | 2310 / 2883 ms (incl. query embed) |

Latencies are Mac GPU **under 4-way contention** (three other evals on the same GPU); ours sends 4 photos per question, baseline sends none. Indexing the 8 spots: 2474 ms.

## Scoring rules

- **Ours, answerable**: correct iff output passes the AssistantValidator rule (strict JSON, exact keys, ids among s1..s4 shown) AND grounded (model grounded=true and ≥1 citation) AND a cited spot ∈ gold set.
- **Baseline, answerable**: correct iff answer text contains (case-insensitive) a gold spot's full title, or "Memory k" for a gold spot's index, or the distinctive gold-note phrase (fire bowl / stone steps / gravel / close the gate).
- **Unanswerable**: ours correct iff valid and grounded=false. Baseline correct iff text matches an abstention phrase (don't|doesn't|do not|does not|not see|no record|not mentioned|no information|can't find|cannot find|not in); otherwise counted as a made-up answer.
- Baseline string matching is lenient on phrasing but can miss a correct paraphrase of a title; see per-question table and manual notes below.

## Manual spot-check (read every answer)

| | Baseline | Ours |
|---|---|---|
| Answerable correct (manual) | 8/10 | 10/10 |
| Unanswerable abstained (manual) | 5/6 | 4/6 |
| Unanswerable made up (manual) | 1/6 | 2/6 |

- Baseline "swimming pool" and "phone charger" answers say "no mention": real abstentions the specified regex misses, so the automatic 3/6 understates baseline; manual 5/6.
- Baseline "redwood trees" passes the string rule only because it lists Memories 1,4,5,6,7,8 (6 of 8 spots) and says redwoods aren't mentioned; manually not credited.
- "Waterfall": both sides point to the pergola water feature Gemma itself described ("Water feature in background"); scored as made up on both sides, borderline.
- Ours "red mailbox": a clear hallucination ("Spot s4 had the red mailbox", grounded=true, cites redwood-b). The validator only checks that the ids were shown; it can't catch a false claim with a citation.
- "Where was the pergola": ours also cited pine-a/pine-b; the pergola is visible overhead in those photos, so that's fine.

**Honest reading:** the pipeline is more accurate on answerable questions (10/10 vs 8/10 manual) and every answer comes with machine-checked spot ids the app can show as photos (16/16 valid JSON); the baseline's answers can't be linked to a spot reliably. On abstention the baseline does as well or better here (5/6 vs 4/6 manual). The pipeline costs about 5x the latency because it sends 4 photos per question.

## Three examples each side

1. *Where might my sunglasses be?* Baseline: "…you might have lost your sunglasses in the **gravel area** in Memory 4." Ours: cites pine-b, grounded. Both correct.
2. *Where were the redwood trees?* Baseline lists 6 of 8 memories and says redwoods aren't mentioned. Ours: cites redwood-b + redwood-a (the photos show the trunks), grounded.
3. *Which spot had the red mailbox?* Baseline: "I do not have any memory that mentions a red mailbox." Ours: "Spot s4 had the red mailbox." (hallucinated; cited redwood-b).

## Per question

| Question | Gold | Baseline | Ours top-4 | Ours cited / grounded | Ours |
|---|---|---|---|---|---|
| Where did I leave my water bottle? | pergola-a | ✓ | pergola-a, redwood-a, pine-b, gatepath-a | pergola-a / True | ✓ |
| Where did I eat lunch? | redwood-a | ✓ | redwood-a, gateback-a, pine-b, pergola-a | redwood-a / True | ✓ |
| Where might my sunglasses be? | pine-b | ✓ | pine-b, pergola-a, gatepath-a, gateback-a | pine-b / True | ✓ |
| What did I want to remember to do on the way back? | gatepath-a | ✓ | gatepath-a, gateback-a, redwood-a, pine-b | gatepath-a / True | ✓ |
| Where was the gate? | gatepath-a, gateback-a | ✓ | gateback-a, gatepath-a, pine-b, redwood-a | gateback-a, gatepath-a / True | ✓ |
| Where did I see the pond? | pergola-a, pergola-b | ✓ | pergola-b, redwood-a, pine-b, pergola-a | pergola-b, pergola-a, pine-b / True | ✓ |
| Where was the pergola? | pergola-a, pergola-b | ✓ | pine-b, pergola-a, pergola-b, pine-a | pine-b, pergola-a, pergola-b, pine-a / True | ✓ |
| Where were the redwood trees? | redwood-a, redwood-b | ✓ | redwood-b, redwood-a, pine-b, pine-a | redwood-b, redwood-a / True | ✓ |
| Where were the pine trees? | pine-a, pine-b | ✗ | pine-b, redwood-a, pine-a, redwood-b | pine-b, pine-a / True | ✓ |
| Where did I sit down for a rest? | redwood-a | ✓ | redwood-a, pine-b, pergola-a, pergola-b | redwood-a / True | ✓ |
| Where is the swimming pool? | (none) | ✗ | gatepath-a, pine-b, gateback-a, pergola-a | - / False | ✓ |
| Where did I park the car? | (none) | ✓ | pine-b, pine-a, redwood-a, gateback-a | - / False | ✓ |
| Which spot had the red mailbox? | (none) | ✓ | redwood-a, gatepath-a, gateback-a, redwood-b | redwood-b / True | ✗ |
| Where did I buy coffee? | (none) | ✓ | gatepath-a, gateback-a, pergola-a, redwood-a | - / False | ✓ |
| Where did I see the waterfall? | (none) | ✗ | redwood-a, pergola-a, pine-b, pergola-b | pergola-a, pergola-b / True | ✗ |
| Where did I leave my phone charger? | (none) | ✗ | redwood-a, gateback-a, pine-b, gatepath-a | - / False | ✓ |

## Memories (Gemma's own describe output)

- **pergola-a** — Wooden pergola structure over patio — Wooden pergola structure · Patio paving stones · Water feature in background · Large tree trunk in background · Green foliage in background — note: Left my water bottle on the fire bowl
- **pergola-b** — Wooden pergola structure — Wooden pergola structure: Top and sides · Water feature: Middle and lower · Stone retaining wall: Lower left · Paved patio: Foreground and lower · Planter/Fire pit: Foreground right · Dense green foli
- **pine-a** — Wooden pergola structure overhead — Wooden pergola structure overhead - Top center · Dense evergreen tree - Centre · Reddish-brown foliage - Right background · Stone paving pattern - Foreground left · Dark gravel ground cover - Foregrou
- **pine-b** — Wooden pergola structure and large evergreen tree — Wooden pergola structure · Foreground gravel area · Large evergreen tree centre · Stone path right foreground · Stone paving right foreground · Green foliage background left · Stone border left foregr — note: Lost my sunglasses somewhere in the gravel here
- **redwood-a** — Large tree trunk and stairs — Large tree trunk on the left · White stairs on the left · Dense green foliage in the background · Manicured green bushes in the middle ground · Stone and pebble garden in the foreground · Paved walkwa — note: Sat on the stone steps for lunch
- **redwood-b** — Large tree trunk and manicured shrubs — Massive tree trunk left · Dense green foliage right · Manicured green bushes centre · Gravel ground foreground · Large reddish-brown rock foreground · Stone sculpture background center
- **gatepath-a** — Stone walkway with geometric pattern — Geometric stone path foreground · Stone steps right side · Large tree canopy left · Stone wall background left · Metal gate centre background · Black lamppost right foreground — note: Remind me to close the gate on the way back
- **gateback-a** — Stone walkway and metal gate — Stone walkway · Foreground path · Metal gate structure · Stone steps · Building facade · Trees and foliage · Overhead canopy structure

## Raw answers (all)

**Where did I leave my water bottle?**
- Baseline: 'You left your water bottle on the fire bowl.'
- Ours: `{
"answer":
"You left your water bottle on the fire bowl at the wooden pergola structure.",
"sourceIds":
[
"s1"
],
"grounded":
true
}`

**Where did I eat lunch?**
- Baseline: 'You sat on the stone steps for lunch.'
- Ours: `{
"answer":
"You sat on the stone steps for lunch at spot s1.",
"sourceIds":
[
"s1"
],
"grounded":
true
}`

**Where might my sunglasses be?**
- Baseline: 'Based on the memories, you might have lost your sunglasses in the **gravel area** in Memory 4.'
- Ours: `{
"answer":
"You might have lost your sunglasses in the gravel area at spot s1.",
"sourceIds":
[
"s1"
],
"grounded":
true
}`

**What did I want to remember to do on the way back?**
- Baseline: 'You wanted to remember to **close the gate on the way back**. (Memory 7)'
- Ours: `{
"answer":
"You should remember to close the gate on the way back.",
"sourceIds":
[
"s1"
],
"grounded":true
}`

**Where was the gate?**
- Baseline: 'The gate was in the **centre background** (Memory 7) and there is a **metal gate structure** mentioned in relation to a stone walkway (Memory 8).'
- Ours: `{
"answer":
"The metal gate was seen at spot s1 and spot s2.",
"sourceIds":
[
"s1",
"s2"
],
"grounded":
true
}`

**Where did I see the pond?**
- Baseline: 'Based on the memories, you saw a **water feature** in the background (Memory 1) and the **water feature** is in the middle and lower part of the wooden pergola structure (Memory 2).'
- Ours: `{
"answer":
"You can see the pond under the wooden pergola structure at spots s1, s4, and s3.",
"sourceIds":
[
"s1",
"s4",
"s3"
],
"grounded":
true
}`

**Where was the pergola?**
- Baseline: 'The pergola was located **over a patio**.\n\nThis is suggested by Memory 1 ("Wooden pergola structure over patio") and Memory 2, which mentions a "Paved patio: Foreground and lower".'
- Ours: `{
"answer":
"The pergola was seen in spots s1, s2, s3, and s4.",
"sourceIds":
[
"s1",
"s2",
"s3",
"s4"
],
"grounded":
true
}`

**Where were the redwood trees?**
- Baseline: 'Based on the memories provided, there are mentions of a **large tree trunk** and **dense green foliage** in various locations.\n\nHere are the specific locations mentioned:\n\n* **Memory 1:** Large tree trunk in the background.\n* **Memory 4:** Large evergreen tree centre.\n* **Memory 5:** Large tree trunk on the left.\n* **Memory 6:** Massive tree trunk left.\n* **Memory 7:** Large tree canopy left.\n* **Memory 8:** Trees and foliage mentioned in relation to the stone walkway and overhead canopy structu'
- Ours: `{
"answer":
"The redwood trees were seen at spot s1 and spot s2.",
"sourceIds":["s1",
"s2"],
"grounded":true
}`

**Where were the pine trees?**
- Baseline: 'Based on the memories provided, there are mentions of a **large tree trunk** and **dense green foliage** in the background, and a **large evergreen tree** in the center of some descriptions.\n\nHowever, there is no specific mention of **pine trees**.'
- Ours: `{
"answer":
"The pine trees are visible under the wooden pergola structure at spot s1 and s3.",
"sourceIds":["s1",
"s3"],
"grounded":true
}`

**Where did I sit down for a rest?**
- Baseline: 'Based on the memories, you sat on the **stone steps** for lunch (Memory 5).'
- Ours: `{
"answer":
"You sat on the stone steps for lunch.",
"sourceIds":
[
"s1"
],
"grounded":
true
}`

**Where is the swimming pool?**
- Baseline: 'Based on the provided memories, there is **no mention of a swimming pool**.'
- Ours: `{
"answer":
"I don't see that in their saved places.",
"sourceIds":
[]
,
"grounded":
false
}`

**Where did I park the car?**
- Baseline: 'Based on the memories provided, there is **no information** about where you parked your car.'
- Ours: `{
"answer":
"I don't see that in their saved places.",
"sourceIds":
[],
"grounded":false
}`

**Which spot had the red mailbox?**
- Baseline: 'I do not have any memory that mentions a red mailbox.'
- Ours: `{
"answer":
"Spot s4 had the red mailbox.",
"sourceIds":
[
"s4"
],
"grounded":
true
}`

**Where did I buy coffee?**
- Baseline: 'Based on the memories provided, there is no information about where you bought coffee.'
- Ours: `{
"answer":
"I don't see that in their saved places.",
"sourceIds":
[]
,
"grounded":
false
}`

**Where did I see the waterfall?**
- Baseline: 'Based on the memories, you saw a **water feature** in the background of the wooden pergola structure (Memory 1) and the water feature is also mentioned in relation to the structure (Memory 2).'
- Ours: `{
"answer":
"You saw the waterfall under the wooden pergola structure at spot s2 and spot s4.",
"sourceIds":
[
"s2",
"s4"
],
"grounded":
true
}`

**Where did I leave my phone charger?**
- Baseline: 'Based on the memories provided, there is no mention of where you left your phone charger.'
- Ours: `{
"answer":
"I don't see that in their saved places.",
"sourceIds":
[]
,
"grounded":
false
}`
