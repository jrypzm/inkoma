#!/usr/bin/env python3
"""python3 lint_script.py <script.json or project folder> [--allow-lakh]

Catches the things that make a script sound machine-written, or promise what it can't deliver.
[FIX] lines should be rewritten. [LOOK] lines need a human decision. Exit code 1 if there is anything to fix.
"""
import argparse
import json
import os
import re
import sys

WORDS_PER_SEC = {"ml": 2.6, "en": 2.8}
NEG = r"(?:\S*(?:ഇല്ല|ില്ല|വേണ്ട|േണ്ട|അല്ല|ല്ല)|no|not|never)"
SLOP = ["delve", "leverage", "seamless", "robust", "game-changer", "game changer", "unlock", "supercharge", "in today's", "let that sink in",
        "here's the thing", "the truth is", "imagine a world", "at the end of the day", "moreover", "furthermore", "tapestry", "testament"]
PROMISE = [r"\bguarantee", r"will go viral", r"go viral overnight", r"overnight", r"you will get", r"you'll get \S* ?(?:million|views)", r"ഉറപ്പായും", r"ഉറപ്പാണ്", r"100%"]
LAKH = r"\b(?:lakh|lakhs|crore|crores)\b|കോടി|ലക്ഷം"


def sentences(text):
    return [s.strip() for s in re.split(r"(?<=[.?!])\s+", text.replace("*", "").strip()) if s.strip()]


def check_line(say, allow_lakh):
    fix, look = [], []
    text = say.replace("*", "")
    low = text.lower()
    parts = sentences(text)
    # "Camera ഇല്ല. Animator ഇല്ല. Claude മാത്രം." and "No camera. No animator."
    run = 0
    for s in parts:
        words = s.rstrip(".?!").split()
        negative = re.fullmatch(NEG, words[-1], re.I) or re.match(r"(?:no|not)\b", words[0], re.I) if words else False
        run = run + 1 if negative and len(words) <= 4 else 0
        if run >= 2:
            fix.append(f'"not this, not that" rhythm: "{s}". Say it as one spoken sentence.')
            break
    # "camera വേണ്ട, മുഖം കാണിക്കേണ്ട, editor വേണ്ട"
    pieces = [p.strip() for p in re.split(r"[,،]", text)]
    if sum(1 for p in pieces if p.split() and len(p.split()) <= 3 and re.fullmatch(NEG, p.rstrip(".?!").split()[-1], re.I)) >= 2:
        fix.append('List of negatives ("X വേണ്ട, Y വേണ്ട"). Say what happens instead.')
    if re.search(r"\bnot\s[^.,;]{1,30},\s*not\b", low) or re.search(r"\bno\s+\w+[,.]\s+no\s+\w+", low):
        fix.append('"not X, not Y" rhythm. Say it as one spoken sentence.')
    if re.search(r"\b(?:it'?s|this is|that'?s)\s+not\b[^.?!]{1,50}[,;.]\s*(?:it'?s|this is|that'?s)\b", low):
        fix.append('"It\'s not X, it\'s Y" is a stock AI move. Say the Y plainly.')
    run = 0
    for s in parts:
        run = run + 1 if len(s.split()) <= 3 else 0
        if run >= 3:
            fix.append("Three or more very short sentences in a row. Join them the way you'd say them out loud.")
            break
    if re.search(r"[—–]", text):
        fix.append("Dash in spoken text. Use a comma or a new sentence.")
    for w in SLOP:
        if w in low:
            fix.append(f'Stock phrase: "{w}".')
    promised = [m.group(0) for m in (re.search(pat, low) for pat in PROMISE) if m]
    if promised:
        look.append(f'Possible promise of results ("{promised[0]}"). Nobody can guarantee views; check this is true.')
    if not allow_lakh and re.search(LAKH, low):
        fix.append("Lakh or crore in the line. Write views as millions or K (6.5 million, 318K).")
    return fix, look


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("path")
    p.add_argument("--allow-lakh", action="store_true")
    a = p.parse_args()
    path = os.path.join(a.path, "script.json") if os.path.isdir(a.path) else a.path
    with open(path, encoding="utf-8") as f:
        script = json.load(f)
    rate = WORDS_PER_SEC.get(script.get("language", "en"), 2.7)
    beats = script.get("beats", [])
    fixes = looks = 0

    def report(kind, where, msg):
        nonlocal fixes, looks
        fixes += kind == "FIX"
        looks += kind == "LOOK"
        print(f"[{kind}] {where}: {msg}")

    if not beats:
        report("FIX", "script", "No beats.")
    ids = [b.get("id") for b in beats]
    if len(set(ids)) != len(ids):
        report("FIX", "script", "Beat ids must be unique.")
    for b in beats:
        where = b.get("id", "?")
        if not b.get("say", "").strip():
            report("FIX", where, "No spoken line.")
            continue
        if not b.get("scene", "").strip():
            report("FIX", where, "No scene description. Every beat needs a picture.")
        fix, look = check_line(b["say"], a.allow_lakh)
        for m in fix:
            report("FIX", where, m)
        for m in look:
            report("LOOK", where, m)
    for i, h in enumerate(script.get("hooks", [])):
        fix, look = check_line(h.get("say", ""), a.allow_lakh)
        for m in fix:
            report("FIX", f"hook {i + 1}", m)
        for m in look:
            report("LOOK", f"hook {i + 1}", m)

    hooks = [("b1", beats[0]["say"])] if beats and beats[0].get("say") else []
    hooks += [(f"hook {i + 1}", h.get("say", "")) for i, h in enumerate(script.get("hooks", []))]
    for where, say in hooks:
        first = (sentences(say) or [""])[0]
        n = len(first.split())
        if n > 14:
            report("FIX", where, f"First sentence is {n} words. A hook lands in about 14 or fewer.")
        total_words = len(say.replace("*", "").split())
        if total_words > 16:
            report("FIX", where, f"Hook is {total_words} words. Cut it to 16 or fewer; long hooks lose people before the claim lands.")
    label = (beats[0].get("label") if beats else None) or script.get("label")
    if not label:
        report("LOOK", "script", "No on-screen label. Without one, a muted viewer sees nothing on the first frame except the picture.")
    elif len(label.split()) > 8:
        report("LOOK", "script", f"Label is {len(label.split())} words. A muted viewer reads about 6 at a glance.")
    total = sum(len(b.get("say", "").replace("*", "").split()) / rate + 0.15 for b in beats)
    if total > 60:
        report("LOOK", "script", f"About {total:.0f} s long. Over 60 s, most stories should lose a beat.")
    print(f"\nAbout {total:.0f} s. {fixes} to fix, {looks} to look at.")
    sys.exit(1 if fixes else 0)


if __name__ == "__main__":
    main()
