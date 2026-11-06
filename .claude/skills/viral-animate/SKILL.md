---
name: viral-animate
description: Turn a reel script into a real 3D animated faceless video written entirely in code, with no camera, no animator and no video AI, using objects, rooms, light and type. Use when someone wants to animate a script, make a faceless reel or minimalist animation, build scenes for a reel, or fix how an animated scene looks. Third step of the viral pack. Needs script.json from viral-script. Draws objects and places, not people or animals.
---

# viral-animate

Every frame is a pure function of time, drawn with Three.js in a headless browser. That's why a whole reel renders in seconds and why you can look at any moment without rendering the rest.

Scripts in this guide live next to this file, so use their full path.

## Setup, once

```bash
bash setup.sh
```
It needs Node.js 18+ and ffmpeg, and tells you if either is missing. It installs to `~/.viral-agent`.

## The loop

Work in the project folder, which has `script.json`.

1. **Estimate timing:** `python3 timing.py <project>` writes `timing.json` from the word count. The voice replaces it later with real times, so tie motion to words and beat length, never to fixed seconds.
2. **Write `scenes.js`.** One scene per beat id. Read `example/scenes.js` first. It's a complete five-beat reel with screens, cables, bulbs, falling objects and camera moves.
3. **Look at it.** `node render.mjs <project> --stills beats` draws the start, middle and end of each beat into `stills/beats.jpg` in about two seconds. Open the image and be picky. Fix, re-run, repeat. This is where the quality comes from, so most of your time goes here.
4. **Check exact moments.** `--stills 0,0.2,0.4` writes `stills/spot.jpg` and leaves `beats.jpg` alone.
5. **Watch the motion.** `node render.mjs <project> --draft` makes a fast low-res `draft.mp4`.

## Writing scenes

```js
export default (kit) => {
  const { O, room, cam, drift, prog, ease, lerp } = kit;
  return {
    b1: {
      build(g) {                          // runs once: make things, add them to g, return what update needs
        room(g, 'night');
        const table = O.table(g);
        const mug = O.mug(g); mug.position.set(0, table.top, 0);
        return { mug };
      },
      update({ mug }, t, k, d) {          // runs for every frame. t: seconds into the beat. k: t/d. d: beat length.
        mug.position.y = 1.01 + (1 - prog(t, 0, 0.5, ease.drop)) * 2;
        const dr = drift(t);
        cam(dr.x, 2 + dr.y, 6 - k * 0.5, 0, 1.2, 0);
      },
    },
  };
};
```

**Sync to words.** `kit.at('charger')` is when that word starts, in seconds into the current beat, and `kit.atEnd('charger')` is when it ends. Call them inside `update`. They search the beat's `say` text, ignoring case and `*stars*`, and part of a word is enough, so `'snooze'` finds `snooze-ൽ`. For a repeated word, the second argument counts from 0: `kit.at('reel', 1)` is the second one. It works on estimated timing and on the real voice, so the cable lands on "charger" whatever the voice does. A word the beat doesn't say stops the render with a clear message.

**Scale.** One unit is roughly a metre, and a table is about 1 tall. At the default `fov` of 32, a camera `d` units away sees about `0.57 × d` tall and `0.32 × d` wide. So at `d` = 9 the frame is about 5 tall and 2.9 wide, and a clock of `r` 0.6 fills about 40% of the width.

**Rooms.** `room(g, look, options)` makes a wall, floor and light. Looks: `night`, `sand`, `ink`, `paper`, `clay`. Options: `keyPower`, `fillPower`, `angle`, `keyX`, `keyY`, `keyZ`, `wallZ` (default -5), `wallColor`, `floorColor`. It returns `{ floor, wall, key, fill }`, so you can animate the light through `key.intensity` and `fill.intensity`.

**Objects.** `O.*(g, options)` stands on y = 0 unless noted:

| Object | Size and options | Moves with |
|---|---|---|
| `table` | `w` 3.4, `d` 1.9, `color`. Top surface is at `table.top` | |
| `phone` | 0.72 x 1.48, flat against the camera until you rotate it. `paint`, `power`, `color` | `phone.screen.paint((ctx, w, h) => ...)` |
| `cable(g, points, { r, color })` | Runs through `[x, y, z]` points | `cable.show(k)`, `cable.curve.getPoint(k)` |
| `bulb` | `power`, `core`, `color`, `shadow`, `on` | `bulb.set(k)` |
| `pendant(g, y, bulbOptions)` | Hanging bulb and cord, from height y | `pendant.bulb.set(k)` |
| `socket` | 0.42 wide. Put it on the wall | `socket.seat(k)` |
| `brick` | 0.62 x 0.22 x 0.3, `color` | |
| `stairs(g, n, { w, color })` | | `stairs.steps[i]` |
| `candle` | `h` | `candle.flicker(t)` |
| `clock` | `r`, `rim`, `h`. Upright, facing the camera. Its position is its centre, not its base | `clock.set(hours)`, in decimal hours: 6.5 is 6:30 |
| `book`, `mug` | 0.9 wide; 0.32 tall. `color` | |
| `door` | 3.1 tall, 1.86 wide. `color`, `frame`, `gap` (space under it for light) | `door.open(k)`, `door.hinge` |
| `chair`, `bed` | Bed is 3.4 wide across x, so rotate it for a portrait frame. `sheet`, `pillow` | |
| `plant` | `color`, `k` | `plant.grow(k)` |

`door.open` swings toward the camera onto the room's wall, so build whatever is behind it yourself.

**Build anything else** from these. Each takes the group first, and a wrong order stops with the right signature:

```
box(g, w, h, d, material, x, y, z)        rbox(g, w, h, d, radius, material, x, y, z)
sphere(g, radius, material, x, y, z)      cyl(g, radiusTop, radiusBottom, height, material, x, y, z)
torus(g, radius, tube, material, x, y, z)
```
Materials: `clay(color, options)`, `metal(color)`, `glass(color)`, `glow(color, power)`. Options pass straight to Three.js, such as `{ roughness: 0.3 }`.

**Words in the scene.** `panel(g, w, h, paint, { px, power, lit })` is a surface you paint with a canvas, and `paint` can run every frame. `sign(g, text, { w, h, size, color, bg, weight })` writes words: `w` and `h` are scene units (default 2 by 0.6), `size` is the letter height as a share of `h` (default 0.62), and `bg` is an optional CSS colour. Leave `bg` out for no background. Both are flat planes facing the camera's default direction, sitting at the group's centre, so set `.position` and `.rotation` yourself. Malayalam shapes correctly on both.

**Atmosphere.** These are what stop it looking like a plain clay render, so use them:
- `spill(g, w, h, color, power)`: a soft pool of light on a wall or under a door. `w` and `h` are scene units and `power` runs from 0 to 1, so start around 0.5. It's flat, so place it just in front of whatever it lights: `const s = spill(g, 3, 1.2, '#ffd9a8', 0.6); s.position.set(0, 0.2, r.wall.position.z + 0.05);`. On a floor, set `s.rotation.x = -Math.PI / 2` and lift it slightly. `spill.set(k)` fades it.
- `steam(g, { x, y, z, height, power })`, then `steam.set(t)` in update.
- `motes(g, { box: [w, h, d], n })`, then `motes.set(t)`: dust floating in the light.

**Going further.** `kit.THREE` is the full Three.js, and `kit.scene`, `kit.camera`, `kit.renderer` and `kit.bloom` are all there. Real detail, like a plate with a turned rim or rice on it, comes from building that one object properly. Spend that effort on the object the story depends on.

**Motion and camera.** `prog(t, from, to, ease.out)` runs 0 to 1 between two moments. `ease` has `out`, `in`, `inOut`, `back`, `drop`. Also `lerp`, `clamp`, and `rng(seed)` for repeatable randomness. `cam(x, y, z, targetX, targetY, targetZ, fov)` sets the camera, and `drift(t, amount)` adds a slow handheld feel. `toScreen(x, y, z)`, called after `cam`, tells you where a point lands on the 1080x1920 frame.

**Stay deterministic.** Frames render out of order on several workers, so never use `Math.random`, `Date.now` or anything that remembers the last frame. If something must accumulate, work it out from `t`.

## Captions and label

Captions and the label are drawn for you. The word being spoken lights up in the accent colour, and a `*starred*` word stays lit for the rest of its caption. In Malayalam reels, English words use capital mono letters. Other languages get one plain font, or set `"caption_plain": true` in `style`. A caption too wide for the safe area shrinks itself.

In `script.json`, `style` also takes `caption_y` (default 1240), `accent`, and `label_until` (seconds, default the end of the first beat).

## What makes it look good

- **Move in the first half second.** A buzz, a fall, a fast push-in. A still opening reads as a slideshow and people leave.
- **One idea per frame.** One object lit, everything else falling away. These reels work because they're simple enough to read in a glance on a phone.
- **Get the light right, both ways.** Too muddy: raise `fillPower` and `keyPower`. Dark looks moody on a laptop and like a black rectangle on a phone. Blown out to white: lower `keyPower` or `kit.bloom.strength`. Light objects near the key light clip first.
- **Keep clear of Instagram's buttons and the caption.** These zones are covered: the top 220 px, the bottom 380 px, and x over 950 from y 900 to 1540. The caption pill sits at y 1240, so keep the main object between y 250 and y 1200, above it, and check with `toScreen`.
- **Change something at every beat.** A new look, a new angle or a new object. Long lens and slow drift in calm moments, faster moves on turns.
- **Make the picture do what the words say, as they say it.** That's what `kit.at` is for.

## People and animals

This kit draws objects, places and type, with no people or animals, by design. Objects carry stories well: a phone at 1% says "you're running on empty" without a face. If the user asks for characters, tell them this kit doesn't include any before you build something from primitives, and let them decide.

## Hand over

When the stills and draft look right, show the sheet and point to `/viral-voice`. Real timing arrives there, and that guide sends you back here for one more look before the final render.
