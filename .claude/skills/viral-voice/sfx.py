#!/usr/bin/env python3
"""python3 sfx.py <project>  -> sfx.wav. Synthesized, non-musical sound effects, with no samples and no licences to worry about.

A thump opens the reel and a quiet whoosh marks every beat change. "sfx_auto": false in script.json turns those off.
Add more in script.json, on a word so they stay in sync when the voice changes:
"sfx": [{"beat": "b2", "word": "charger", "kind": "pop", "db": -14}]    or "at": 1.1 for seconds into the beat
"""
import array
import json
import math
import os
import random
import sys
import wave

SR = 48000
rng = random.Random(7)


def lowpass(x, fc):
    out, y, a = [], 0.0, 1 - math.exp(-2 * math.pi * fc / SR)
    for v in x:
        y += a * (v - y)
        out.append(y)
    return out


def highpass(x, fc):
    low = lowpass(x, fc)
    return [a - b for a, b in zip(x, low)]


def norm(x):
    peak = max(abs(v) for v in x) or 1
    return [v / peak for v in x]


def noise(n):
    return [rng.uniform(-1, 1) for _ in range(n)]


def whoosh(dur=0.34, f0=500, f1=2600, peak=0.62):
    n, y, out = int(dur * SR), 0.0, []
    for i in range(n):
        u = i / n
        fc = f0 * (f1 / f0) ** u
        a = 1 - math.exp(-2 * math.pi * fc / SR)
        y += a * (rng.uniform(-1, 1) - y)
        env = (u / peak) ** 2.2 if u < peak else math.exp(-(u - peak) / 0.12)
        out.append(y * env)
    return norm(out)


def swell():
    return whoosh(0.45, 300, 1800, 0.9)


def chirp(dur, f_end, f_extra, tau, att=0.002, attack_tau=0.02):
    n, ph, out = int(dur * SR), 0.0, []
    for i in range(n):
        t = i / SR
        ph += 2 * math.pi * (f_end + f_extra * math.exp(-t / attack_tau)) / SR
        env = (t / att) ** 2 if t < att else math.exp(-(t - att) / tau)
        out.append(math.sin(ph) * env)
    return out


def pop():
    return norm(chirp(0.11, 300, 320, 0.028, attack_tau=0.018))


def thump():
    body = chirp(0.35, 46, 60, 0.09, att=0.003, attack_tau=0.03)
    rumble = lowpass(noise(len(body)), 400)
    return norm([b + r * 0.6 * math.exp(-i / SR / 0.03) for i, (b, r) in enumerate(zip(body, rumble))])


def tick():
    x = highpass(lowpass(noise(int(0.03 * SR)), 9000), 2500)
    return norm([v * math.exp(-i / SR / 0.004) for i, v in enumerate(x)])


def stamp():
    body = chirp(0.3, 70, 90, 0.06)
    slap = highpass(lowpass(noise(len(body)), 4000), 600)
    return norm([b + s * 1.4 * math.exp(-i / SR / 0.012) for i, (b, s) in enumerate(zip(body, slap))])


def swipe():
    x = highpass(lowpass(noise(int(0.2 * SR)), 7000), 1800)
    n = len(x)
    return norm([v * math.sin(math.pi * i / n) ** 2 for i, v in enumerate(x)])


def knock():
    body = chirp(0.13, 110, 70, 0.03)
    wood = lowpass(noise(len(body)), 1500)
    return norm([b + w * 0.7 * math.exp(-i / SR / 0.01) for i, (b, w) in enumerate(zip(body, wood))])


def click():
    return norm(chirp(0.03, 1700, 900, 0.004, att=0.0005, attack_tau=0.003))


KINDS = {"whoosh": whoosh, "swell": swell, "pop": pop, "tick": tick, "thump": thump, "stamp": stamp, "swipe": swipe, "knock": knock, "click": click}


def main(project):
    with open(os.path.join(project, "timing.json"), encoding="utf-8") as f:
        timing = json.load(f)
    with open(os.path.join(project, "script.json"), encoding="utf-8") as f:
        script = json.load(f)
    by_id = {b["id"]: b for b in timing["beats"]}
    events = []
    if script.get("sfx_auto", True):
        events.append((0.0, "thump", -11.0))
        events += [(b["start"] - 0.2, "whoosh", -19.0) for b in timing["beats"][1:]]
    for e in script.get("sfx", []):
        beat = by_id.get(e.get("beat"))
        if beat is None and "t" not in e:
            sys.exit(f"sfx event needs a known beat or a time: {e}")
        if "word" in e:
            hits = [w for w in beat.get("words", []) if e["word"].lower() in w["w"].lower()]
            if not hits:
                sys.exit(f"sfx event: beat {e['beat']} doesn't say \"{e['word']}\"")
            t = hits[e.get("nth", 0)]["s"] + e.get("at", 0)
        else:
            t = e["t"] if "t" in e else beat["start"] + e.get("at", 0)
        if e["kind"] not in KINDS:
            sys.exit(f"Unknown sound '{e['kind']}'. Kinds: {' '.join(KINDS)}")
        events.append((t, e["kind"], e.get("db", -15.0)))
    n = int(timing["duration"] * SR) + 1
    mix = array.array("f", bytes(4 * n))
    for t, kind, db in events:
        gain, at = 10 ** (db / 20), max(int(t * SR), 0)
        for i, v in enumerate(KINDS[kind]()):
            if at + i >= n:
                break
            mix[at + i] += v * gain
    pcm = array.array("h", (max(-32767, min(32767, int(v * 32767))) for v in mix))
    if sys.byteorder == "big":
        pcm.byteswap()
    with wave.open(os.path.join(project, "sfx.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(f"sfx.wav: {len(events)} sounds")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
