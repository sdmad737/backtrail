# Travis evals: stock Gemma vs the Travis pipeline

**Same model file both ways:** `gemma-4-E2B-it.litertlm`, plus `embeddinggemma-2-740m` for
retrieval. **Same runtime as the Pixel app:** LiteRT-LM 0.18.0, run on a Mac (Apple M5 Pro
GPU) on 2026-10-24.

The two setups being compared:
- **Stock Gemma:** a plain, generic prompt with no output constraint.
- **Travis pipeline:** the app's own prompts, JSON-schema-forced decoding, strict
  validators, EmbeddingGemma retrieval and code-side guards, copied verbatim from
  `app/src/main/java/com/backtrail/app/ai/`.

No model weights were changed. Mac latencies were measured with 4 suites sharing the GPU;
for phone speed see `docs/INFERENCE-LOG.md`. Per-suite details are in `results/*.md`.

## Headline

| What we measured | Stock Gemma | Travis pipeline | Set |
|---|---|---|---|
| Photo descriptions the app can use | 0 / 11 | **11 / 11** | 11 photos |
| "Same spot?" answers the app can use | 0 / 55 | **55 / 55** | 55 photo pairs |
| Wrong "same spot" on different places | n/a, no usable answers | **0 / 51** | 51 different-place pairs |
| Real same-spot pairs found | n/a | 2 / 4 | 4 same-spot pairs |
| Risky safety verdict ("not toxic", "not safe to climb"…) | 4 / 10 | **0 / 10** | 10 safety questions on photos |
| Normal photo questions wrongly refused | 0 / 10 | 0 / 10 | 10 normal questions |
| Trip questions: right spot | 8 / 10 | **10 / 10** | 10 answerable questions |
| Trip questions: honest "not found" | **5 / 6** | 4 / 6 | 6 unanswerable questions |
| Trip answers with citations the app can check | 0 / 16 | **16 / 16** | 16 questions |
| Reminder notes classified correctly | **19 / 20** | 18 / 20 | 20 notes |

## What the numbers say

1. **Usable output.** Stock Gemma wrapped every structured answer in a Markdown code block,
   so 0 of 66 photo answers passed the app's strict check. Forced JSON took that to 66 of 66.
   This is the bug we hit on the phone this morning.
2. **Safety.** Stock Gemma gave a verdict on 4 of 10 "is it safe" questions (hand-read). The
   pipeline gave none, and refused no normal questions.
3. **Same spot?** The shipped strict prompt made 0 wrong "same spot" calls in 51 different-place
   pairs. The lenient prompt made 15. The cost is recall: 2 of 4 real matches found. Travis
   ships the strict prompt and compares only against the spot the route expects next,
   because a wrong "same spot" misleads a walker.
4. **Trip questions.** Retrieval put the right spot in the top 4 every time (10 of 10), and the
   pipeline answered 10 of 10 answerable questions with a checkable citation. Stock Gemma was
   slightly better at saying "not found" (5 of 6 vs 4 of 6). Once, the pipeline cited a
   wrong spot for a red mailbox that doesn't exist, which is why the app always shows the
   cited photo.
5. **Reminders.** No improvement: 18 of 20 vs 19 of 20. Not claimed.

## Matches the Pixel?

On the 28 garden pairs run on the Pixel NPU:

| | Pixel NPU | Mac GPU |
|---|---|---|
| Strict prompt | 0 / 25 wrong, 1 / 3 found | 0 / 25 wrong, 2 / 3 found |
| Lenient prompt | 9 / 25 wrong, 3 / 3 found | 15 / 25 wrong, 3 / 3 found |

Same direction on both. The exact counts differ, because the backends differ.

## Caveats

- **Small sets:** 11 photos, 55 pairs, 16 trip questions, 20 safety questions, 20 notes. One
  garden plus three sample images. These are demo-scale results, not a benchmark.
- **Scoring:** text answers are scored by simple rules, with hand checks where the rules
  were ambiguous. The rules and every disagreement are in each suite's `.md` file.
- **Safety guard coverage:** the question guard catches safety questions by keyword. No
  question outside that keyword list was tested. With the guard bypassed, two verdict
  phrasings slipped past the answer check; the check has since been extended to catch them
  (`SafetyTopics.kt`, unit-tested).
- **Sign reading:** no setup read the "Lalibela" street sign on the Mac. Only the Pixel's
  Tensor G6 build did.
