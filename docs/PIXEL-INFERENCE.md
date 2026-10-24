# Pixel inference — measurement protocol and results

Owner: inference lane (`ai/`). This file is the **only** place device numbers are reported.

Rule, from `AGENTS.md` and `docs/CONTRACTS.txt`: compilation, emulator operation and real
device measurement are different kinds of evidence. Never substitute Mac or emulator numbers
for Pixel results. Keep three milestones distinct:

1. Downloaded on Mac — done (model verified bit-exact, see `DECISIONS.txt`)
2. Installed on Pixel — pending
3. Proven offline on Pixel — pending

## Device record (fill in on arrival)

| Field | Value | How |
|---|---|---|
| Model | | `adb -s SERIAL shell getprop ro.product.model` |
| Android version | | `adb -s SERIAL shell getprop ro.build.version.release` |
| SoC | | `adb -s SERIAL shell getprop ro.soc.model` |
| Total RAM | | `adb -s SERIAL shell cat /proc/meminfo \| head -1` |
| Free storage | | `adb -s SERIAL shell df -h /sdcard` |
| APK commit | | `git rev-parse --short HEAD` at build time |
| Model SHA-256 prefix | `181938105e` | verified on Mac and after device import |
| Backend | | GPU unless flipped; record what actually ran |
| Shader cache state | cold / warm | first run after install is cold |

Needs ~2.8 GB free for the import (2.59 GB source + app-private copy headroom);
`ModelStore` enforces size + 128 MB.

## Order of operations when the phone arrives

Import first — it is the longest serial step, has no resume, and backgrounding the app
restarts it from zero.

1. Free-space check.
2. `adb -s SERIAL push` the model to `/sdcard/Download/`.
3. Integrator installs the APK (only the integrator installs the shared app ID).
4. Import in-app, **screen on, app foregrounded**, until checksum verification completes.
5. Airplane mode on, Wi-Fi explicitly off. Confirm no connectivity before any inference.
6. Warm-up run — populates the shader cache. Never clear app data afterward.

## Acceptance matrix (required evidence)

| # | Case | Expected | Observed | Pass |
|---|---|---|---|---|
| 1 | Real new camera photo, describe | valid bounded JSON → label + features | | |
| 2 | Three saved memories with notes | all three retrievable | | |
| 3 | Paraphrase 1 | correct landmark | | |
| 4 | Paraphrase 2 | correct landmark | | |
| 5 | Paraphrase 3 | correct landmark | | |
| 6 | Unrelated query | `NO_RECORDED_MATCH` | | |
| 7 | Cancel during engine init | honest busy/cancelled state, no crash | | |
| 8 | Cancel during generate | `CANCELLED`, no state change | | |
| 9 | Model file renamed away | `UNAVAILABLE`, no crash | | |
| 10 | Unclear / low-information photo | visible unclear state, not invention | | |
| 11 | App restart between save and ask | saved descriptions survive | | |

Cases 6 and 7 are the ones most likely to be skipped under time pressure, and they are the
two a judge is most likely to ask about. Do not skip them.

## Timing runs

Five runs minimum, batched, device plugged in, after a cooldown. Report **count and
min–max**, never a single number and never a mean presented as precision.

| Run | initMs | generateMs | validateMs | totalMs | backend | cache |
|---|---|---|---|---|---|---|
| 1 | | | | | | cold |
| 2 | | | | | | warm |
| 3 | | | | | | warm |
| 4 | | | | | | warm |
| 5 | | | | | | warm |

With a per-request engine, cold init is part of **every** request, so `initMs` and `totalMs`
are only separable because instrumentation splits them. State the thermal condition and
whether the device was plugged in.

## Demo script — "backtrack me home"

The spine. Radios off for the whole demo.

1. Show airplane mode and Wi-Fi off. State plainly: the model file is on the phone, nothing
   is fetched, no laptop is involved.
2. Walk out: save two or three landmarks, each with a photo **and a short note**. The note
   matters — "where did I leave my jacket?" cannot match the visual features of a junction.
3. Ask in natural language. Show the proposed landmark with its saved photo, and confirm it.
4. Ask something unrelated. Show `NO_RECORDED_MATCH` — refusing to guess is the feature.
5. State the measured numbers from the table above, with backend and run count.

Say out loud what it does not do: it does not claim to know where you are, a visual match is
evidence rather than a position fix, and two-image comparison is not implemented. Disclose
that a lexical gate runs before the model rather than implying everything is Gemma.

Do not promise the Match-my-view / Look screen — `compare()` returns `UNAVAILABLE`.

## Logging discipline

One tagged line per request: phase timings, backend, model SHA prefix, APK commit, run
index. Never image paths, note text, query text or model output — those are the user's
private route data.
