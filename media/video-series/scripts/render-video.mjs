// Renders a chapter master frame by frame (the promo's render-animated.mjs, generalised): the player
// page (scenes/player.html) exposes render(t); for every frame this sets t, waits for any recorded
// console frame to decode, screenshots 1920x1080 and pipes the PNG to ffmpeg. Deterministic.
//
//   node scripts/render-video.mjs <NN> <lang>                    # → build/chNN/<lang>/video.mp4 (silent)
//   node scripts/render-video.mjs <NN> <lang> --stills 3,12.5    # single frames → build/chNN/<lang>/stills/
//   node scripts/render-video.mjs <NN> <lang> --from 38 --to 55  # a range
//   node scripts/render-video.mjs <NN> <lang> --thumb            # the thumbnail still → build/chNN/thumb.png
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { readFileSync, mkdirSync, existsSync, writeFileSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(here, '../..');
const [chapter, lang] = process.argv.slice(2);
const arg = (name, dflt) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : dflt; };
const flag = (name) => process.argv.includes(`--${name}`);
const FPS = 30;
const chDir = resolve(here, `chapters/${chapter}`);
const build = resolve(here, `build/ch${chapter}/${lang}`);
const read = (p) => readFileSync(p, 'utf8');
const json = (p) => JSON.parse(read(p));

const script = json(`${chDir}/script.json`);
const strings = json(`${chDir}/strings.${lang}.json`);
const svg = read(resolve(repo, 'doc/public/favicon.svg'));
const logo = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '');

/** A real file's excerpt: { file, from, to } — line numbers (1-based) or regexes (first match; `to` after `from`). */
/** Definition files may be named *.ec.yaml (preferred), *.yaml or *.ec: a script names one, the real one is used. */
export function resolveFile(file) {
  const base = file.replace(/\.ec\.yaml$|\.yaml$|\.ec$/, '');
  const options = base === file ? [file] : [`${base}.ec.yaml`, `${base}.yaml`, `${base}.ec`];
  const found = options.find((f) => existsSync(resolve(repo, f)));
  if (!found) throw new Error(`no file ${options.join(' | ')}`);
  return found;
}
export function excerpt(spec) {
  spec = { ...spec, file: resolveFile(spec.file) };
  const all = read(resolve(repo, spec.file)).replace(/\n$/, '').split('\n');
  const find = (v, start) => { if (typeof v === 'number') return v - 1; const i = all.findIndex((l, j) => j >= start && new RegExp(v).test(l)); if (i < 0) throw new Error(`${spec.file}: no line /${v}/`); return i; };
  const a = spec.from == null ? 0 : find(spec.from, 0);
  const b = spec.to == null ? all.length - 1 : find(spec.to, a + 1);
  return { file: spec.file, first: a + 1, lines: all.slice(a, b + 1) };
}

function data() {
  if (flag('thumb')) return { chapter: script.chapter, lang, strings, logo, duration: 1, scenes: [{ id: 'thumb', view: 'thumb', spec: {}, start: 0, dur: 1, cues: [] }], code: {}, term: {}, live: {} };
  const tl = json(`${build}/timeline.json`);
  const byId = Object.fromEntries(script.scenes.map((s) => [s.id, s]));
  const scenes = tl.scenes.map((s) => ({ ...s, view: byId[s.id].view, spec: byId[s.id].spec || {} }));
  const code = {}, term = {}, live = {};
  for (const s of script.scenes) if (s.code) code[s.id] = excerpt(s.code);
  for (const dir of [`${build}/term`, resolve(here, `build/ch${chapter}/term`)]) {
    if (!existsSync(dir)) continue;
    for (const f of readdirSync(dir)) if (f.endsWith('.json')) term[f.replace(/\.json$/, '')] ??= json(`${dir}/${f}`);
  }
  for (const s of scenes) if (s.view === 'live') {
    const idx = `${build}/live/${s.id}/index.json`;
    if (!existsSync(idx)) throw new Error(`missing ${idx}: run record-live.mjs ${chapter} ${lang}`);
    { const ix = json(idx); live[s.id] = { dir: `../build/ch${chapter}/${lang}/live/${s.id}`, viewport: ix.viewport, frames: ix.frames }; }
  }
  // The console window's title and the terminal prompt: the chapter's app (script.json `port`, `prompt`), else the workshop's.
  const host = script.port ? `localhost:${script.port}` : undefined;
  return { chapter: script.chapter, lang, strings, logo, duration: tl.duration, scenes, code, term, live, host, prompt: script.prompt };
}

const DATA = data();
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
page.on('pageerror', (e) => { console.error('page error:', e.message); process.exitCode = 1; });
await page.addInitScript((d) => { window.DATA = d; }, DATA);
await page.goto('file://' + resolve(here, 'scenes/player.html'));
await page.addScriptTag({ path: resolve(here, 'scenes/views.js') });
if (existsSync(`${chDir}/scenes.js`)) await page.addScriptTag({ path: `${chDir}/scenes.js` });
await page.addScriptTag({ path: resolve(here, 'scenes/series.js') });
if (process.exitCode) throw new Error('the player failed to boot');
await page.evaluate(() => window.ready);
await page.evaluate(() => window.render(0));
await page.waitForTimeout(300);

async function frameAt(t) {
  await page.evaluate((tt) => window.render(tt), t);
  await page.evaluate(() => window.pending);
  return page.screenshot({ type: 'png', animations: 'disabled' });
}

if (flag('thumb')) {
  const p = resolve(here, `build/ch${chapter}/thumb-${lang}.png`);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, await frameAt(0.5));
  console.log('thumb', p);
  await browser.close(); process.exit(0);
}

const stills = arg('stills', null);
if (stills) {
  mkdirSync(`${build}/stills`, { recursive: true });
  for (const s of stills.split(',').map(Number)) {
    const p = `${build}/stills/t${s.toFixed(2).padStart(7, '0')}.png`;
    writeFileSync(p, await frameAt(s));
    console.log('still', p);
  }
  await browser.close(); process.exit(0);
}

const from = Number(arg('from', 0)), to = Number(arg('to', DATA.duration));
const out = resolve(here, arg('out', `${build}/video.mp4`));
mkdirSync(dirname(out), { recursive: true });
// Near-lossless intermediate (yuv444p, CRF 8); the final encode happens in assemble.sh.
const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
  '-c:v', 'libx264', '-preset', 'medium', '-crf', '8', '-pix_fmt', 'yuv444p', '-r', String(FPS), out], { stdio: ['pipe', 'inherit', 'inherit'] });
const first = Math.round(from * FPS), last = Math.round(to * FPS);
const started = Date.now();
for (let f = first; f < last; f++) {
  const png = await frameAt(f / FPS);
  if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once('drain', r));
  if ((f - first) % (FPS * 10) === 0) console.log(`  t=${(f / FPS).toFixed(0)}s  ${(((f - first) / (last - first)) * 100).toFixed(0)}%  ${((Date.now() - started) / 1000).toFixed(0)}s`);
}
ff.stdin.end();
await new Promise((r) => ff.on('close', r));
await browser.close();
console.log('video', out, `${((Date.now() - started) / 1000).toFixed(0)}s`);
