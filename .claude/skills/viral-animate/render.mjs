#!/usr/bin/env node
// node render.mjs <project> [--draft] [--stills 0,1.5,3] [--out video.mp4] [--workers 4]
// node render.mjs --doctor      checks that a browser and WebGL work
// Every frame is a pure function of time, so frames can be rendered in any order, on any worker.
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import http from 'node:http';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TK = process.env.VIRAL_TOOLKIT || path.join(os.homedir(), '.viral-agent');
const die = (m) => { console.error(m); process.exit(1); };
if (!fs.existsSync(path.join(TK, 'node_modules', 'playwright-core'))) die(`Toolkit missing. Run: bash "${path.join(HERE, 'setup.sh')}"`);
const { chromium } = createRequire(path.join(TK, 'package.json'))('playwright-core');

const argv = process.argv.slice(2);
const flag = (n) => argv.includes(n);
const opt = (n, d) => { const i = argv.indexOf(n); return i >= 0 ? argv[i + 1] : d; };
const project = argv[0] && !argv[0].startsWith('--') ? path.resolve(argv[0]) : null;

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.json': 'application/json', '.woff2': 'font/woff2', '.woff': 'font/woff', '.jpg': 'image/jpeg', '.png': 'image/png', '.css': 'text/css' };
function serve() {
  const roots = { '/kit/': path.join(HERE, 'kit'), '/vendor/': path.join(TK, 'node_modules'), '/p/': project || HERE };
  const server = http.createServer((req, res) => {
    const url = decodeURIComponent(req.url.split('?')[0]);
    if (url === '/favicon.ico') { res.writeHead(204); return res.end(); }
    const key = Object.keys(roots).find((k) => url.startsWith(k));
    const file = key && path.normalize(path.join(roots[key], url.slice(key.length)));
    if (!file || !file.startsWith(roots[key]) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream', 'cache-control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((ok) => server.listen(0, '127.0.0.1', () => ok(server)));
}

async function launch() {
  const args = ['--force-color-profile=srgb', '--ignore-gpu-blocklist', '--enable-webgl', '--enable-unsafe-swiftshader', '--hide-scrollbars'];
  if (process.platform === 'darwin') args.push('--use-angle=metal');
  // VIRAL_BROWSER=chromium skips Google Chrome and uses the downloaded browser.
  if (process.env.VIRAL_BROWSER !== 'chromium') {
    try { return await chromium.launch({ channel: 'chrome', headless: true, args }); } catch { /* no Chrome here, so fall through */ }
  }
  return chromium.launch({ headless: true, args });
}

async function openPlayer(browser, port, dsf) {
  const ctx = await browser.newContext({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: dsf });
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', (e) => errs.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !m.text().startsWith('Failed to load resource')) errs.push(m.text()); });
  page.on('response', (r) => { if (r.status() >= 400) errs.push(`${r.status()} ${r.url()}`); });
  await page.goto(`http://127.0.0.1:${port}/kit/player.html?dsf=${dsf}`);
  await page.waitForFunction('window.__ready === true || window.__error', null, { timeout: 120000 });
  const bad = await page.evaluate('window.__error || null');
  if (bad || errs.length) die(`Scene failed to load:\n${bad || ''}\n${errs.slice(0, 5).join('\n')}`);
  return { page, errs };
}

function ffmpeg(args, input) {
  const p = spawn('ffmpeg', ['-nostdin', '-v', 'error', '-y', ...args], { stdio: [input ? 'pipe' : 'ignore', 'inherit', 'inherit'] });
  return { stdin: p.stdin, done: new Promise((ok, no) => p.on('close', (c) => (c ? no(new Error(`ffmpeg exited ${c}`)) : ok()))) };
}

async function renderRange(browser, port, dsf, fps, from, to, outFile, crf, onFrame) {
  const { page, errs } = await openPlayer(browser, port, dsf);
  const enc = ffmpeg(['-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'mjpeg', '-i', '-', '-vf', 'scale=in_range=pc:out_range=tv:out_color_matrix=bt709,format=yuv420p', '-c:v', 'libx264', '-preset', 'medium', '-crf', String(crf),
    '-color_range', 'tv', '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', '-movflags', '+faststart', outFile], true);
  for (let f = from; f < to; f++) {
    await page.evaluate((t) => window.seek(t), f / fps);
    if (errs.length) die(`Stopped at frame ${f}:\n${errs.slice(0, 5).join('\n')}`);
    const buf = await page.screenshot({ type: 'jpeg', quality: 95 });
    if (!enc.stdin.write(buf)) await new Promise((ok) => enc.stdin.once('drain', ok));
    onFrame();
  }
  enc.stdin.end();
  await enc.done;
  if (errs.length) die(`Errors while rendering:\n${errs.slice(0, 5).join('\n')}`);
  await page.context().close();
}

async function doctor() {
  const server = await serve();
  const browser = await launch();
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/kit/doctor.html`);
  const r = await page.evaluate(() => {
    const gl = document.createElement('canvas').getContext('webgl2');
    if (!gl) return null;
    const d = gl.getExtension('WEBGL_debug_renderer_info');
    return d ? gl.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'webgl2';
  });
  await browser.close(); server.close();
  if (!r) die('WebGL2 is not available in this browser.');
  console.log(`browser ok, WebGL: ${r}`);
}

async function main() {
  if (flag('--doctor')) return doctor();
  if (!project) die('Usage: node render.mjs <project> [--draft] [--stills 0,1.5,3] [--out video.mp4]');
  for (const f of ['script.json', 'timing.json', 'scenes.js']) if (!fs.existsSync(path.join(project, f))) die(`${f} is missing in ${project}`);
  const timing = JSON.parse(fs.readFileSync(path.join(project, 'timing.json'), 'utf8'));
  const draft = flag('--draft');
  const dsf = draft ? 0.5 : 1;
  const fps = draft ? 15 : timing.fps || 30;
  const server = await serve();
  const port = server.address().port;
  const browser = await launch();
  const t0 = Date.now();

  const stills = opt('--stills');
  if (stills) {
    const dir = path.join(project, 'stills');
    const tmp = path.join(dir, 'frames');
    fs.rmSync(tmp, { recursive: true, force: true }); fs.mkdirSync(tmp, { recursive: true });
    const { page, errs } = await openPlayer(browser, port, 0.5);
    const isBeats = stills === 'beats';
    const times = isBeats ? timing.beats.flatMap((b) => [b.start + 0.05, (b.start + b.end) / 2, b.end - 0.05]) : stills.split(',').map(Number);
    let i = 0;
    for (const t of times) {
      await page.evaluate((x) => window.seek(x), t).catch((e) => die(`Failed at ${t} s:\n${e.message}`));
      await page.screenshot({ type: 'jpeg', quality: 90, path: path.join(tmp, `s_${String(i++).padStart(3, '0')}.jpg`) });
    }
    const cols = Math.min(times.length, 6);
    const sheet = path.join(dir, isBeats ? 'beats.jpg' : 'spot.jpg');
    await ffmpeg(['-framerate', '1', '-i', path.join(tmp, 's_%03d.jpg'), '-vf', `scale=300:-2,tile=${cols}x${Math.ceil(times.length / cols)}`, '-frames:v', '1', sheet]).done;
    if (errs.length) die(`Something in the scene is broken:\n${errs.slice(0, 5).join('\n')}`);
    console.log(sheet);
  } else {
    const total = Math.ceil(timing.duration * fps);
    const workers = Math.max(1, Math.min(Number(opt('--workers', Math.min(4, Math.floor(os.cpus().length / 2)))), total));
    const out = path.resolve(project, opt('--out', draft ? 'draft.mp4' : 'video.mp4'));
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'viral-'));
    let done = 0;
    const tick = () => { if (++done % 60 === 0) process.stdout.write(`\r${done}/${total} frames, ${Math.round((Date.now() - t0) / 1000)}s`); };
    const segs = [...Array(workers).keys()].map((k) => ({
      from: Math.floor((total * k) / workers), to: Math.floor((total * (k + 1)) / workers), file: path.join(tmp, `seg_${k}.mp4`),
    }));
    await Promise.all(segs.map((s) => renderRange(browser, port, dsf, fps, s.from, s.to, s.file, draft ? 26 : 16, tick)));
    const list = path.join(tmp, 'list.txt');
    fs.writeFileSync(list, segs.map((s) => `file '${s.file}'`).join('\n'));
    const r = spawnSync('ffmpeg', ['-nostdin', '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', '-movflags', '+faststart', out], { stdio: 'inherit' });
    if (r.status) die('Could not join the rendered segments.');
    fs.rmSync(tmp, { recursive: true, force: true });
    console.log(`\n${out}  (${total} frames in ${Math.round((Date.now() - t0) / 1000)}s)`);
  }
  await browser.close();
  server.close();
}

main().catch((e) => die(e.stack || String(e)));
