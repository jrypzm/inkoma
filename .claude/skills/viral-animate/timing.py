#!/usr/bin/env python3
"""python3 timing.py <project>  -> timing.json with estimated times, for drafting before any voice exists.

viral-voice overwrites timing.json with the real times. The chunking rules live here so both agree.
"""
import json
import os
import re
import sys

WORDS_PER_SEC = {"ml": 2.6, "en": 2.8}
MAX_WORDS, MAX_CHARS = 4, 24


def parse_say(text):
    """Split a line into words. *accent words* are marked hot; punctuation stays on the word for chunking."""
    out, hot = [], False
    for raw in text.split():
        stars = raw.count("*")
        out.append({"w": raw.replace("*", ""), "hot": hot or stars > 0})
        if stars % 2:
            hot = not hot
    return out


def clean(word):
    return word.rstrip(",.:;")


def chunk_words(words):
    """Up to four words per caption, always cut at punctuation, short enough for one line."""
    chunks, cur = [], []
    for w in words:
        if cur and (len(cur) >= MAX_WORDS or sum(len(x["w"]) for x in cur) + len(w["w"]) > MAX_CHARS):
            chunks.append(cur)
            cur = []
        cur.append(w)
        if re.search(r"[,.?!:;]$", w["w"]):
            chunks.append(cur)
            cur = []
    if cur:
        # A stray last word joins the caption before it, unless that caption ended a thought or would get too long.
        prev = chunks[-1] if chunks else None
        if len(cur) == 1 and prev and not re.search(r"[,.?!:;]$", prev[-1]["w"]) and sum(len(x["w"]) for x in prev) + len(cur[0]["w"]) <= MAX_CHARS:
            prev.extend(cur)
        else:
            chunks.append(cur)
    return [
        {"s": c[0]["s"], "e": c[-1]["e"],
         "words": [{"w": clean(x["w"]), "hot": x["hot"], "s": round(x["s"], 3), "e": round(x["e"], 3)} for x in c]}
        for c in chunks
    ]


def build(script, beat_words, duration, fps=30):
    """beat_words is [(beat_id, start, end, [word dicts with s and e])]. Returns the timing.json structure."""
    beats, chunks = [], []
    for bid, start, end, words in beat_words:
        beats.append({"id": bid, "start": round(start, 3), "end": round(end, 3),
                      "words": [{"w": clean(x["w"]), "s": round(x["s"], 3), "e": round(x["e"], 3)} for x in words]})
        chunks.extend(chunk_words(words))
    for c in chunks:
        c["s"], c["e"] = round(c["s"], 3), round(c["e"], 3)
    return {"fps": fps, "duration": round(duration, 3), "beats": beats, "chunks": chunks, "language": script.get("language", "en")}


def estimate(script):
    rate = WORDS_PER_SEC.get(script.get("language", "en"), 2.7)
    t, rows = 0.0, []
    for b in script["beats"]:
        words = parse_say(b["say"])
        d = max(len(words) / rate, 1.2)
        step = d / max(len(words), 1)
        for i, w in enumerate(words):
            w["s"], w["e"] = t + i * step, t + (i + 1) * step
        rows.append((b["id"], t, t + d + 0.15, words))
        t += d + 0.15
    return build(script, rows, t)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    project = sys.argv[1]
    with open(os.path.join(project, "script.json"), encoding="utf-8") as f:
        script = json.load(f)
    timing = estimate(script)
    with open(os.path.join(project, "timing.json"), "w", encoding="utf-8") as f:
        json.dump(timing, f, ensure_ascii=False, indent=1)
    print(f"timing.json: {timing['duration']} s (estimated), {len(timing['beats'])} beats, {len(timing['chunks'])} captions")
