# Is this the same spot? (compare suite)

Same gemma-4-E2B-it.litertlm, LiteRT-LM 0.18.0, Mac GPU, greedy, thinking off. 55 of 55 photo pairs ran (11 photos; same spot = same name before the last dash). Latency: Mac GPU, measured under 4-way contention (three other eval agents on the same GPU).

| Condition | Valid (strict) | Same spot found | Same spot missed | Different pairs: FALSE "same spot" | Correct DIFFERENT_PLACE | Unsure | Invalid | SUPPORTS_MATCH precision | Median ms |
|---|---|---|---|---|---|---|---|---|---|
| Stock Gemma, plain prompt, no schema | 0/55 (0.0%) | 0/4 | 4/4 | 0/51 (0.0%) | 0/51 | 0/51 | 51/51 | n/a (0 matches) | 1606 |
| Travis v1 prompt + schema | 55/55 (100.0%) | 4/4 | 0/4 | 15/51 (29.4%) | 2/51 | 34/51 | 0/51 | 4/19 = 0.21 | 1316 |
| Travis v2 prompt + schema (shipped) | 55/55 (100.0%) | 2/4 | 2/4 | 0/51 (0.0%) | 51/51 | 0/51 | 0/51 | 2/2 = 1.00 | 1359 |

Invalid replies count as no verdict (the app shows "The model did not return a valid comparison"). baseline = images then the plain prompt, no schema, max 384 tokens; v1/v2 = app layout + ASSESSMENT_SCHEMA, max 320 tokens.

Baseline, diagnostic only (not the app's rule): every one of the 55 stock replies was wrapped in a ```json code fence, so the strict validator rejects all of them. If the first verdict word is pulled out of the reply anyway, stock Gemma would have said SUPPORTS_MATCH on 4/4 same-spot pairs and 4/51 different pairs (40 DIFFERENT_PLACE, 7 INSUFFICIENT_EVIDENCE), so precision 4/8.

Garden-only subset (28 pairs: 3 same spot, 25 different), the set used on the Pixel NPU: v2 had 0/25 false "same spot" and found 2/3 (pine, pergola; redwood came back DIFFERENT_PLACE); v1 had 15/25 false and found 3/3. The Pixel NPU build gave v2 0/25 false, 1/3 found and v1 9/25 false, 3/3 found. The Mac reproduces the direction (v2 has no false matches, v1 has many) but not the exact counts. Here v1 is worse (15 false, not 9) and v2 found one more same-spot pair (2, not 1). The Mac GPU and Pixel NPU are different backends, so greedy outputs can differ.

**Interpretation.** On this set, the shipped v2 prompt never called two different places "the same spot" (0/51). The price is recall: it missed 2 of 4 real same-spot pairs (junction and redwood both came back DIFFERENT_PLACE, not INSUFFICIENT_EVIDENCE). The v1 prompt found all 4, but 15 of 51 different pairs also got "same spot", so only 4 of its 19 matches were right (0.21). Stock Gemma with a plain prompt never produced a reply the app could use, because every reply was in a code fence. The product uses the conservative v2 prompt because a false "same spot" sends a walker down the wrong path, while a miss only costs one more look. This is a small set: 11 photos, 4 same-spot pairs.
