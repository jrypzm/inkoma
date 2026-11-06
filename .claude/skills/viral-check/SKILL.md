---
name: viral-check
description: Check a finished reel before it goes out. Measures the technical side (size, loudness, dead air, dark or frozen stretches), scores the hook and the first 3 seconds against a six-question rubric, and rewrites the hook when it's weak. Use whenever a reel is finished, or someone asks if a reel or hook is good, will people scroll past it, is it ready to post, or wants a hook reviewed. Last step of the viral pack, and works on any 9:16 video.
---

# viral-check

This catches known ways a reel loses people. It can't predict views, and it should never claim to. Nothing here posts anything: the user decides what goes live.

Scripts in this guide live next to this file, so use their full path.

## 1. Measure

```bash
python3 check.py <project>      # or a path to any .mp4
```

`FAIL` must be fixed. `WARN` needs a look. Some warnings are fine on purpose, such as a dark scene that's meant to be dark and lasts a second, but say so rather than ignoring them. It also makes three images in `check/`, with the zones Instagram's buttons cover shaded red.

A list of PASS lines says nothing about how the reel looks. The measurements can't see a broken object, a caption on top of the main object, or a light that looks wrong. The images are the only defence against those, so check every tile.

## 2. Judge the hook

Open `check/frame0.jpg` (the very first frame, what a muted scroller sees), `check/hook.jpg` (the first 3 seconds) and `check/sheet.jpg` (the whole reel). Read `script.json` if there is one. Score the six questions in `rubric.json`, and quote what you actually saw for each.

Be hard on it. A kind score is useless here, because the viewer won't be kind. If you made this reel, you know what the hook means, and a stranger doesn't. Score it as the stranger.

## 3. Fix what's weak

If the hook is weak by the rubric's rule, don't just report it. Fix it:

1. Write three new hooks using different shapes from `../viral-script/hooks.json`, following that skill's voice rules.
2. Run `../viral-script/lint_script.py` on them. Pick the strongest and score it again against questions 1 to 3.
3. Put it into `b1` in `script.json` and its label into the top-level `label`, keeping the old one in `hooks`.
4. If frame 0 or the first second was the problem, change the first scene in `scenes.js` as well.
5. Re-run `viral-voice`'s `voice.py` and `finish.py`, then run `check.py` again.

Do the same for technical failures. Too dark means raising the scene's light. Dead air means tightening the gaps. Captions in a red zone or on top of the main object mean moving one of them.

If the same hook stays weak after two rounds, stop and tell the user why. The idea itself may be the problem, and that's their call.

## 4. Report

Write `check/report.md` and show it:

```
READY / NEEDS A LOOK / NOT READY

Hook: 9/12. [one line on the biggest strength and the biggest risk]
Fixed: [what you changed and why, with the before and after hook]
Left for you: [anything only a human can judge, such as how the voice sounds]
Sources: [any claim that has no source yet]
```

Pick the first line by this rule:
- **NOT READY:** any `FAIL` is left, the hook is still weak after two rounds, or a claim has no source.
- **NEEDS A LOOK:** a `WARN` is unresolved, or nobody has listened to the voice yet.
- **READY:** none of the above.

Also check the `also_check` list in the rubric: sources for claims, no drawn people or animals unless asked, and a reminder about Instagram's AI label when the voice is AI. Be clear about what you couldn't check. You can't hear the audio through these tools, so a reel nobody has listened to can't be READY.

Finish with the file path and a reminder that the decision to post is theirs.
