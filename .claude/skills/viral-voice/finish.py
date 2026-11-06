#!/usr/bin/env python3
"""python3 finish.py <project> [--no-render]  -> final.mp4

Renders the picture on the real voice timing, adds the sound effects, sets the loudness to about -14 LUFS and joins it all.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = -14.5


def ff(*args):
    r = subprocess.run(["ffmpeg", "-nostdin", "-y", "-v", "info", *args], capture_output=True, text=True)
    if r.returncode:
        sys.exit(f"ffmpeg failed: {r.stderr[-600:]}")
    return r.stderr


def loudness(path):
    log = ff("-i", path, "-af", "loudnorm=print_format=summary", "-f", "null", "-")
    i, tp = re.search(r"Input Integrated:\s*(-?[\d.]+|-?inf)", log), re.search(r"Input True Peak:\s*([+-]?[\d.]+|-?inf)", log)
    return float(i.group(1)), float(tp.group(1))


def main(project, render):
    p = lambda name: os.path.join(project, name)  # noqa: E731
    for need in ("voice.wav", "timing.json"):
        if not os.path.exists(p(need)):
            sys.exit(f"{need} is missing. Run voice.py first.")
    if render:
        r = subprocess.run(["node", os.path.join(HERE, "..", "viral-animate", "render.mjs"), project])
        if r.returncode:
            sys.exit("Rendering failed.")
    r = subprocess.run([sys.executable, os.path.join(HERE, "sfx.py"), project])
    if r.returncode:
        sys.exit("Sound effects failed.")
    chain = "highpass=f=75,lowpass=f=15000,acompressor=threshold=-24dB:ratio=2.4:attack=6:release=120:makeup=2"
    ff("-i", p("voice.wav"), "-af", chain, "-ar", "48000", "-ac", "2", "-c:a", "pcm_f32le", p(".v1.wav"))
    gain = TARGET - loudness(p(".v1.wav"))[0]
    mix = (f"[0:a]volume={gain:.2f}dB[v];[1:a]aformat=channel_layouts=stereo,volume=3dB[s];"
           "[v][s]amix=inputs=2:normalize=0:duration=first,alimiter=limit=0.85:attack=3:release=50:level=disabled[a]")
    ff("-i", p(".v1.wav"), "-i", p("sfx.wav"), "-filter_complex", mix, "-map", "[a]", "-ar", "48000", "-c:a", "pcm_s16le", p("mix.wav"))
    os.remove(p(".v1.wav"))
    ff("-i", p("video.mp4"), "-i", p("mix.wav"), "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "256k",
       "-movflags", "+faststart", "-shortest", p("final.mp4"))
    i, tp = loudness(p("mix.wav"))
    size = os.path.getsize(p("final.mp4")) / 1e6
    print(f"{p('final.mp4')}  ({size:.1f} MB, {i} LUFS, {tp} dBTP)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1], "--no-render" not in sys.argv)
