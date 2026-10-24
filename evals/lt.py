"""Shared harness: the same LiteRT-LM 0.18.0 runtime and Gemma 4 E2B file the Pixel app uses,
run on the Mac. One loaded engine, one conversation per request, greedy sampling, thinking
off, optional JSON-schema-constrained output (ResponseFormat), as in LocalVisionService.kt."""
import json, os, time
import litert_lm as L

MODELS = os.path.expanduser("~/gemma-hackathon/models")
GEMMA = f"{MODELS}/litertlm-gemma4-E2B/gemma-4-E2B-it.litertlm"
EMBED = f"{MODELS}/litertlm-embeddinggemma2-740m/embeddinggemma-2-740m.litertlm"
CACHE = os.path.expanduser("~/.cache/travis-evals"); os.makedirs(CACHE, exist_ok=True)
L.set_min_log_severity(L.LogSeverity.ERROR) if hasattr(L, "LogSeverity") else None

_engine = None
def engine():
    global _engine
    if _engine is None:
        b = L.Backend.GPU() if callable(getattr(L.Backend, "GPU", None)) else L.Backend.GPU
        _engine = L.Engine(GEMMA, backend=b, vision_backend=b, audio_backend=L.Backend.CPU() if callable(L.Backend.CPU) else L.Backend.CPU,
                           max_num_tokens=4096, max_num_images=4, cache_dir=CACHE)
    return _engine

def _text(msg):
    if isinstance(msg, str): return msg
    for attr in ("text",):
        if hasattr(msg, attr) and isinstance(getattr(msg, attr), str): return getattr(msg, attr)
    d = msg if isinstance(msg, dict) else getattr(msg, "__dict__", {})
    c = d.get("content") if isinstance(d, dict) else None
    if isinstance(c, list): return "".join(p.get("text", "") if isinstance(p, dict) else str(getattr(p, "text", "")) for p in c)
    if isinstance(c, str): return c
    return str(msg)

def ask(parts, schema=None, max_tokens=384):
    """parts: list of str or ('image', path) or ('audio', path). Returns (raw_text, ms)."""
    items = []
    for p in parts:
        if isinstance(p, tuple) and p[0] == "image": items.append(L.Content.ImageFile(p[1]))
        elif isinstance(p, tuple) and p[0] == "audio": items.append(L.Content.AudioFile(p[1]))
        else: items.append(L.Content.Text(p))
    conv = engine().create_conversation(
        sampler_config=L.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0, seed=0),
        thinking_config=L.ThinkingConfig(enable_thinking=False), max_output_tokens=max_tokens,
        constrained_decoding_config=L.ConstrainedDecodingConfig(enable=True, provider=L.LiteRtLmConstraintProviderType.LL_GUIDANCE) if schema else None)
    t = time.time()
    try:
        kw = {"response_format": L.ResponseFormat.json(schema)} if schema else {}
        msg = conv.send_message(L.Contents.of(*items) if hasattr(L.Contents, "of") else L.Contents(items), **kw)
        return _text(msg), int((time.time() - t) * 1000)
    finally:
        conv.close()

def strict_json(raw, keys):
    """Mirror of the app's strict validator: whole reply must be JSON with exactly these keys."""
    try:
        v = json.loads(raw)
        return v if isinstance(v, dict) and set(v) == set(keys) else None
    except Exception:
        return None
