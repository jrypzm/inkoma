"""Runs inside the toolkit's Python: edge_say.py <voice> <rate> <out.mp3> < text  -> prints word marks as JSON."""
import asyncio
import json
import sys

import edge_tts


async def main(voice, rate, out, text):
    marks = []
    speech = edge_tts.Communicate(text, voice, rate=rate, boundary="WordBoundary")
    with open(out, "wb") as f:
        async for ch in speech.stream():
            if ch["type"] == "audio":
                f.write(ch["data"])
            elif ch["type"] == "WordBoundary":
                marks.append({"w": ch["text"], "s": ch["offset"] / 1e7, "e": (ch["offset"] + ch["duration"]) / 1e7})
    print(json.dumps(marks, ensure_ascii=False))


asyncio.run(main(sys.argv[1], sys.argv[2], sys.argv[3], sys.stdin.read().strip()))
