# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy", "sentence-transformers>=5.1", "transformers>=4.57", "torch", "torchvision", "pillow", "turbovec>=1.1"]
# ///
"""
Strategy research on YOUR OWN copy of NSE daily data, searched by meaning with EmbeddingGemma 2.

  python3 tools/market/fetch.py --from 2016-01-01        # first: your own copy of the data
  uv run tools/research/research.py build --bhav ~/.khayali/bhav --out ~/.khayali/research
  uv run tools/research/research.py ask "breakout to a new high on very heavy volume" --out ~/.khayali/research

build: turns every liquid stock's month-end situation into a plain sentence (trend, momentum,
       distance from high, volume, volatility), embeds the sentences with google/embeddinggemma-2
       (downloaded by this Mac from Hugging Face into its own cache), and stores the vectors with
       what happened over the following month.
ask:   embeds your question the same way and finds the closest past situations, then reports how
       those situations went next (median, hit rate) against the market. Research, not advice.
export: writes the bank as docs/knowledge/screens-v1.kb (rules, names, links, int8 vectors; no descriptions),
        published on the project site; the app downloads it on demand and checks its sha256.
bank:  embeds a local knowledge bank of stock screens you collected (name, description, rules).
find:  searches that bank by meaning: "cheap quality companies with low debt" -> the closest screens.

Nothing is uploaded and nothing here is redistributable: the data and the index stay on this Mac.
The situations index uses turbovec (TurboQuant, 4-bit) for candidates, re-ranked exactly. The small
screens bank is searched exactly; `export` writes it into the app as int8 vectors.
"""
import argparse, datetime as dt, glob, io, json, os, sys, zipfile
import numpy as np

MODEL = "google/embeddinggemma-2"
DIM = 768

def read_day(path):
    with zipfile.ZipFile(path) as z:
        text = z.read(z.namelist()[0]).decode("utf-8", "replace")
    lines = [l.rstrip("\r") for l in text.splitlines() if l.strip()]
    h = [c.strip().upper() for c in lines[0].split(",")]
    udiff = "TCKRSYMB" in h
    col = lambda *names: next((h.index(n) for n in names if n in h), -1)
    cs, cser, cc, cp, cv, cval, cisin, ctp = col("TCKRSYMB", "SYMBOL"), col("SCTYSRS", "SERIES"), col("CLSPRIC", "CLOSE"), col("PRVSCLSGPRIC", "PREVCLOSE"), \
        col("TTLTRADGVOL", "TOTTRDQTY"), col("TTLTRFVAL", "TOTTRDVAL"), col("ISIN"), col("FININSTRMTP")
    out = {}
    for l in lines[1:]:
        f = [x.strip() for x in l.split(",")]
        if f[cser] not in ("EQ", "BE", "BZ"): continue
        if udiff and f[ctp] != "STK": continue
        isin = f[cisin] if cisin >= 0 else ""
        if isin.startswith("INF"): continue
        try: c, p, v, val = float(f[cc]), float(f[cp]), float(f[cv]), float(f[cval])
        except ValueError: continue
        if c > 0 and p > 0: out[f[cs]] = (c / p - 1, v, val)
    return out

def build(a):
    files = sorted(glob.glob(os.path.join(os.path.expanduser(a.bhav), "*.csv.zip")))
    if len(files) < 300: sys.exit("Need at least ~300 trading days in --bhav (run tools/market/fetch.py first).")
    dates = [dt.datetime.strptime(os.path.basename(f)[:8], "%Y%m%d").date() for f in files]
    days = [read_day(f) for f in files]
    syms = sorted({s for d in days for s in d}); ix = {s: i for i, s in enumerate(syms)}
    n, m = len(days), len(syms)
    ret = np.full((m, n), np.nan, np.float32); vol = np.full((m, n), np.nan, np.float32); val = np.full((m, n), np.nan, np.float32)
    for t, d in enumerate(days):
        for s, (r, v, x) in d.items(): ret[ix[s], t] = r; vol[ix[s], t] = v; val[ix[s], t] = x
    px = np.nancumsum(np.log1p(np.nan_to_num(ret)), axis=1)          # log price chained from adjusted previous close
    ends = [t for t in range(n) if t == n - 1 or dates[t + 1].month != dates[t].month]
    texts, meta = [], []
    for k, t in enumerate(ends):
        if t < 252 or k + 1 >= len(ends): continue
        nxt = ends[k + 1]
        liquid = np.nanmedian(val[:, t - 59:t + 1], axis=1) >= 5e7
        mkt_next = np.nanmean(np.where(liquid, px[:, nxt] - px[:, t], np.nan))
        for s in np.where(liquid)[0]:
            if np.isnan(ret[s, t]): continue
            p = px[s]
            r1m, r3m, r12 = p[t] - p[t - 21], p[t] - p[t - 63], p[t - 21] - p[t - 252]
            hi = np.max(p[t - 252:t + 1]); lo = np.min(p[t - 252:t + 1])
            ma50, ma200 = np.mean(p[t - 49:t + 1]), np.mean(p[t - 199:t + 1])
            v20 = np.nanmedian(vol[s, t - 80:t - 20]); vnow = np.nanmean(vol[s, t - 4:t + 1])
            sig = np.nanstd(ret[s, t - 59:t + 1]) * np.sqrt(252)
            vr = vnow / v20 if v20 and v20 > 0 else 1.0
            trend = "in a long uptrend" if p[t] > ma50 > ma200 else "in a long downtrend" if p[t] < ma50 < ma200 else "trading sideways"
            texts.append(
                f"A stock {trend}; up {np.expm1(r1m)*100:.0f}% over the last month and {np.expm1(r3m)*100:.0f}% over three months, "
                f"{np.expm1(r12)*100:.0f}% over the year before that; {abs(np.expm1(p[t]-hi))*100:.0f}% below its 52-week high and "
                f"{np.expm1(p[t]-lo)*100:.0f}% above its low; recent volume {vr:.1f} times normal; annual volatility {sig*100:.0f}%."
                .replace("up -", "down "))
            meta.append({"symbol": syms[s], "date": str(dates[t]), "next": float(np.expm1(p[nxt] - p[t])), "market": float(np.expm1(mkt_next))})
    print(f"{len(texts)} situations from {dates[0]} to {dates[-1]}; embedding with {MODEL}…", flush=True)
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device=a.device)
    vecs = model.encode_document(texts, batch_size=64, show_progress_bar=True, normalize_embeddings=True, truncate_dim=a.dim).astype(np.float32)
    out = os.path.expanduser(a.out); os.makedirs(out, exist_ok=True)
    np.save(os.path.join(out, "vectors.npy"), vecs)
    json.dump({"model": MODEL, "dim": a.dim, "texts": texts, "meta": meta}, open(os.path.join(out, "situations.json"), "w"))
    # turbovec (TurboQuant, 4-bit) pays off at this size: ~3x smaller than float32, ~0.9 recall@10, then exact re-rank
    import turbovec
    idx = turbovec.TurboQuantIndex(dim=a.dim, bit_width=4); idx.add(vecs); idx.write(os.path.join(out, "situations.tv"))
    print(f"turbovec 4-bit index written ({len(vecs)} vectors)")
    print(f"done: {out}")

def ask(a):
    out = os.path.expanduser(a.out)
    info = json.load(open(os.path.join(out, "situations.json"))); vecs = np.load(os.path.join(out, "vectors.npy"))
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(info["model"], device=a.device)
    q = model.encode_query([a.question], normalize_embeddings=True, truncate_dim=info["dim"]).astype(np.float32)
    # turbovec gathers 4x the candidates from the compressed index; exact float32 scores re-rank them
    tv = os.path.join(out, "situations.tv")
    if os.path.exists(tv):
        import turbovec
        _, cand = turbovec.TurboQuantIndex.load(tv).search(q, k=min(len(vecs), a.k * 4)); cand = np.asarray(cand[0])
    else:
        cand = np.arange(len(vecs))
    s = vecs[cand] @ q[0]; order = np.argsort(-s)[: a.k]; ids = cand[order]; scores = s[order]
    hits = [info["meta"][i] | {"text": info["texts"][i], "score": float(sc)} for i, sc in zip(ids, scores)]
    nxt = np.array([h["next"] for h in hits]); ex = nxt - np.array([h["market"] for h in hits])
    print(f"\n{len(hits)} closest past situations to: {a.question!r}")
    for h in hits[:8]: print(f"  {h['date']} {h['symbol']:<12} next month {h['next']*100:+6.1f}%   {h['text'][:110]}…")
    print(f"\nWhat happened next (1 month): median {np.median(nxt)*100:+.1f}%, {np.median(ex)*100:+.1f}% over the market (median), "
          f"rose in {np.mean(nxt>0)*100:.0f}% of cases, beat the market in {np.mean(ex>0)*100:.0f}%.")
    print("Past situations, not predictions. No costs or taxes. Not a recommendation.")

def bank(a):
    """Embeds a local knowledge bank of stock screens (JSON list of {name, description, query, themes}) for search by meaning."""
    rows = json.load(open(os.path.expanduser(a.bank)))
    texts = [f"{r['name']}. {r.get('description','')} Rules: {r['query']}" for r in rows]
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(MODEL, device=a.device)
    vecs = model.encode_document(texts, batch_size=32, show_progress_bar=True, normalize_embeddings=True, truncate_dim=a.dim).astype(np.float32)
    out = os.path.expanduser(a.out); os.makedirs(out, exist_ok=True)
    np.save(os.path.join(out, "bank_vectors.npy"), vecs)
    json.dump({"model": MODEL, "dim": a.dim, "rows": rows}, open(os.path.join(out, "bank.json"), "w"))
    print(f"embedded {len(rows)} screens -> {out}")

def find(a):
    out = os.path.expanduser(a.out)
    info = json.load(open(os.path.join(out, "bank.json"))); vecs = np.load(os.path.join(out, "bank_vectors.npy"))
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(info["model"], device=a.device)
    q = model.encode_query([a.question], normalize_embeddings=True, truncate_dim=info["dim"]).astype(np.float32)
    s = vecs @ q[0]; ids = np.argsort(-s)[: a.k]
    print(f"\nScreens closest to: {a.question!r}")
    for i in ids:
        r = info["rows"][i]
        print(f"\n  [{s[i]:.2f}] {r['name']}  ({', '.join(r.get('themes', []))})\n      " + r["query"].replace("\n", " ")[:300])

def export(a):
    """Writes the bank into the app: screens.json (name, rules, themes, link — no descriptions) and int8 vectors."""
    out = os.path.expanduser(a.out); info = json.load(open(os.path.join(out, "bank.json"))); vecs = np.load(os.path.join(out, "bank_vectors.npy"))
    rows = [{"name": r["name"], "query": " ".join(r["query"].split()), "themes": r.get("themes", []), "url": r["url"]} for r in info["rows"]]
    os.makedirs(a.app, exist_ok=True)
    meta = json.dumps({"model": info["model"], "dim": info["dim"], "source": "Public screens listed in screener.in's sitemap; rules and names only, linked to the original.", "screens": rows},
                      ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    q = np.clip(np.round(vecs * 127 / np.abs(vecs).max(axis=1, keepdims=True)), -127, 127).astype(np.int8)
    # one file the app downloads on demand: [int32 json length][json][int32 n][int32 dim][int8 vectors]
    path = os.path.join(a.app, "screens-v1.kb")
    with open(path, "wb") as f:
        f.write(np.array([len(meta)], dtype="<i4").tobytes()); f.write(meta)
        f.write(np.array([len(q), q.shape[1]], dtype="<i4").tobytes()); f.write(q.tobytes())
    import hashlib
    print("sha256", hashlib.sha256(open(path, "rb").read()).hexdigest(), "size", os.path.getsize(path))
    exact = vecs @ vecs.T
    qf = q.astype(np.float32); qf /= np.linalg.norm(qf, axis=1, keepdims=True); approx = qf @ qf.T      # cosine, as the app computes it
    top = lambda m: np.argsort(-m, axis=1)[:, 1:11]
    rec = np.mean([len(set(x) & set(y)) / 10 for x, y in zip(top(exact), top(approx))])
    print(f"exported {len(rows)} screens, {q.nbytes // 1024} KB of int8 vectors; neighbour recall@10 vs float32: {rec:.2f}")

ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
b = sub.add_parser("build"); b.add_argument("--bhav", default="~/.khayali/bhav"); b.add_argument("--out", default="~/.khayali/research")
b.add_argument("--dim", type=int, default=256, choices=[128, 256, 512, 768]); b.add_argument("--device", default="mps")
q = sub.add_parser("ask"); q.add_argument("question"); q.add_argument("--out", default="~/.khayali/research"); q.add_argument("-k", type=int, default=200); q.add_argument("--device", default="mps")
kb = sub.add_parser("bank"); kb.add_argument("bank"); kb.add_argument("--out", default="~/.khayali/research"); kb.add_argument("--dim", type=int, default=256, choices=[128, 256, 512, 768]); kb.add_argument("--device", default="mps")
fd = sub.add_parser("find"); fd.add_argument("question"); fd.add_argument("--out", default="~/.khayali/research"); fd.add_argument("-k", type=int, default=6); fd.add_argument("--device", default="mps")
ex = sub.add_parser("export"); ex.add_argument("--out", default="~/.khayali/knowledge"); ex.add_argument("--app", default="docs/knowledge")
a = ap.parse_args(); {"build": build, "ask": ask, "bank": bank, "find": find, "export": export}[a.cmd](a)
