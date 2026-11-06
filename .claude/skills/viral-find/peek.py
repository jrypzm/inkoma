#!/usr/bin/env python3
"""python3 peek.py outliers.json [--top 5] [--out peek]

Downloads the top outliers for a moment, makes two contact sheets of each so you can see the first
picture and the pace, then deletes the video. Needs the videoUrl field, and Instagram's links expire,
so run it soon after fetching.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import urllib.request


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("path")
    p.add_argument("--top", type=int, default=5)
    p.add_argument("--out", default="peek")
    a = p.parse_args()
    with open(a.path, encoding="utf-8") as f:
        rows = json.load(f)["outliers"][: a.top]
    os.makedirs(a.out, exist_ok=True)
    made = 0
    for i, r in enumerate(rows, 1):
        name = f"{i}_{r['account']}"
        if not r.get("videoUrl"):
            print(f"{name}: no videoUrl, skipped. Judge this one from its caption and say so.")
            continue
        with tempfile.TemporaryDirectory() as tmp:
            video = os.path.join(tmp, "v.mp4")
            try:
                req = urllib.request.Request(r["videoUrl"], headers={"user-agent": "Mozilla/5.0"})
                with urllib.request.urlopen(req, timeout=120) as resp, open(video, "wb") as f:
                    f.write(resp.read())
            except Exception as err:  # An expired link is normal here, and the rest may still work.
                print(f"{name}: download failed ({err}). The link may have expired, so fetch the reels again.")
                continue
            dur = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video], capture_output=True, text=True).stdout.strip() or 0)
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-t", "3", "-i", video, "-vf", "fps=2,scale=300:-2,tile=6x1", "-frames:v", "1", os.path.join(a.out, f"{name}_hook.jpg")], check=True)
            per = max(dur / 18, 0.5)
            subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", video, "-vf", f"fps=1/{per:.3f},scale=300:-2,tile=6x3", "-frames:v", "1", os.path.join(a.out, f"{name}_whole.jpg")], check=True)
        made += 1
        print(f"{name}: {dur:.0f} s, {r['views']:,} views, {r['x']}x  {os.path.join(a.out, name)}_hook.jpg and _whole.jpg")
    if not made:
        sys.exit("No sheets made.")


if __name__ == "__main__":
    main()
