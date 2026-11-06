#!/usr/bin/env python3
"""Make the voice track and the real caption times for a project.

python3 voice.py <project>                                free voice (Malayalam by default for language "ml")
python3 voice.py <project> --voice ml-IN-SobhanaNeural    another free voice
python3 voice.py <project> --engine elevenlabs --voice <voice id>    needs ELEVENLABS_API_KEY
python3 voice.py <project> --engine file --audio take.m4a one recording of the whole script, in order
python3 voice.py <project> --engine file --audio voice/   one file per beat, named after the beat id (b1.m4a, b2.m4a)

Writes voice.wav and overwrites timing.json.
"""
import argparse
import base64
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "viral-animate"))
import timing as T  # noqa: E402

TK = os.environ.get("VIRAL_TOOLKIT", os.path.expanduser("~/.viral-agent"))
EDGE_DEFAULT = {"ml": "ml-IN-MidhunNeural", "en": "en-US-AndrewNeural", "hi": "hi-IN-MadhurNeural", "ta": "ta-IN-ValluvarNeural"}
TAIL = 0.4


def ff(*args):
    r = subprocess.run(["ffmpeg", "-nostdin", "-y", *args], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"ffmpeg failed: {r.stderr[-600:]}")
    return r.stderr


def duration(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True)
    return float(r.stdout.strip())


def speech_regions(path, noise="-34dB", gap=0.14):
    """Where someone is actually talking, from ffmpeg's silence detector."""
    log = ff("-i", path, "-af", f"silencedetect=noise={noise}:d={gap}", "-f", "null", "-")
    total, regions, cur = duration(path), [], 0.0
    for kind, val in re.findall(r"silence_(start|end): ([\d.]+)", log):
        val = float(val)
        if kind == "start":
            if val > cur + 0.03:
                regions.append((cur, val))
        else:
            cur = val
    if cur < total - 0.03:
        regions.append((cur, total))
    return regions or [(0.0, total)]


def spread(words, regions):
    """No word timings available: share the spoken time out by word length, skipping the silences."""
    weights = [max(len(w["w"]), 2) for w in words]
    whole, talk = sum(weights), sum(e - s for s, e in regions)

    def real(pos):
        for s, e in regions:
            if pos <= e - s:
                return s + pos
            pos -= e - s
        return regions[-1][1]

    done = 0
    for w, n in zip(words, weights):
        w["s"] = real(talk * done / whole)
        done += n
        w["e"] = real(talk * done / whole)


def apply_marks(words, marks, regions, beat_id=""):
    if len(marks) == len(words):
        for w, m in zip(words, marks):
            w["s"], w["e"] = m["s"], m["e"]
    else:
        if marks:
            print(f"Note: {beat_id} speaks {len(marks)} words but its caption has {len(words)}, so its caption times are estimated. Match the word counts in \"tts\" for exact times.")
        spread(words, regions)


def tts_text(beat):
    return beat.get("tts") or beat["say"].replace("*", "")


def edge_clip(beat, out, a):
    py = os.path.join(TK, "py", "bin", "python")
    if not os.path.exists(py):
        py = os.path.join(TK, "py", "Scripts", "python.exe")
    if not os.path.exists(py):
        sys.exit(f"Free voice engine missing. Run: bash \"{os.path.join(HERE, 'setup.sh')}\"")
    r = subprocess.run([py, os.path.join(HERE, "edge_say.py"), a.voice, a.rate, out], input=tts_text(beat), capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"Free voice failed on {beat['id']} (it needs internet): {r.stderr[-400:]}")
    return json.loads(r.stdout)


def eleven_clip(beat, out, a):
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        sys.exit("Set ELEVENLABS_API_KEY first, or use the free voice.")
    if not a.voice:
        sys.exit("Pass --voice <ElevenLabs voice id>.")
    base = os.environ.get("ELEVENLABS_BASE_URL", "https://api.elevenlabs.io")
    body = json.dumps({"text": tts_text(beat), "model_id": a.model}).encode()
    headers = {"xi-api-key": key, "content-type": "application/json"}

    def post(path):
        return urllib.request.urlopen(urllib.request.Request(f"{base}{path}", data=body, headers=headers), timeout=120)

    try:
        with post(f"/v1/text-to-speech/{a.voice}/with-timestamps") as resp:
            data = json.load(resp)
        with open(out, "wb") as f:
            f.write(base64.b64decode(data["audio_base64"]))
        al = data.get("alignment") or data.get("normalized_alignment") or {}
        marks, cur = [], None
        for ch, s, e in zip(al.get("characters", []), al.get("character_start_times_seconds", []), al.get("character_end_times_seconds", [])):
            if ch.isspace():
                cur = None
            elif cur is None:
                cur = {"w": ch, "s": s, "e": e}
                marks.append(cur)
            else:
                cur["w"] += ch
                cur["e"] = e
        return marks
    except urllib.error.HTTPError as err:
        if err.code in (401, 402, 403):
            sys.exit(f"ElevenLabs refused the request ({err.code}): {err.read()[:300]!r}")
    # Some models have no timestamps route. Take plain audio; timing then comes from the silence detector.
    try:
        with post(f"/v1/text-to-speech/{a.voice}") as resp, open(out, "wb") as f:
            f.write(resp.read())
    except urllib.error.HTTPError as err:
        sys.exit(f"ElevenLabs failed ({err.code}): {err.read()[:300]!r}")
    return []


def join_clips(clips, gap, out):
    """clips is [(path, start, end)]. Trims each, puts a short gap between them, adds a tail."""
    args, parts = [], ""
    for i, (path, s, e) in enumerate(clips):
        args += ["-i", path]
        pad = TAIL if i == len(clips) - 1 else gap
        parts += f"[{i}:a]aresample=48000,aformat=channel_layouts=mono,atrim={s:.3f}:{e:.3f},asetpts=N/SR/TB,apad=pad_dur={pad:.3f}[a{i}];"
    parts += "".join(f"[a{i}]" for i in range(len(clips))) + f"concat=n={len(clips)}:v=0:a=1[out]"
    ff(*args, "-filter_complex", parts, "-map", "[out]", "-c:a", "pcm_s16le", out)


def per_beat(script, a, tmp):
    rows, clips, cursor = [], [], 0.0
    for beat in script["beats"]:
        words = T.parse_say(beat["say"])
        if a.engine == "file":
            hits = glob.glob(os.path.join(a.audio, f"{beat['id']}.*"))
            if not hits:
                sys.exit(f"No recording for beat {beat['id']} in {a.audio}")
            path, marks = hits[0], []
        else:
            path = os.path.join(tmp, f"{beat['id']}.mp3")
            marks = edge_clip(beat, path, a) if a.engine == "edge" else eleven_clip(beat, path, a)
        regions = speech_regions(path)
        apply_marks(words, marks, regions, beat["id"])
        start = max(words[0]["s"] - 0.03, 0)
        end = min(max(words[-1]["e"], regions[-1][1]) + 0.08, duration(path))
        for w in words:
            w["s"] += cursor - start
            w["e"] += cursor - start
        clips.append((path, start, end))
        pad = TAIL if beat is script["beats"][-1] else a.gap
        rows.append((beat["id"], cursor, cursor + end - start + pad, words))
        cursor += end - start + pad
    return rows, clips, cursor


def one_take(script, a):
    regions = speech_regions(a.audio)
    per = [T.parse_say(b["say"]) for b in script["beats"]]
    allwords = [w for ws in per for w in ws]
    spread(allwords, regions)
    start, end = max(regions[0][0] - 0.03, 0), regions[-1][1] + 0.08
    gaps = [(regions[i][1], regions[i + 1][0]) for i in range(len(regions) - 1)]
    rows, t = [], 0.0
    for i, (beat, ws) in enumerate(zip(script["beats"], per)):
        # A beat changes on the nearest real pause, so the picture never cuts mid-word.
        if i + 1 < len(per):
            guess = per[i + 1][0]["s"]
            near = [g for g in gaps if abs((g[0] + g[1]) / 2 - guess) < 1.0]
            cut = (min(near, key=lambda g: abs((g[0] + g[1]) / 2 - guess))[1] - 0.02) if near else guess
        else:
            cut = end + TAIL
        rows.append((beat["id"], t, cut - start, ws))
        t = cut - start
    for ws in per:
        for w in ws:
            w["s"] -= start
            w["e"] -= start
    return rows, [(a.audio, start, end)], end - start + TAIL


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("project")
    p.add_argument("--engine", choices=["edge", "elevenlabs", "file"], default="edge")
    p.add_argument("--voice")
    p.add_argument("--rate", default="+8%", help="free voice speed")
    p.add_argument("--model", default="eleven_v3")
    p.add_argument("--audio", help="a recording, or a folder of per-beat recordings")
    p.add_argument("--gap", type=float, default=0.12, help="seconds between beats")
    a = p.parse_args()
    with open(os.path.join(a.project, "script.json"), encoding="utf-8") as f:
        script = json.load(f)
    lang = script.get("language", "en")
    if a.engine == "edge" and not a.voice:
        a.voice = EDGE_DEFAULT.get(lang)
        if not a.voice:
            sys.exit(f"No default free voice for language '{lang}'. Pass --voice.")
    if a.engine == "file" and not a.audio:
        sys.exit("--engine file needs --audio.")

    with tempfile.TemporaryDirectory() as tmp:
        if a.engine == "file" and os.path.isfile(a.audio):
            rows, clips, total = one_take(script, a)
        else:
            rows, clips, total = per_beat(script, a, tmp)
        join_clips(clips, a.gap, os.path.join(a.project, "voice.wav"))
    timing = T.build(script, rows, total)
    with open(os.path.join(a.project, "timing.json"), "w", encoding="utf-8") as f:
        json.dump(timing, f, ensure_ascii=False, indent=1)
    print(f"voice.wav and timing.json: {timing['duration']} s, {len(timing['beats'])} beats, {len(timing['chunks'])} captions ({a.engine})")


if __name__ == "__main__":
    main()
