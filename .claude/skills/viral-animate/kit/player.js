// Loads a project (script.json, timing.json, scenes.js) and exposes window.seek(t), a pure function of time.
import { makeKit } from '/kit/kit.js';

const dsf = Number(new URLSearchParams(location.search).get('dsf') || 1);
const MAX_PILL = 820; // Wider than this and a centred caption runs under Instagram's buttons.

async function main() {
  const [script, timing, mod] = await Promise.all([
    fetch('/p/script.json').then((r) => r.json()),
    fetch('/p/timing.json').then((r) => r.json()),
    import('/p/scenes.js'),
  ]);
  const style = script.style || {};
  if (style.accent) document.documentElement.style.setProperty('--accent', style.accent);
  if (style.caption_y) document.documentElement.style.setProperty('--cap-y', `${style.caption_y}px`);

  await Promise.all(['900 46px "Noto Sans Malayalam"', '700 46px "Noto Sans Malayalam"', '800 48px "JetBrains Mono"', '800 62px Inter']
    .map((f) => document.fonts.load(f, 'നമ Aa1')));

  const kit = makeKit(document.getElementById('c'), dsf);
  let current = null;
  // kit.at('charger') is the moment that word is spoken, in seconds into the current beat. Works on estimated and real timing.
  const find = (word, nth, key) => {
    const q = word.toLowerCase();
    const hits = current.words.filter((w) => w.w.toLowerCase().includes(q));
    if (!hits[nth]) throw new Error(`kit.at("${word}"): beat ${current.id} doesn't say that. Its words: ${current.words.map((w) => w.w).join(' ')}`);
    return hits[nth][key] - current.start;
  };
  kit.at = (word, nth = 0) => find(word, nth, 's');
  kit.atEnd = (word, nth = 0) => find(word, nth, 'e');

  const scenes = mod.default(kit);
  const beats = timing.beats.map((b) => {
    const def = scenes[b.id];
    if (!def) throw new Error(`scenes.js has no scene for beat "${b.id}"`);
    const group = new kit.THREE.Group();
    group.visible = false;
    kit.scene.add(group);
    return { ...b, def, group, refs: def.build(group) };
  });
  const pill = document.getElementById('pill');
  const label = document.getElementById('label');
  const labelText = (script.beats && script.beats[0] && script.beats[0].label) || script.label || '';
  const labelUntil = style.label_until ?? (timing.beats[0] ? timing.beats[0].end : 3);
  // Mono capitals for English words suit Malayalam captions. Other languages get one plain font.
  const mixed = script.language === 'ml' && !style.caption_plain;
  let shownChunk = -2, spans = [];

  window.seek = (t) => {
    const beat = beats.find((b) => t >= b.start && t < b.end) || beats[beats.length - 1];
    current = beat;
    for (const b of beats) b.group.visible = b === beat;
    const d = beat.end - beat.start;
    const local = Math.min(Math.max(t - beat.start, 0), d);
    beat.def.update(beat.refs, local, local / d, d, t);
    kit.draw();

    label.textContent = t < labelUntil ? labelText : '';
    let idx = -1;
    const cs = timing.chunks;
    for (let i = 0; i < cs.length; i++) {
      // The first caption is up from frame 0, and each stays until the next one starts.
      const from = i === 0 ? 0 : cs[i].s;
      const until = cs[i + 1] ? Math.max(cs[i].e, cs[i + 1].s) : cs[i].e + 0.3;
      if (t >= from && t < until) { idx = i; break; }
    }
    if (idx !== shownChunk) {
      shownChunk = idx;
      pill.style.display = idx < 0 ? 'none' : 'flex';
      pill.style.transform = '';
      pill.innerHTML = '';
      spans = [];
      if (idx >= 0) {
        for (const w of cs[idx].words) {
          const s = document.createElement('span');
          s.textContent = w.w;
          s.className = !mixed ? 'plain' : /[A-Za-z0-9]/.test(w.w) ? 'lat' : 'ml';
          pill.appendChild(s);
          spans.push({ s, w });
        }
        const width = pill.offsetWidth;
        if (width > MAX_PILL) pill.style.transform = `scale(${MAX_PILL / width})`;
      }
    }
    // The word being said lights up. A starred word stays lit for the rest of its caption.
    for (const { s, w } of spans) s.classList.toggle('hot', (t >= w.s && t < w.e) || (w.hot && t >= w.s) || (t < w.s && w === spans[0].w && t < cs[0].s && idx === 0));
  };
  window.seek(0);
  window.__ready = true;
}

main().catch((e) => { window.__error = e.stack || String(e); });
