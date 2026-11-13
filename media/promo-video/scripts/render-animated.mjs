// Renders the promo video frame by frame: scenes/index.html exposes render(t); for every frame
// this sets t, screenshots the page at 1920x1080 and pipes the PNG to ffmpeg. Deterministic —
// nothing on the page moves on its own — so the same inputs always give the same video.
//
//   node scripts/render-animated.mjs                       # whole video → build/master.mp4
//   node scripts/render-animated.mjs --from 38 --to 55     # a range (for iterating on a scene)
//   node scripts/render-animated.mjs --stills 3,12,21      # single frames → build/stills/*.png
//   --strings strings.es.json                              # another language
//
// Inputs: strings.json, app/definitions/hotel-booking.yaml, build/real/* (record-real.mjs),
// build/out/*.txt (capture-terminal.sh), doc/public/favicon.svg (the logo).
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, dflt) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : dflt; };
const FPS = Number(arg('fps', 30));
const from = Number(arg('from', 0));
const stills = arg('stills', null);
const out = resolve(here, arg('out', 'build/master.mp4'));
const scenePage = arg('page', 'scenes/index.html');            // or scenes/opening.html

const read = (p) => readFileSync(resolve(here, p), 'utf8');
const lines = (p) => read(p).split('\n').filter((l) => l.trim().length > 0);
for (const need of ['build/real/boxes.json', 'build/out/test-green.txt', 'build/editor/meta.json']) {
  if (!existsSync(resolve(here, need))) throw new Error(`missing ${need}: run scripts/capture-real.sh, capture-terminal.sh and record-editor.mjs first`);
}

// The definition as typed in the editor: the real file, from its top through the inline body,
// then one comment standing for the three steps left out (they are in the file, not on screen).
const def = read('app/definitions/hotel-booking.yaml').split('\n');
const cut = def.findIndex((l) => /^\s+- id: notify/.test(l));
const yaml = [...def.slice(0, cut).filter((l, i, a) => !(l.trim() === '' && i === a.length - 1)), '  # … notify, done, cancelRoom'].join('\n');
const shown = yaml.split('\n');
const yamlEmphasis = shown.map((l, i) => (/forEach:|onIterationFailure|label:|^\s{4}steps:|compensationStepId/.test(l) ? i : -1)).filter((i) => i >= 0);

const svg = read('../../doc/public/favicon.svg');
const logo = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '').replace(/<style>[\s\S]*?<\/style>/g, ''); // its own colours off: the outro paints it

const DATA = {
  strings: JSON.parse(read(arg('strings', 'strings.json'))),
  yaml, yamlEmphasis, logo,
  boxes: JSON.parse(read('build/real/boxes.json')),
  drawFrames: JSON.parse(read('build/editor/meta.json')).frames,
  out: {
    validateGreen: lines('build/out/validate-green.txt').map((l) => l.replace(/^\[INFO\] /, '')),
    testGreen: lines('build/out/test-green.txt'),
    testRed: lines('build/out/test-red.txt'),
  },
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
await page.addInitScript((d) => { window.DATA = d; }, DATA);
await page.goto('file://' + resolve(here, scenePage));
await page.evaluate(() => window.ready);
await page.evaluate(() => window.render(0));
await page.waitForTimeout(300);

const to = Number(arg('to', await page.evaluate(() => window.DURATION)));
async function frameAt(t) {
  await page.evaluate((tt) => window.render(tt), t);
  await page.evaluate(() => window.pending);      // a recorded frame still decoding
  return page.screenshot({ type: 'png', animations: 'disabled' });
}

if (stills) {
  mkdirSync(resolve(here, 'build/stills'), { recursive: true });
  for (const s of stills.split(',').map(Number)) {
    const png = await frameAt(s);
    const p = resolve(here, `build/stills/t${String(s).padStart(5, '0')}.png`);
    (await import('node:fs')).writeFileSync(p, png);
    console.log('still', p);
  }
  await browser.close();
  process.exit(0);
}

mkdirSync(dirname(out), { recursive: true });
// Master: H.264 in yuv444p at a very low CRF — near-lossless, so the final encodes start clean.
const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
  '-c:v', 'libx264', '-preset', 'medium', '-crf', '8', '-pix_fmt', 'yuv444p', '-r', String(FPS), out], { stdio: ['pipe', 'inherit', 'inherit'] });
const first = Math.round(from * FPS), last = Math.round(to * FPS);
const started = Date.now();
for (let f = first; f < last; f++) {
  const png = await frameAt(f / FPS);
  if (!ff.stdin.write(png)) await new Promise((r) => ff.stdin.once('drain', r));
  if ((f - first) % (FPS * 5) === 0) console.log(`t=${(f / FPS).toFixed(1)}s  ${(((f - first) / (last - first)) * 100).toFixed(0)}%  ${((Date.now() - started) / 1000).toFixed(0)}s`);
}
ff.stdin.end();
await new Promise((r) => ff.on('close', r));
await browser.close();
console.log('master', out, `${((Date.now() - started) / 1000).toFixed(0)}s`);
