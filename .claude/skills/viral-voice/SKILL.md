---
name: viral-voice
description: Add the voiceover, word-timed captions and sound effects to a faceless reel and produce the finished video. Works with a free AI voice (Malayalam, English, Hindi, Tamil and many more), ElevenLabs, or the user's own recording. Use when someone wants a voiceover, captions or subtitles on an animated reel, sound effects, or to finish, render or export a reel made with viral-animate. Fourth step of the viral pack.
---

# viral-voice

Run setup once: `bash setup.sh`. Scripts in this guide live next to this file, so use their full path.

## 1. Choose a voice

Ask which they want, and be straight about the trade-offs:

| Option | Cost | Quality | Caption timing |
|---|---|---|---|
| **Free voice** (default) | Free | Clear, a bit flat | Exact, each word lights up as it's said |
| **ElevenLabs** | Their paid plan or free credits | Most natural | Exact when the model gives timestamps |
| **Their own voice** | Free | The most personal | Estimated within each line. Tighter if recorded per beat |

Their own voice usually performs best, because people can hear when someone means it. A faceless reel doesn't have to be voiceless.

```bash
# Free voice. Picks Midhun for Malayalam. --voice ml-IN-SobhanaNeural for the other Malayalam voice.
python3 voice.py <project>

# ElevenLabs. Needs ELEVENLABS_API_KEY and a voice id. Malayalam needs a model that lists it, such as eleven_v3.
python3 voice.py <project> --engine elevenlabs --voice <voice id> --model eleven_v3

# Their own recording, one take of the whole script in order
python3 voice.py <project> --engine file --audio take.m4a

# Their own recording, one file per beat (voice/b1.m4a, voice/b2.m4a). Timing is tighter this way.
python3 voice.py <project> --engine file --audio voice/
```

Audio from any other tool works through `--engine file` as well, so if a speech-generation tool is connected, generate one file per beat and pass the folder.

For recordings, each line is timed by word length between pauses, so a caption can land up to about a second off in a single long take. On our test with a real 48-second Malayalam reel, most captions landed within half a second. If it looks off, record per beat.

The free voice runs on Microsoft's Edge read-aloud service through the open-source `edge-tts` tool. It isn't an official API, so it could change or stop working, and anyone using it for client or commercial work should check the terms first. Say this if the user is making videos for a business.

## 2. Someone has to listen

You can't hear audio through these tools, and `timing.json` only shows the words from the script, never what the voice actually said. So nothing here can confirm the pronunciation. Ask the user to listen to `voice.wav`, or if a transcription tool is connected, transcribe it and compare. Say plainly that it's unheard if neither happens.

When the voice says a word wrongly, add `"tts"` to that beat in `script.json` with a spelling it says correctly, and run it again. The caption keeps using `say`. Keep the same number of words in `tts` as in `say` when you can. If they differ, `voice.py` prints a note and that beat's caption times become an estimate.

If a line sounds rushed, change `--rate` (default `+8%`). `--gap` sets the pause between beats.

The length estimates from `lint_script.py` and `timing.py` are only rough, and can run 10 to 15% short. The real length is what `voice.py` prints. To hit a target, change `--rate` or cut words, then run it again.

## 3. Look at the picture again

The real voice has moved everything, so look before the final render:

```bash
node <viral-animate folder>/render.mjs <project> --stills beats
```

Open `stills/beats.jpg`. Motion tied to `k`, `d` or `kit.at` has followed the voice. Anything with fixed seconds in it hasn't, so fix those in `scenes.js`.

## 4. Sound effects

`sfx.py` runs automatically: a low thump at the start and a quiet whoosh on each beat change. For a quiet, emotional piece, turn those off with `"sfx_auto": false` in `script.json`. To add more, list them, on a word so they stay in sync:

```json
"sfx": [{ "beat": "b2", "word": "charger", "kind": "pop", "db": -14 }]
```

Use `"at": 1.1` instead of `word` for seconds into the beat. A word the beat doesn't say stops with a clear message. Kinds:

| Kind | Sounds like | Good for |
|---|---|---|
| `whoosh` `swell` | Rising air | Changes, things flying in |
| `swipe` | Quick brush of air | Fast moves |
| `pop` | Small round pop | Things appearing |
| `tick` | Tiny dry tick | Counters, small moments |
| `click` | Sharp switch click | Light switches, buttons |
| `knock` | Knuckle on wood | Doors, tables |
| `thump` | Low hit | Openings, big reveals |
| `stamp` | Hard slap with weight | Verdicts, landing a point |

They're all synthesized, so there's no licensing risk. The pack adds no music: it keeps the voice clear and avoids copyright strikes. If the user wants music, they can add it in Instagram's editor.

## 5. Finish

```bash
python3 finish.py <project>
```

This renders the full-quality picture on the real voice timing, mixes in the effects, sets the loudness to about -14 LUFS and writes `final.mp4`. Add `--no-render` to remix sound only.

**Trial versions:** to test the other hooks, copy the project folder, put another hook's `say` into `b1` and its `label` into the top-level `label`, then run `voice.py` and `finish.py` on the copy. Motion tied to `k`, `d` or `kit.at` follows. Check that any `kit.at` word is still in the new hook.

Then go to `/viral-check`. Don't call the reel done before that.
