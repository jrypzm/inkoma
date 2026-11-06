---
name: viral-script
description: Write a short faceless reel script that sounds like a real person talking, with three hooks and a picture for every line, in Malayalam, English or any language. Use when someone wants a reel script, hooks, a voiceover script, a story for a faceless account, or to turn an idea or a viral reel's structure into their own video. Second step of the viral pack, and it can run on its own. Always runs the script linter before showing anything.
---

# viral-script

## Before writing

Read `~/.claude/viral/profile.md` if it exists, and `brief.md` if a project has one. The samples of how the person really talks matter more than anything else here, so ask for two or three if there are none. If they've made reels before, their transcripts are the best source: match those, not this guide's examples. If you can't get any samples, write plain everyday speech and tell the user the script hasn't been matched to their voice yet.

Scripts in this guide live next to this file, so use their full path.

Take the skeleton from a reel that won, never its words. Change the setting, the object and the examples until the original creator wouldn't recognise it as theirs.

## Sound like a person talking

Scripts go wrong in a predictable way: they read well and sound like nobody. Write the way this person would say it to a friend.

- **Join your thoughts up.** People speak in connected sentences. "ഇതിൽ ഒരു account അവരുടെ secret തുറന്നു പറഞ്ഞിട്ടുണ്ട്" sounds like someone talking. "Camera ഇല്ല. Animator ഇല്ല. Claude മാത്രം." sounds like an AI. In Malayalam, spoken joiners do this work: അപ്പോ, പിന്നെ, എന്നിട്ട്, അതായത്, അതും.
- **Skip "not this, not that, but this".** Also "it's not X, it's Y", and lists of what isn't needed. Say what actually happens.
- **Mix languages the way people do.** Malayalam speakers say "completely free" and "post ചെയ്യും", not a pure translation. Write it as it's said, in that script.
- **Find one concrete, slightly funny detail.** "The character's hair is code." "I had tea while it worked." A single specific picture does more than three adjectives, and it's what people repeat to friends.
- **Write numbers as the profile says.** Default to millions and K, as in 6.5 million or 318K.

## Three hooks

Pick three different shapes from `hooks.json`, so you're testing three ideas and not one idea three ways. Each should:

- land the claim, number or conflict in the first sentence, which should be 14 words or fewer, and use no more than 16 words for the whole hook
- make sense to a stranger who has never heard of the account
- come with an on-screen `label` of about 6 words that a muted viewer can read on frame 0, in the language that audience reads fastest

Say which one you'd lead with and why, in one line, and save that as `lead` in the file.

## The body

Unless the user asks for another length, aim for 20 to 45 seconds, in 4 to 9 beats. Each beat is one thought and one picture. Open a question in the hook and close it near the end. Finish on a line people want to send someone, or on a clear ask if the video is selling something.

Think in pictures as you write. If a line has no picture, it probably has no point. The animation draws objects, places and type, so describe things like "a phone buzzing on a dark table". Don't write in drawn people or animals unless the user asks. Use the real-world objects that carry the feeling, because they often hit harder.

## What it may claim

Nothing makes a reel fall apart faster than a comment proving it wrong, so check before you write it down.

- Every number or factual claim needs a source you've actually seen. Put them in `sources`. If you can't source it, cut it or tell the user it's their call.
- Say who a claim comes from when you can't verify it yourself: "they say", not "it is".
- Don't promise views or results. "You can make this kind of video" is true. "You'll get millions" isn't anyone's to promise.

## Save it

Write `viral/<short-name>/script.json`. Every word lights up as it's spoken. `*Stars*` mark the few words that should stay lit for the rest of their caption.

```json
{
  "title": "One percent",
  "language": "ml",
  "label": "YOUR PHONE AT 1%",
  "lead": "Hook 1, because everyone has been at 1%.",
  "style": { "accent": "#D2E640" },
  "hooks": [
    { "shape": "The object truth", "label": "YOUR PHONE AT 1%", "say": "..." },
    { "shape": "...", "label": "...", "say": "..." },
    { "shape": "...", "label": "...", "say": "..." }
  ],
  "beats": [
    { "id": "b1", "say": "നിങ്ങളുടെ phone-ന്റെ charge *1%* ആയാൽ നിങ്ങൾ എന്ത് ചെയ്യും?", "scene": "A phone flat on a dark table, its screen red, 1% blinking." }
  ],
  "sources": [ { "claim": "...", "where": "https://..., checked 2026-11-01" } ]
}
```

Beat `b1` holds the chosen hook and nothing else, and the top-level `label` holds its label. The rest of the line starts in `b2`.

**How `say` becomes captions.** Each caption holds up to four words, and a comma or full stop always ends one, so place punctuation where you want a caption to break. Keep each caption's words short enough to fit one line. Star whole words: `*gate-ന്റെ*`, not `*gate*-ന്റെ`.

**Spelling for the voice.** Write English words the way the audience reads them, like "phone-ന്റെ". Listen first, and only respell what comes out wrong. Add `"tts": "..."` to a beat to give the voice a different spelling, ideally with the same number of words as `say`, so the caption times stay exact. The caption keeps using `say`.

## Check it

```bash
python3 lint_script.py viral/<short-name>
```

Rewrite until there are no `[FIX]` lines, then decide on each `[LOOK]`. The linter only catches patterns, so read the whole thing as if you were saying it out loud, too. If a sentence would feel odd to say to a friend, change it.

Show the user the three hooks and the body. Then wait, because they'll want to change lines and they're always right about their own voice. If they've told you to carry on without them, pick your lead hook and say which. When they change something, carry the reason into the rest of the script, not just that one line. When they're happy, point to `/viral-animate`.
