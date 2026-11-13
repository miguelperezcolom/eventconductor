// Records the "draw it" scene: the REAL graph editor (eventconductor-workflow-graph in edit mode —
// the component the console serves and the VS Code / IntelliJ plugins embed) driven with the mouse,
// beside the definition it edits updating live (scenes/editor-harness.html).
//
// One screenshot per video frame (30 fps): the mouse moves a step, the page reacts, the frame is
// taken — so the result is smooth however fast the editor answers. A drawn cursor and click pulses
// are added to the page (they are not part of the editor). → build/editor/00001.jpg …, and
// build/editor/meta.json with the frame count.
import { chromium } from 'playwright';
import { mkdirSync, rmSync, writeFileSync, copyFileSync } from 'node:fs';
import { serve } from './static-server.mjs';

const FPS = 30, out = 'build/editor';
rmSync(out, { recursive: true, force: true }); mkdirSync(out, { recursive: true });
const srv = await serve(8433);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1.5, colorScheme: 'dark' });
page.on('pageerror', (e) => { throw e; });
await page.goto('http://127.0.0.1:8433/media/promo-video/scenes/editor-harness.html?unset=onIterationFailure');
await page.waitForFunction(() => window.harnessReady);
await page.waitForTimeout(2000);

// The drawn cursor and the click pulse, above everything.
await page.evaluate(() => {
  const c = document.createElement('div');
  c.id = 'promo-cursor';
  c.innerHTML = '<svg viewBox="0 0 24 24" width="26" height="26"><path d="M3 2 L3 20 L8 15.5 L11.5 22.5 L14.6 21 L11.2 14.2 L18 14.2 Z" fill="#fff" stroke="#0b1020" stroke-width="1.6" stroke-linejoin="round"/></svg>';
  Object.assign(c.style, { position: 'fixed', left: '0', top: '0', zIndex: 2147483647, pointerEvents: 'none', filter: 'drop-shadow(0 3px 6px rgba(0,0,0,.6))' });
  const r = document.createElement('div');
  r.id = 'promo-pulse';
  Object.assign(r.style, { position: 'fixed', zIndex: 2147483646, pointerEvents: 'none', borderRadius: '50%', border: '3px solid #38bdf8', opacity: '0' });
  document.body.append(r, c);
});

let frame = 0, last = null, mx = 640, my = 600, pulseAt = -1, pulseDouble = false;
async function snap() {
  await page.evaluate(([x, y, f, at, dbl]) => {
    // A shift-drag selects text on its way; no frame should show that.
    getSelection().removeAllRanges();
    document.querySelector('eventconductor-workflow-graph')?.shadowRoot?.getSelection?.()?.removeAllRanges();
    const c = document.getElementById('promo-cursor'); c.style.transform = `translate(${x - 2}px, ${y - 2}px)`;
    const r = document.getElementById('promo-pulse');
    let q = at < 0 ? 1 : (f - at) / 16;
    if (dbl && q > .45 && q < 1) q = (q - .45) / .55;
    if (q >= 0 && q < 1) { const rad = 6 + 28 * (1 - Math.pow(1 - q, 3)); Object.assign(r.style, { left: x - rad + 'px', top: y - rad + 'px', width: 2 * rad + 'px', height: 2 * rad + 'px', opacity: String(1 - q) }); }
    else r.style.opacity = '0';
  }, [mx, my, frame, pulseAt, pulseDouble]);
  const p = `${out}/${String(++frame).padStart(5, '0')}.jpg`;
  await page.screenshot({ path: p, type: 'jpeg', quality: 92 });
  last = p;
}
async function hold(sec) { const n = Math.round(sec * FPS); for (let i = 0; i < n; i++) { if (pulseAt >= 0 && frame - pulseAt < 24) await snap(); else copyFileSync(last, `${out}/${String(++frame).padStart(5, '0')}.jpg`); } }
const ease = (p) => (p < .5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2);
async function moveTo(x, y, sec) {
  const n = Math.max(1, Math.round(sec * FPS)), x0 = mx, y0 = my;
  for (let i = 1; i <= n; i++) { mx = x0 + (x - x0) * ease(i / n); my = y0 + (y - y0) * ease(i / n); await page.mouse.move(mx, my); await snap(); }
}
async function click(dbl = false) {
  pulseAt = frame; pulseDouble = dbl;
  if (dbl) await page.mouse.dblclick(mx, my); else await page.mouse.click(mx, my);
  await page.waitForTimeout(350); await snap();
}
const g = page.locator('eventconductor-workflow-graph');
const node = (text) => g.locator('g.node').filter({ hasText: text }).first();
const centre = async (loc) => { const b = await loc.boundingBox(); return [b.x + b.width / 2, b.y + b.height / 2]; };
async function dragTo(x, y, sec, shift = false) {
  if (shift) await page.keyboard.down('Shift');
  await page.mouse.down(); await moveTo(x, y, sec); await page.mouse.up();
  if (shift) await page.keyboard.up('Shift');
  await page.waitForTimeout(500); await snap();
}

await snap(); await hold(.6);

// 1. Drag a USER_TASK from the palette onto the canvas.
const [ux, uy] = await centre(g.locator('.palette-item').filter({ hasText: 'USER_TASK' }));
await moveTo(ux - 30, uy, .9); await hold(.15);
await dragTo(470, 575, 1.0);
await hold(.3);

// 2. Name it in the properties panel.
const name = g.locator('input').nth(2);
const [nx, ny] = await centre(name);
await moveTo(nx, ny, .5); pulseAt = frame; pulseDouble = false;
await name.click({ clickCount: 3 }); await snap();
for (const ch of 'Manager approval') { await page.keyboard.type(ch); await snap(); }
await page.waitForTimeout(300); await snap(); await hold(.3);
// Close the properties panel with its ✕.
async function closePanel() {
  const x = g.locator('button.close-btn:visible').first();
  if (await x.count()) { const [qx, qy] = await centre(x); await moveTo(qx, qy, .45); await click(); await page.waitForTimeout(250); await snap(); }
}
await closePanel(); await hold(.2);

// 3. Link it after the rooms: shift + drag from one step to the other.
const [rx, ry] = await centre(node('Book every room'));
const [ax, ay] = await centre(node('Manager approval'));
await moveTo(rx, ry, .6);
await dragTo(ax, ay, .8, true);
await hold(.5);

// 4. The failure policy: select the rooms step, pick compensateAll.
await moveTo(rx, ry, .4); await click(); await hold(.2);
const policy = g.locator('select').filter({ has: page.locator('option[value="compensateAll"]') }).first();
await policy.scrollIntoViewIfNeeded(); await page.waitForTimeout(250); await snap();
const [px, py] = await centre(policy);
await moveTo(px, py, .6); await click();
await policy.selectOption('compensateAll'); await page.waitForTimeout(300); await snap();
await hold(.8);

// 5. Into the inline body: double-click the PROCESS, add a step, link it, back out.
await moveTo(rx, ry, .5); await click(true); await page.waitForTimeout(600); await snap(); await hold(.5);
const [kx, ky] = await centre(node('Confirm'));
const [cx, cy] = await centre(g.locator('.palette-item').filter({ hasText: /^\s*ACTION\s*$/ }));
await moveTo(cx - 30, cy, .7);
await dragTo(kx, ky + 150, .9);
await closePanel(); await hold(.15);
const fresh = node('New Action');
const [fx, fy] = await centre(fresh);
await moveTo(kx, ky, .5);
await dragTo(fx, fy, .7, true);
await hold(.5);
const back = g.getByText('Back', { exact: false }).first();
const [bx, by] = await centre(back);
await moveTo(bx, by, .6); await click(); await page.waitForTimeout(500); await snap();
await moveTo(1100, 680, .6); await hold(.8);

writeFileSync(`${out}/meta.json`, JSON.stringify({ frames: frame, fps: FPS }));
console.log('editor frames', frame, (frame / FPS).toFixed(1) + 's');
await browser.close(); srv.close();
