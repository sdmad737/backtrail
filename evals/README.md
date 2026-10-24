# Travis evals (Mac)

Same model files and the same LiteRT-LM 0.18.0 runtime as the Pixel app, run on a Mac with
the Python package `litert-lm==0.18.0`. Each suite compares:

- **Baseline**: stock Gemma 4 E2B with a plain, generic prompt and no output constraint.
- **Travis pipeline**: the app's own prompts, JSON-schema-constrained decoding, strict
  validators, EmbeddingGemma retrieval and code-side guards, copied from `app/src/main/java/com/backtrail/app/ai/`.

No model weights were changed. "Our tuning" here means the pipeline around the stock model.

## Run

```sh
python3 -m venv .venv && .venv/bin/pip install litert-lm==0.18.0
cd evals
../.venv/bin/python eval_describe.py
../.venv/bin/python eval_compare.py
../.venv/bin/python eval_trip.py
../.venv/bin/python eval_safety_reminder.py
```

Models are read from `~/gemma-hackathon/models/` (see `lt.py`). Photos are read from a local
folder outside the repo. The garden photos are private, so the per-item outputs in
`results/raw/` are git-ignored. Summaries are in `results/*.md` and `results/*.json`, and
the combined table is in `RESULTS.md`.

Caveats: these are small sets (11 photos, tens of questions). Mac GPU latencies aren't the
phone's: see `docs/INFERENCE-LOG.md` for Pixel numbers.
