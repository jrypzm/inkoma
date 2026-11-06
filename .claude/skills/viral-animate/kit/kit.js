// The drawing kit for viral-animate: rooms, light, materials, objects and motion helpers.
// Everything here is deterministic. Never call Math.random or read the clock in a scene; use kit.rng(seed) and t.
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';

export const LOOKS = {
  night: { bg: '#0a0e1c', wall: '#1d2438', floor: '#151a29', key: '#ffd7a6', keyPower: 150, fill: '#3f5fa8', fillPower: 0.55 },
  sand: { bg: '#2b2119', wall: '#c8ab8c', floor: '#a98a6c', key: '#fff0db', keyPower: 240, fill: '#ffe2c0', fillPower: 0.95 },
  ink: { bg: '#040405', wall: '#0b0b0e', floor: '#0e0e12', key: '#fff3e0', keyPower: 110, fill: '#20222e', fillPower: 0.2 },
  paper: { bg: '#d8d2c6', wall: '#ece6da', floor: '#dcd5c7', key: '#ffffff', keyPower: 200, fill: '#fff8ee', fillPower: 1.25 },
  clay: { bg: '#1c0f0a', wall: '#8c4f38', floor: '#6d3b2a', key: '#ffdcb5', keyPower: 190, fill: '#ff9f6b', fillPower: 0.55 },
};

export const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
export const lerp = (a, b, k) => a + (b - a) * k;
export const ease = {
  out: (k) => 1 - (1 - k) ** 3,
  in: (k) => k ** 3,
  inOut: (k) => (k < 0.5 ? 4 * k ** 3 : 1 - (-2 * k + 2) ** 3 / 2),
  back: (k) => 1 + 2.4 * (k - 1) ** 3 + 1.4 * (k - 1) ** 2,
  drop: (k) => { const n = 7.5625, d = 2.75; if (k < 1 / d) return n * k * k; if (k < 2 / d) return n * (k -= 1.5 / d) * k + 0.75; if (k < 2.5 / d) return n * (k -= 2.25 / d) * k + 0.9375; return n * (k -= 2.625 / d) * k + 0.984375; },
};
// prog(t, 1, 2, ease.out) runs 0 to 1 between second 1 and second 2 of the beat.
export const prog = (t, from, to, fn = ease.out) => fn(clamp((t - from) / (to - from)));

export function rng(seed = 1) {
  let s = seed >>> 0;
  return () => { s = (s + 0x6d2b79f5) >>> 0; let x = Math.imul(s ^ (s >>> 15), 1 | s); x = (x + Math.imul(x ^ (x >>> 7), 61 | x)) ^ x; return ((x ^ (x >>> 14)) >>> 0) / 4294967296; };
}

function grainTexture(hex, amount = 14, seed = 3, size = 256) {
  const c = document.createElement('canvas'); c.width = c.height = size;
  const x = c.getContext('2d'); x.fillStyle = hex; x.fillRect(0, 0, size, size);
  const im = x.getImageData(0, 0, size, size), r = rng(seed);
  for (let i = 0; i < im.data.length; i += 4) { const n = (r() - 0.5) * amount; im.data[i] += n; im.data[i + 1] += n; im.data[i + 2] += n; }
  x.putImageData(im, 0, 0);
  const t = new THREE.CanvasTexture(c); t.wrapS = t.wrapT = THREE.RepeatWrapping; t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

export function makeKit(canvas, dsf = 1) {
  const W = 1080, H = 1920;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true, powerPreference: 'high-performance' });
  renderer.setPixelRatio(dsf);
  renderer.setSize(W, H, false);
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1;
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(32, W / H, 0.05, 200);
  const target = new THREE.WebGLRenderTarget(W * dsf, H * dsf, { samples: 4, type: THREE.HalfFloatType });
  const composer = new EffectComposer(renderer, target);
  composer.setPixelRatio(dsf); composer.setSize(W, H);
  composer.addPass(new RenderPass(scene, camera));
  // Only truly bright things glow. Lower kit.bloom.strength if a scene washes out.
  const bloom = new UnrealBloomPass(new THREE.Vector2(W * dsf, H * dsf), 0.4, 0.7, 1.0);
  composer.addPass(bloom);
  composer.addPass(new OutputPass());
  // A faint reflection of a room, so metal and glass have something to reflect.
  const env = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environment = env;
  scene.environmentIntensity = 0.1;

  const clay = (color, o = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.78, metalness: 0, ...o });
  const metal = (color = '#b9b4ab', o = {}) => new THREE.MeshStandardMaterial({ color, roughness: 0.32, metalness: 0.9, envMap: env, envMapIntensity: 0.9, ...o });
  const glass = (color = '#ffffff', o = {}) => new THREE.MeshPhysicalMaterial({ color, roughness: 0.08, metalness: 0, transparent: true, opacity: 0.32, envMap: env, envMapIntensity: 0.15, ...o });
  const glow = (color, power = 2.5) => new THREE.MeshStandardMaterial({ color: '#000000', emissive: color, emissiveIntensity: power });

  const shadow = (m) => { m.castShadow = true; m.receiveShadow = true; return m; };
  const add = (g, geo, mat, x = 0, y = 0, z = 0) => { const m = shadow(new THREE.Mesh(geo, mat)); m.position.set(x, y, z); g.add(m); return m; };
  // A wrong argument order would otherwise draw nothing and say nothing.
  const need = (sig, mat) => { if (!mat || !mat.isMaterial) throw new Error(`Wrong arguments. Use ${sig}`); };
  const box = (g, w, h, d, mat, x, y, z) => { need('box(g, w, h, d, material, x, y, z)', mat); return add(g, new THREE.BoxGeometry(w, h, d), mat, x, y, z); };
  const rbox = (g, w, h, d, r, mat, x, y, z) => { need('rbox(g, w, h, d, radius, material, x, y, z)', mat); return add(g, new RoundedBoxGeometry(w, h, d, 5, Math.min(r, w / 2, h / 2, d / 2)), mat, x, y, z); };
  const sphere = (g, r, mat, x, y, z) => { need('sphere(g, radius, material, x, y, z)', mat); return add(g, new THREE.SphereGeometry(r, 48, 32), mat, x, y, z); };
  const cyl = (g, rt, rb, h, mat, x, y, z, seg = 40) => { need('cyl(g, radiusTop, radiusBottom, height, material, x, y, z)', mat); return add(g, new THREE.CylinderGeometry(rt, rb, h, seg), mat, x, y, z); };
  const torus = (g, r, tube, mat, x, y, z) => { need('torus(g, radius, tube, material, x, y, z)', mat); return add(g, new THREE.TorusGeometry(r, tube, 20, 64), mat, x, y, z); };

  const softDot = (() => {
    const c = document.createElement('canvas'); c.width = c.height = 128;
    const x = c.getContext('2d'), g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
    g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(1, 'rgba(255,255,255,0)');
    x.fillStyle = g; x.fillRect(0, 0, 128, 128);
    return new THREE.CanvasTexture(c);
  })();
  const soft = (color, opacity) => new THREE.SpriteMaterial({ map: softDot, color, transparent: true, opacity, depthWrite: false, blending: THREE.AdditiveBlending });
  // A soft pool of light on a wall or under a door. It's flat, so it can't tilt into the wall as the camera moves. spill.set(k) fades it.
  function spill(g, w, h, color = '#ffd9a8', power = 0.8) {
    const s = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ map: softDot, color, transparent: true, opacity: clamp(power), depthWrite: false, blending: THREE.AdditiveBlending })); g.add(s);
    s.set = (k) => { s.material.opacity = clamp(k * power); };
    return s;
  }
  // Steam rising from a point. steam.set(t) poses it.
  function steam(g, o = {}) {
    const r = rng(o.seed ?? 11), n = o.n ?? 12, height = o.height ?? 0.9;
    const puffs = [...Array(n)].map(() => { const s = new THREE.Sprite(soft(o.color ?? '#ffffff', 0)); g.add(s); return { s, off: r(), side: r() * 6.28, base: 0.1 + r() * 0.08 }; });
    const pos = [o.x ?? 0, o.y ?? 0, o.z ?? 0];
    return { set(t) { for (const p of puffs) { const k = (t * 0.32 + p.off) % 1; p.s.position.set(pos[0] + Math.sin(k * 5 + p.side) * 0.07 * k * 2, pos[1] + k * height, pos[2]); const size = p.base * 0.6 + k * 0.14; p.s.scale.set(size, size * 1.5, 1); p.s.material.opacity = (o.power ?? 0.1) * Math.sin(k * Math.PI); } } };
  }
  // Dust floating in the light. motes.set(t) poses it.
  function motes(g, o = {}) {
    const r = rng(o.seed ?? 21), [w, h, d] = o.box ?? [4, 3.5, 3], n = o.n ?? 40;
    const items = [...Array(n)].map(() => { const s = new THREE.Sprite(soft(o.color ?? '#ffe9c9', (o.power ?? 0.28) * (0.4 + r() * 0.6))); const size = 0.012 + r() * 0.02; s.scale.set(size, size, 1); g.add(s); return { s, x: (r() - 0.5) * w, y: r() * h, z: (r() - 0.5) * d, a: r() * 6.28 }; });
    return { set(t) { for (const m of items) m.s.position.set(m.x + Math.sin(t * 0.3 + m.a) * 0.12, ((m.y + t * 0.05) % h), m.z + Math.cos(t * 0.21 + m.a) * 0.1); } };
  }
  // Where a point lands on the 1080x1920 frame, for checking framing. Call it after cam().
  const toScreen = (x, y, z) => { const v = new THREE.Vector3(x, y, z).project(camera); return { x: Math.round((v.x + 1) / 2 * W), y: Math.round((1 - v.y) / 2 * H) }; };

  // A room: wall, floor, a soft key light from above, a fill, and fog for depth.
  function room(g, look = 'sand', o = {}) {
    const L = LOOKS[look] || LOOKS.sand;
    const fl = grainTexture(o.floorColor ?? L.floor, 12, 5); fl.repeat.set(6, 6);
    const wl = grainTexture(o.wallColor ?? L.wall, 9, 9); wl.repeat.set(5, 5);
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(60, 60), new THREE.MeshStandardMaterial({ map: fl, roughness: 0.9 }));
    floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true; g.add(floor);
    const wall = new THREE.Mesh(new THREE.PlaneGeometry(60, 40), new THREE.MeshStandardMaterial({ map: wl, roughness: 0.95 }));
    wall.position.set(0, 20, o.wallZ ?? -5); wall.receiveShadow = true; g.add(wall);
    const key = new THREE.SpotLight(L.key, o.keyPower ?? L.keyPower, 0, o.angle ?? 0.62, 0.85, 1.7);
    key.position.set(o.keyX ?? -2.2, o.keyY ?? 7.5, o.keyZ ?? 5);
    key.target.position.set(0, 0.7, 0);
    key.castShadow = true; key.shadow.mapSize.set(2048, 2048); key.shadow.bias = -0.0004; key.shadow.normalBias = 0.035; key.shadow.radius = 7; key.shadow.camera.near = 1; key.shadow.camera.far = 30;
    g.add(key, key.target);
    const fill = new THREE.HemisphereLight(L.fill, L.floor, o.fillPower ?? L.fillPower);
    g.add(fill);
    g.userData.look = L;
    return { floor, wall, key, fill, look: L };
  }

  // Set the camera for this frame. Call it from update().
  const cam = (x, y, z, tx = 0, ty = 1, tz = 0, fov = 32) => {
    camera.position.set(x, y, z); camera.lookAt(tx, ty, tz);
    if (camera.fov !== fov) { camera.fov = fov; camera.updateProjectionMatrix(); }
  };
  // Slow handheld drift. Pure function of t.
  const drift = (t, amp = 0.05) => ({ x: Math.sin(t * 0.7) * amp, y: Math.sin(t * 0.43 + 1) * amp * 0.6 });

  // A flat panel you can paint on with a canvas. paint(ctx, w, h) can run every frame for screens and counters.
  function panel(g, w, h, paint, o = {}) {
    const px = o.px ?? 560;
    const c = document.createElement('canvas'); c.width = Math.round(w * px); c.height = Math.round(h * px);
    const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 8;
    const mat = o.lit ? new THREE.MeshStandardMaterial({ map: tex, roughness: 0.7, transparent: true }) : new THREE.MeshStandardMaterial({ map: tex, emissiveMap: tex, emissive: '#ffffff', emissiveIntensity: o.power ?? 1, color: '#000000', transparent: true });
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), mat); g.add(m);
    m.paint = (fn = paint) => { const x = c.getContext('2d'); x.clearRect(0, 0, c.width, c.height); fn(x, c.width, c.height); tex.needsUpdate = true; };
    if (paint) m.paint();
    return m;
  }
  // Words painted on a sign in the scene. Malayalam shapes correctly here because the browser draws it.
  function sign(g, text, o = {}) {
    return panel(g, o.w ?? 2, o.h ?? 0.6, (x, w, h) => {
      if (o.bg) { x.fillStyle = o.bg; x.fillRect(0, 0, w, h); }
      x.fillStyle = o.color ?? '#fff'; x.textAlign = 'center'; x.textBaseline = 'middle';
      x.font = `${o.weight ?? 800} ${h * (o.size ?? 0.62)}px Inter, "Noto Sans Malayalam", sans-serif`;
      x.fillText(text, w / 2, h / 2);
    }, o);
  }

  // Objects. Each returns a Group, standing on y = 0 unless noted. No people or animals in this kit, by design.
  const O = {
    table(g, o = {}) {
      const t = new THREE.Group(); g.add(t);
      const wood = clay(o.color ?? '#5b3a25', { roughness: 0.55 });
      rbox(t, o.w ?? 3.4, 0.12, o.d ?? 1.9, 0.03, wood, 0, 0.95, 0);
      for (const [x, z] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) cyl(t, 0.05, 0.04, 0.9, wood, x * ((o.w ?? 3.4) / 2 - 0.2), 0.45, z * ((o.d ?? 1.9) / 2 - 0.2));
      t.top = 1.01;
      return t;
    },
    // The phone lies flat unless you rotate it. phone.screen.paint((ctx, w, h) => ...) draws on it.
    phone(g, o = {}) {
      const p = new THREE.Group(); g.add(p);
      rbox(p, 0.72, 1.48, 0.075, 0.08, clay(o.color ?? '#14151a', { roughness: 0.35 }));
      p.screen = panel(p, 0.67, 1.43, o.paint, { px: 800, power: o.power ?? 1 });
      p.screen.position.z = 0.0395;
      return p;
    },
    // A cable along points. cable.show(k) reveals it from the first point to the last.
    cable(g, pts, o = {}) {
      const curve = new THREE.CatmullRomCurve3(pts.map((p) => new THREE.Vector3(...p)));
      const geo = new THREE.TubeGeometry(curve, 220, o.r ?? 0.018, 10);
      const m = shadow(new THREE.Mesh(geo, clay(o.color ?? '#eeeae2', { roughness: 0.5 }))); g.add(m);
      const full = geo.index.count;
      m.show = (k) => geo.setDrawRange(0, Math.floor(clamp(k) * full));
      m.curve = curve;
      return m;
    },
    // bulb.set(k): 0 is dark, 1 is fully lit.
    bulb(g, o = {}) {
      const b = new THREE.Group(); g.add(b);
      const color = o.color ?? '#ffcf8a';
      cyl(b, 0.075, 0.09, 0.15, metal('#8e8778'), 0, 0.42, 0);
      const gl = glass('#fff6e6'); const shell = sphere(b, 0.21, gl, 0, 0.21, 0); shell.castShadow = false;
      const wire = sphere(b, 0.034, glow(color, 0), 0, 0.2, 0); wire.castShadow = false; wire.scale.set(1, 1.5, 1);
      const light = new THREE.PointLight(color, 0, 0, 1.9); light.position.set(0, 0.2, 0.05);
      light.castShadow = !!o.shadow; if (o.shadow) { light.shadow.mapSize.set(1024, 1024); light.shadow.bias = -0.002; }
      b.add(light);
      b.set = (k) => { wire.material.emissiveIntensity = k * (o.core ?? 11); light.intensity = k * (o.power ?? 16); gl.opacity = 0.28 + k * 0.2; };
      b.set(o.on ?? 0);
      return b;
    },
    // A hanging bulb with its cord, from height y.
    pendant(g, y = 4.2, o = {}) {
      const p = new THREE.Group(); g.add(p);
      cyl(p, 0.008, 0.008, 3.4, clay('#17161a'), 0, y - 0.5 + 1.7, 0, 8);
      const b = O.bulb(p, o); b.position.y = y - 1;
      p.bulb = b;
      return p;
    },
    // A wall socket with a plug. socket.seat(k) pushes the plug in as k goes 0 to 1.
    socket(g, o = {}) {
      const s = new THREE.Group(); g.add(s);
      rbox(s, 0.42, 0.42, 0.05, 0.05, clay('#e7e1d6', { roughness: 0.5 }), 0, 0, 0);
      const plug = new THREE.Group(); s.add(plug);
      rbox(plug, 0.2, 0.2, 0.16, 0.04, clay(o.color ?? '#eeeae2', { roughness: 0.5 }), 0, 0, 0.08);
      s.seat = (k) => { plug.position.z = lerp(0.65, 0.02, k); };
      s.seat(0);
      s.plug = plug;
      return s;
    },
    brick(g, o = {}) { return rbox(g, 0.62, 0.22, 0.3, 0.018, clay(o.color ?? '#a65a3c', { roughness: 0.92 })); },
    stairs(g, n = 6, o = {}) {
      const s = new THREE.Group(); g.add(s); const m = clay(o.color ?? '#d7c7ae');
      const steps = [];
      for (let i = 0; i < n; i++) steps.push(box(s, o.w ?? 1.6, 0.22, 0.5, m, 0, 0.11 + i * 0.22, -i * 0.46));
      s.steps = steps;
      return s;
    },
    candle(g, o = {}) {
      const c = new THREE.Group(); g.add(c);
      const h = o.h ?? 0.8;
      cyl(c, 0.09, 0.1, h, clay('#efe6d3', { roughness: 0.55 }), 0, h / 2, 0);
      const flame = sphere(c, 0.05, glow('#ffb35c', 9), 0, h + 0.09, 0); flame.scale.set(0.75, 1.6, 0.75); flame.castShadow = false;
      const light = new THREE.PointLight('#ffb35c', 3, 0, 1.9); light.position.y = h + 0.12; c.add(light);
      c.flicker = (t) => { const k = 0.82 + 0.18 * Math.sin(t * 13.1) * Math.sin(t * 7.3 + 1); flame.scale.y = 1.6 * k; light.intensity = 3.4 * k; };
      return c;
    },
    // clock.set(hours): the hands point at that time. Stands upright, facing the camera.
    clock(g, o = {}) {
      const c = new THREE.Group(); g.add(c);
      const r = o.r ?? 0.6;
      const f = cyl(c, r, r, 0.08, clay('#f1eadd'), 0, 0, 0); f.rotation.x = Math.PI / 2;
      const rim = torus(c, r, 0.035, clay(o.rim ?? '#2c2a29'), 0, 0, 0.03); rim.castShadow = true;
      const hand = (len, w) => { const p = new THREE.Group(); p.position.z = 0.06; c.add(p); box(p, w, len, 0.02, clay('#1b1a1a'), 0, len / 2, 0); return p; };
      const hh = hand(r * 0.5, 0.035), mh = hand(r * 0.78, 0.024);
      c.set = (h) => { hh.rotation.z = -(h / 12) * Math.PI * 2; mh.rotation.z = -h * Math.PI * 2; };
      c.set(o.h ?? 10);
      return c;
    },
    book(g, o = {}) {
      const b = new THREE.Group(); g.add(b);
      rbox(b, 0.9, 0.14, 0.66, 0.01, clay(o.color ?? '#3f5e6b'), 0, 0.07, 0);
      box(b, 0.84, 0.1, 0.62, clay('#efe8d8'), 0.03, 0.07, 0);
      return b;
    },
    mug(g, o = {}) {
      const m = new THREE.Group(); g.add(m);
      const c = clay(o.color ?? '#e9e2d5', { roughness: 0.4 });
      cyl(m, 0.16, 0.14, 0.32, c, 0, 0.16, 0);
      const tea = cyl(m, 0.145, 0.145, 0.01, clay(o.tea ?? '#7a4a22', { roughness: 0.25 }), 0, 0.3, 0); tea.castShadow = false;
      const h = torus(m, 0.08, 0.018, c, 0.18, 0.16, 0); h.rotation.y = 0;
      return m;
    },
    door(g, o = {}) {
      const d = new THREE.Group(); g.add(d);
      const fr = clay(o.frame ?? '#3b2a20');
      box(d, 0.14, 3.1, 0.16, fr, -0.86, 1.55, 0); box(d, 0.14, 3.1, 0.16, fr, 0.86, 1.55, 0); box(d, 1.86, 0.14, 0.16, fr, 0, 3.1, 0);
      // o.gap leaves a space under the door for light to come through.
      const gap = o.gap ?? 0.02;
      const hinge = new THREE.Group(); hinge.position.set(-0.79, gap, 0); d.add(hinge);
      box(hinge, 1.58, 3.02 - gap, 0.08, clay(o.color ?? '#6b4a33'), 0.79, (3.02 - gap) / 2, 0);
      sphere(hinge, 0.05, metal('#c9b27a'), 1.42, 1.45, 0.08);
      d.hinge = hinge;
      // It opens toward the camera. Build whatever is behind it yourself, because the room's wall is there otherwise.
      d.open = (k) => { hinge.rotation.y = -k * 1.5; };
      return d;
    },
    chair(g, o = {}) {
      const c = new THREE.Group(); g.add(c); const m = clay(o.color ?? '#3a2a22');
      rbox(c, 0.9, 0.1, 0.9, 0.03, m, 0, 0.82, 0); rbox(c, 0.9, 1, 0.09, 0.03, m, 0, 1.36, -0.42);
      for (const [x, z] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) box(c, 0.08, 0.8, 0.08, m, x * 0.38, 0.4, z * 0.38);
      return c;
    },
    bed(g, o = {}) {
      const b = new THREE.Group(); g.add(b);
      rbox(b, 3.4, 0.5, 2.2, 0.05, clay('#3f2f27'), 0, 0.3, 0);
      rbox(b, 3.3, 0.34, 2.1, 0.14, clay(o.sheet ?? '#d9d2c4'), 0, 0.68, 0);
      rbox(b, 0.8, 0.22, 1.5, 0.1, clay(o.pillow ?? '#f0ece3'), -1.1, 0.95, 0);
      rbox(b, 0.12, 1.5, 2.2, 0.04, clay('#3f2f27'), -1.72, 0.8, 0);
      return b;
    },
    // plant.grow(k): stem and leaves rise as k goes 0 to 1.
    plant(g, o = {}) {
      const p = new THREE.Group(); g.add(p);
      cyl(p, 0.3, 0.22, 0.42, clay('#a65a3c'), 0, 0.21, 0);
      const live = new THREE.Group(); live.position.y = 0.42; p.add(live);
      const green = clay(o.color ?? '#4f7a3f');
      const stem = cyl(live, 0.018, 0.026, 1, green, 0, 0.5, 0, 12);
      const leaves = [0, 1, 2, 3, 4, 5].map((i) => { const l = sphere(live, 0.14, green); l.scale.set(1.5, 0.22, 0.7); l.position.set(i % 2 ? 0.15 : -0.15, 0.3 + i * 0.13, 0); l.rotation.z = i % 2 ? 0.45 : -0.45; return l; });
      p.grow = (k) => { live.scale.set(1, Math.max(k, 0.001), 1); leaves.forEach((l, i) => { const s = clamp(k * 7 - i); l.visible = s > 0; }); };
      p.grow(o.k ?? 1);
      void stem;
      return p;
    },
  };

  return {
    THREE, scene, camera, renderer, bloom,
    draw: () => composer.render(),
    clamp, lerp, ease, prog, rng, LOOKS,
    clay, metal, glass, glow, shadow, add, box, rbox, sphere, cyl, torus,
    room, cam, drift, panel, sign, spill, steam, motes, toScreen, O,
  };
}
