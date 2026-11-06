#!/usr/bin/env python3
"""python3 check.py <project or video.mp4>  -> check/report.json, check/hook.jpg, check/sheet.jpg

Measures what a machine can measure. The judgement questions in rubric.json are for Claude, using the two images.
Exit code 1 if anything failed.
"""
import json
import os
import re
import statistics
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "viral-script"))
import lint_script  # noqa: E402

SAFE = "drawbox=x=0:y=0:w=iw:h=220:color=red@0.22:t=fill,drawbox=x=0:y=1540:w=iw:h=380:color=red@0.22:t=fill,drawbox=x=950:y=900:w=130:h=640:color=red@0.22:t=fill"


def ff(*args):
    return subprocess.run(["ffmpeg", "-nostdin", "-v", "info", "-hide_banner", *args], capture_output=True, text=True).stderr


def main(target):
    project = target if os.path.isdir(target) else os.path.dirname(os.path.abspath(target))
    video = os.path.join(target, "final.mp4") if os.path.isdir(target) else target
    if not os.path.exists(video):
        sys.exit(f"No video at {video}")
    out = os.path.join(project, "check")
    os.makedirs(out, exist_ok=True)
    rows = []

    def add(level, name, detail):
        rows.append({"level": level, "check": name, "detail": detail})

    pr = json.loads(subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", video], capture_output=True, text=True).stdout)
    vid = next((s for s in pr["streams"] if s["codec_type"] == "video"), None)
    aud = next((s for s in pr["streams"] if s["codec_type"] == "audio"), None)
    dur = float(pr["format"]["duration"])
    w, h = vid["width"], vid["height"]
    add("pass" if (w, h) == (1080, 1920) else "fail", "Frame size", f"{w}x{h} (want 1080x1920)")
    num, den = vid["r_frame_rate"].split("/")
    fps = float(num) / float(den)
    add("pass" if 24 <= fps <= 60 else "warn", "Frame rate", f"{fps:g} fps")
    add("pass" if vid["codec_name"] == "h264" and vid.get("pix_fmt") == "yuv420p" else "warn", "Video format", f"{vid['codec_name']} {vid.get('pix_fmt')}")
    add("pass" if 7 <= dur <= 90 else "warn", "Length", f"{dur:.1f} s")
    if not aud:
        add("fail", "Audio", "No audio track.")
    else:
        log = ff("-i", video, "-af", "ebur128=peak=true", "-f", "null", "-")
        i = re.search(r"I:\s+(-?[\d.]+) LUFS", log.split("Summary:")[-1])
        tp = re.search(r"Peak:\s+(-?[\d.]+) dBFS", log.split("Summary:")[-1])
        lufs, peak = float(i.group(1)) if i else None, float(tp.group(1)) if tp else None
        add("pass" if lufs is not None and -16.5 <= lufs <= -12.5 else "warn", "Loudness", f"{lufs} LUFS (want about -14)")
        add("pass" if peak is not None and peak <= -0.9 else "warn", "Peak", f"{peak} dB (want under -1)")
        log = ff("-i", video, "-af", "silencedetect=noise=-38dB:d=0.25", "-f", "null", "-")
        starts = [float(x) for x in re.findall(r"silence_start: ([\d.]+)", log)]
        ends = [float(x) for x in re.findall(r"silence_end: ([\d.]+)", log)]
        first = ends[0] if starts and starts[0] < 0.05 and ends else 0.0
        add("pass" if first <= 0.3 else ("warn" if first <= 0.6 else "fail"), "First sound", f"{first:.2f} s in")
        gaps = [(s, e) for s, e in zip(starts, ends) if s > 0.05 and e - s > 1.2 and e < dur - 0.1]
        add("warn" if gaps else "pass", "Dead air", ", ".join(f"{e - s:.1f} s of silence at {s:.1f} s" for s, e in gaps) or "None over 1.2 s")

    p = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-t", "3", "-i", video, "-vf", "scale=270:-2,select='gte(scene,0)',metadata=print:file=-", "-an", "-f", "null", "-"], capture_output=True, text=True)
    scores = [float(x) for x in re.findall(r"lavfi\.scene_score=([\d.]+)", p.stdout)]
    motion = statistics.mean(scores[1:]) if len(scores) > 1 else 0
    add("pass" if motion >= 0.002 else "warn", "Movement in first 3 s", f"score {motion:.4f}{'' if motion >= 0.002 else ', looks like a still image'}")

    p = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-i", video, "-vf", "fps=2,scale=270:-2,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-", "-an", "-f", "null", "-"], capture_output=True, text=True)
    luma = [float(x) for x in re.findall(r"YAVG=([\d.]+)", p.stdout)]
    dark = [i / 2 for i, v in enumerate(luma) if v < 20]
    spans = []
    for t in dark:
        if spans and t - spans[-1][1] <= 0.5:
            spans[-1][1] = t
        else:
            spans.append([t, t])
    long_dark = [s for s in spans if s[1] - s[0] >= 1.0]
    add("warn" if long_dark else "pass", "Too dark for a phone", ", ".join(f"{a:g}-{b:g} s" for a, b in long_dark) or "No long dark stretch")
    log = ff("-i", video, "-vf", "scale=270:-2,freezedetect=n=0.0008:d=2.5", "-an", "-f", "null", "-")
    frozen = re.findall(r"freeze_start: ([\d.]+)", log)
    add("warn" if frozen else "pass", "Frozen picture", ", ".join(f"from {float(x):.1f} s" for x in frozen) or "None over 2.5 s")

    script_path = os.path.join(project, "script.json")
    if os.path.exists(script_path):
        with open(script_path, encoding="utf-8") as f:
            script = json.load(f)
        if script.get("beats"):
            first = lint_script.sentences(script["beats"][0]["say"])[0]
            n = len(first.split())
            add("pass" if n <= 14 else "fail", "Hook length", f"First sentence is {n} words")
            label = script["beats"][0].get("label") or script.get("label")
            add("pass" if label else "warn", "On-screen hook", label or "No label, so a muted viewer gets no words on frame 0")
            issues = []
            for b in script["beats"]:
                fix, _ = lint_script.check_line(b["say"], "--allow-lakh" in sys.argv)
                issues += [f"{b['id']}: {m}" for m in fix]
            add("warn" if issues else "pass", "Script wording", "; ".join(issues) or "Clean")

    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", video, "-vf", f"{SAFE},scale=540:-2", "-frames:v", "1", os.path.join(out, "frame0.jpg")])
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-t", "3", "-i", video, "-vf", f"fps=2,{SAFE},scale=320:-2,tile=6x1", "-frames:v", "1", os.path.join(out, "hook.jpg")])
    per = max(dur / 18, 1)
    subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", video, "-vf", f"fps=1/{per:.3f},{SAFE},scale=320:-2,tile=6x3", "-frames:v", "1", os.path.join(out, "sheet.jpg")])

    report = {"video": video, "duration": round(dur, 2), "checks": rows}
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    marks = {"pass": "PASS", "warn": "WARN", "fail": "FAIL"}
    for r in rows:
        print(f"{marks[r['level']]:<5} {r['check']:<24} {r['detail']}")
    print(f"\nImages for the hook review (red areas are hidden by Instagram's buttons):\n{os.path.join(out, 'frame0.jpg')}\n{os.path.join(out, 'hook.jpg')}\n{os.path.join(out, 'sheet.jpg')}")
    sys.exit(1 if any(r["level"] == "fail" for r in rows) else 0)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1])
