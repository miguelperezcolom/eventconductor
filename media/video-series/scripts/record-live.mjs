// Records the REAL console, frame by frame, for every live scene of a chapter in one language.
//
//   node scripts/record-live.mjs <chapter NN> <lang>
//
// Reads build/chNN/<lang>/timeline.json (scene lengths and sentence cues from the narration) and runs
// chapters/NN/live.mjs: a script that drives the running app — real commands over its REST API
// (captured as terminal output for the terminal scenes) and real console pages filmed with Playwright.
//
// Filming (rule 3): the page's clock is installed and PAUSED (page.clock.install + pauseAt); every
// video frame advances it exactly 1/30 s (clock.runFor) and takes one screenshot (1920x1080 CSS px,
// deviceScaleFactor 2, so the compositor's camera can zoom without blur). CSS animations are slowed
// through CDP Animation.setPlaybackRate so that they advance about one frame per screenshot. The
// pointer is a cursor overlay injected into the page that follows Playwright's real mouse (moves,
// hovers and clicks are real); the click ring is drawn per frame, so it is deterministic too.
//
// Output per live scene: build/chNN/<lang>/live/<scene>/NNNNN.jpg + index.json
//   { fps, viewport, scale, frames: [{ f: "00012.jpg", cam: { cx, cy, z } }] }  (cam in CSS px)
// Terminal output: build/chNN/<lang>/term/<id>.json { cmd, cwd, lines }.
import { chromium, _electron as electron } from 'playwright';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync, cpSync } from 'node:fs';
import { resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(here, '../..');
const [chapter, lang] = process.argv.slice(2);
const build = resolve(here, `build/ch${chapter}/${lang}`);
const FPS = 30, VW = 1920, VH = 1080, SCALE = Number(process.env.LIVE_SCALE || 2);
const BASE = process.env.ENGINE || `http://localhost:${process.env.PORT || 8095}`;
const PORT = new URL(BASE).port;
const tl = JSON.parse(readFileSync(`${build}/timeline.json`, 'utf8'));
const scenes = Object.fromEntries(tl.scenes.map((s) => [s.id, s]));
const only = process.env.ONLY ? new Set(process.env.ONLY.split(',')) : null;

const eio = (p) => (p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2);
const lerp = (a, b, p) => a + (b - a) * p;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** The cursor overlay: UI chrome, not product. Follows the real mouse; the click ring is set per frame. */
const CURSOR = () => {
  const install = () => {
    if (document.getElementById('__rec_cursor')) return;
    const c = document.createElement('div');
    c.id = '__rec_cursor';
    // Built node by node, not with innerHTML: VS Code's workbench enforces Trusted Types.
    const ringEl = document.createElement('div'); ringEl.id = '__rec_ring'; c.appendChild(ringEl);
    const NS = 'http://www.w3.org/2000/svg', svg = document.createElementNS(NS, 'svg'), path = document.createElementNS(NS, 'path');
    for (const [k, v] of [['width', '30'], ['height', '30'], ['viewBox', '0 0 30 30']]) svg.setAttribute(k, v);
    for (const [k, v] of [['d', 'M3 2 L3 24 L9 18.5 L13.5 28 L17.5 26.2 L13 17 L21 17 Z'], ['fill', '#fff'], ['stroke', '#0b1620'], ['stroke-width', '1.8'], ['stroke-linejoin', 'round']]) path.setAttribute(k, v);
    svg.appendChild(path); c.appendChild(svg);
    Object.assign(c.style, { position: 'fixed', left: '-100px', top: '-100px', width: '30px', height: '30px', zIndex: 2147483647, pointerEvents: 'none', filter: 'drop-shadow(0 3px 5px rgba(0,0,0,.55))' });
    const ring = c.querySelector('#__rec_ring');
    Object.assign(ring.style, { position: 'absolute', left: '-22px', top: '-22px', width: '44px', height: '44px', borderRadius: '50%', border: '3px solid #4cb2ff', opacity: 0 });
    document.documentElement.appendChild(c);
    addEventListener('mousemove', (e) => { c.style.left = e.clientX - 3 + 'px'; c.style.top = e.clientY - 2 + 'px'; }, true);
  };
  // Set per frame by the recorder too: mouse events over an iframe (a VS Code webview) never reach this window.
  window.__recPos = (x, y) => { install(); const c = document.getElementById('__rec_cursor'); if (c) { c.style.left = x - 3 + 'px'; c.style.top = y - 2 + 'px'; } };
  window.__recRing = (p) => { const r = document.getElementById('__rec_ring'); if (!r) return; r.style.opacity = p <= 0 || p >= 1 ? 0 : String(1 - p); r.style.transform = `scale(${0.4 + p * 1.2})`; };
  window.__recShow = (v) => { const c = document.getElementById('__rec_cursor'); if (c) c.style.display = v ? 'block' : 'none'; };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install); else install();
};

/** A scene time: seconds, or 'cN' / 'eN' (sentence N starts / ends) with an optional +/- offset. */
const sceneTime = (scene, spec) => {
  if (spec == null) return null;
  if (typeof spec === 'number') return spec;
  const m = String(spec).match(/^(c|e)(-?\d+)([+-][\d.]+)?$/);
  if (!m) throw new Error(`bad scene time ${spec}`);
  let i = +m[2]; if (i < 0) i += scene.cues.length;
  const cue = scene.cues[Math.max(0, Math.min(i, scene.cues.length - 1))];
  return (m[1] === 'c' ? cue.start : cue.end) + (m[3] ? parseFloat(m[3]) : 0);
};

class Shot {
  /** Films [from, end) of the scene (seconds); a scene may be filmed in parts, e.g. before and after a command. */
  constructor(page, scene, dir, vw, vh, from = 0, end = scene.dur) {
    this.page = page; this.scene = scene; this.dir = dir; this.frames = []; this.f = Math.round(from * FPS); this.end = end; this.vw = vw; this.vh = vh;
    this.mouse = { x: vw * 0.62, y: vh * 0.72 };
    this.cam = { cx: vw / 2, cy: vh / 2, z: 1 };
    this.camAnim = null; this.ring = null; this.cursor = true;
  }
  get t() { return this.f / FPS; }
  get dur() { return this.end; }
  cue(i) { const c = this.scene.cues[i < 0 ? this.scene.cues.length + i : i]; return c ? c.start : this.dur; }

  /** One video frame: advance the paused clock 1/30 s, update overlays, screenshot. */
  async frame() {
    const p = this.page;
    await p.clock.runFor(1000 / FPS);
    if (this.camAnim) {
      const a = this.camAnim, q = eio(Math.min(1, (this.f - a.f0) / a.n));
      this.cam = { cx: lerp(a.from.cx, a.to.cx, q), cy: lerp(a.from.cy, a.to.cy, q), z: lerp(a.from.z, a.to.z, q) };
      if (q >= 1) this.camAnim = null;
    }
    const ringP = this.ring ? (this.f - this.ring) / 12 : 1;
    await p.evaluate(([rp, show, x, y]) => { window.__recPos?.(x, y); window.__recRing?.(rp); window.__recShow?.(show); }, [ringP, this.cursor, this.mouse.x, this.mouse.y]);
    if (ringP >= 1) this.ring = null;
    const name = String(this.f).padStart(5, '0') + '.jpg';
    await p.screenshot({ path: `${this.dir}/${name}`, type: 'jpeg', quality: 90, animations: 'allow', caret: 'initial', timeout: 180000 });
    this.frames.push({ f: name, cam: { ...this.cam } });
    this.f++;
  }
  /** Hold until scene time t (seconds), filming. */
  async until(t) { while (this.t < Math.min(t, this.end) - 1e-6) await this.frame(); }
  async hold(s) { await this.until(this.t + s); }
  async untilCue(i, offset = 0) { await this.until(this.cue(i) + offset); }
  async toEnd() { await this.until(this.end); }

  async box(target) {
    const loc = typeof target === 'string' ? this.page.locator(target).first() : target;
    await loc.waitFor({ state: 'visible', timeout: 60000 });
    const b = await loc.boundingBox();
    if (!b) throw new Error(`no box for ${target}`);
    return b;
  }
  /** Move the real mouse along an eased path to the target's centre, over `dur` seconds. */
  async moveTo(target, dur = 0.8, dx = 0, dy = 0) {
    const b = typeof target.x === 'number' ? { x: target.x, y: target.y, width: target.width ?? 0, height: target.height ?? 0 } : await this.box(target);   // a point, a box, or a locator
    const to = { x: b.x + b.width / 2 + dx, y: b.y + b.height / 2 + dy }, from = { ...this.mouse };
    const n = Math.max(1, Math.round(dur * FPS));
    for (let i = 1; i <= n; i++) {
      const q = eio(i / n);
      this.mouse = { x: lerp(from.x, to.x, q), y: lerp(from.y, to.y, q) - Math.sin(q * Math.PI) * 40 };
      await this.page.mouse.move(this.mouse.x, this.mouse.y);
      await this.frame();
    }
    this.mouse = to;
    await this.page.mouse.move(to.x, to.y);
  }
  /** Park the cursor in the bottom-right corner, out of the way. */
  async park(dur = 0.5) { await this.moveTo({ x: this.vw - 60, y: this.vh - 40 }, dur); }
  async click(target, { dur = 0.8, double = false } = {}) {
    if (target) await this.moveTo(target, dur);
    this.ring = this.f;
    if (double) await this.page.mouse.dblclick(this.mouse.x, this.mouse.y); else await this.page.mouse.click(this.mouse.x, this.mouse.y);
    await this.settleFrames(4);
  }
  async dblclick(target, opts = {}) { return this.click(target, { ...opts, double: true }); }
  /** Type into the focused field, one character every other frame. */
  async type(text) { for (const ch of text) { await this.page.keyboard.type(ch); await this.frame(); await this.frame(); } }
  async press(key) { await this.page.keyboard.press(key); await this.settleFrames(2); }
  /** Film a few frames while letting real network replies arrive between them. */
  async settleFrames(n) { for (let i = 0; i < n; i++) { await sleep(60); await this.frame(); } }
  /** Camera: frame a box (CSS px) or reset, over `dur` seconds (compositor applies it). */
  async camera(target, { z = null, dur = 1.2, pad = 60, zmax = 2.2 } = {}) {
    let to;
    try { to = await this.camTarget(target, { z, pad, zmax }); } catch (e) { console.warn(`  ! camera target not found in ${this.scene.id} at ${this.t.toFixed(1)}s: ${e.message.split('\n')[0]}`); process.exitCode = 3; return; }
    if (process.env.DEBUG_CAM) console.log(`  cam ${this.scene.id} f=${this.f} →`, JSON.stringify(to));
    this.camAnim = { f0: this.f, n: Math.max(1, Math.round(dur * FPS)), from: { ...this.cam }, to };
  }
  async camTarget(target, { z, pad, zmax }) {
    let to;
    if (target === 'full') to = { cx: this.vw / 2, cy: this.vh / 2, z: 1 };
    else if (target && typeof target.x === 'number' && typeof target.width === 'number') to = this.frameBox(target, { z, pad, zmax });
    else {
      const rects = async (t) => (typeof t === 'object' && t.count && (await t.count()) > 1)
        ? (await t.evaluateAll((es) => es.map((e) => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; }))).filter((r) => r.width > 0)
        : [await this.box(t)];
      const b = union((await Promise.all((Array.isArray(target) ? target : [target]).map(rects))).flat());
      const w = b.width + 2 * pad, h = b.height + 2 * pad;
      to = { cx: b.x + b.width / 2, cy: b.y + b.height / 2, z: z ?? Math.max(1, Math.min(zmax, this.vw / w, this.vh / h)) };
      const hw = this.vw / 2 / to.z, hh = this.vh / 2 / to.z;   // keep the view inside the page
      to.cx = Math.min(this.vw - hw, Math.max(hw, to.cx)); to.cy = Math.min(this.vh - hh, Math.max(hh, to.cy));
    }
    return to;
  }
  frameBox(b, { z = null, pad = 60, zmax = 2.2 } = {}) {
    const w = b.width + 2 * pad, h = b.height + 2 * pad;
    const to = { cx: b.x + b.width / 2, cy: b.y + b.height / 2, z: z ?? Math.max(1, Math.min(zmax, this.vw / w, this.vh / h)) };
    const hw = this.vw / 2 / to.z, hh = this.vh / 2 / to.z;
    to.cx = Math.min(this.vw - hw, Math.max(hw, to.cx)); to.cy = Math.min(this.vh - hh, Math.max(hh, to.cy));
    return to;
  }
  /** The part of a grid row that holds `text`: from just before the text, `span` px to the right (within the grid). */
  async rowBox(textLocator, { before = 40, span = 1100 } = {}, gridSel = 'vaadin-grid, table, [role="grid"], [role="table"]') {
    const t = await this.box(textLocator);
    const grids = await this.page.locator(gridSel).evaluateAll((es) => es.map((e) => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height }; }));
    const g = grids.find((r) => r.width > 0 && t.y >= r.y && t.y <= r.y + r.height) || { x: 96, width: 1728 };
    const x0 = Math.max(g.x, t.x - before), x1 = Math.min(g.x + g.width, x0 + span);
    return { x: x0, y: t.y - 14, width: x1 - x0, height: t.height + 28 };
  }
  /** Set the camera instantly (before the first frame). */
  async cameraNow(target, opts = {}) { await this.camera(target, { ...opts, dur: 0.001 }); if (this.camAnim) { this.cam = this.camAnim.to; this.camAnim = null; } }
  locator(sel) { return (this.root || this.page).locator(sel); }
  /** Press the mouse at the current point, move to `target` over `dur` s, release (Shift held when asked). */
  async drag(target, { dur = 1.0, shift = false } = {}) {
    if (shift) await this.page.keyboard.down('Shift');
    await this.page.mouse.down();
    this.ring = this.f;
    await this.moveTo(target, dur);
    await this.page.mouse.up();
    if (shift) await this.page.keyboard.up('Shift');
    await this.settleFrames(6);
  }
}
const union = (bs) => { const x = Math.min(...bs.map((b) => b.x)), y = Math.min(...bs.map((b) => b.y)); return { x, y, width: Math.max(...bs.map((b) => b.x + b.width)) - x, height: Math.max(...bs.map((b) => b.y + b.height)) - y }; };

const browser = await chromium.launch();
const ctx = {
  base: BASE, port: PORT, lang, chapter, repo, here, build, scenes,
  api: async (path, init) => { const r = await fetch(BASE + path, init); if (!r.ok) throw new Error(`${path}: ${r.status}`); return r.json(); },
  processByKey: async (key) => (await ctx.api(`/workflow/api/processes?size=200`)).content.find((p) => p.businessKey === key),
  waitFor: async (fn, what, ms = 120000) => { const t0 = Date.now(); for (;;) { const v = await fn(); if (v) return v; if (Date.now() - t0 > ms) throw new Error(`timeout waiting for ${what}`); await sleep(300); } },

  /** Run a REAL command (bash, in cwd relative to the repo) and keep its output for terminal scene `id`. */
  term(id, cmd, { cwd = 'examples/workshop-hotel-booking', keep = null, cache = false, show = cmd, env = {} } = {}) {
    const dir = cache ? resolve(here, `build/ch${chapter}/term`) : `${build}/term`;
    mkdirSync(dir, { recursive: true });
    const file = `${dir}/${id}.json`;
    if (cache && existsSync(file) && !process.env.FRESH) { console.log(`term ${id}: cached`); return JSON.parse(readFileSync(file, 'utf8')); }
    console.log(`term ${id}: $ ${cmd}`);
    const r = spawnSync('bash', ['-c', cmd], { cwd: resolve(repo, cwd), encoding: 'utf8', env: { ...process.env, ENGINE: BASE, ...env }, maxBuffer: 64 << 20 });
    let lines = (r.stdout + r.stderr).replace(/\x1b\[[0-9;]*m/g, '').split('\n').filter((l) => l.trim().length);
    if (keep) lines = lines.filter((l) => keep.test(l));
    // Paths as a viewer would see them from the example's directory (no machine-specific prefixes).
    lines = lines.map((l) => l.split(resolve(repo, cwd) + '/').join('').split(repo + '/').join(''));
    const out = { id, cmd: show, cwd, status: r.status, lines };
    writeFileSync(file, JSON.stringify(out, null, 1));
    return out;
  },

  /** Film live scene `id` (skipped when the chapter has no such scene). */
  /**
   * Film live scene `id` (or a part of scene `opts.scene`: [opts.from, opts.until), scene times — so the
   * scene can show a state before a real command and the state after it; parts are filmed in order).
   */
  async shot(id, open, script, { viewport = { w: VW, h: VH }, scale = viewport.w < VW ? 3 : SCALE, cookies = [], context: contextOptions = {}, scene: sceneId = id, from = null, until = null } = {}) {
    const scene = scenes[sceneId];
    if (!scene) throw new Error(`no scene ${sceneId} in the timeline`);
    if (only && !only.has(sceneId)) return;
    const onFrame = (t) => Math.round(t * FPS) / FPS;   // parts meet on a frame boundary
    const t0s = onFrame(sceneTime(scene, from) ?? 0), t1s = until == null ? scene.dur : onFrame(sceneTime(scene, until));
    const dir = `${build}/live/${sceneId}`;
    if (t0s === 0) rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    const context = await browser.newContext({ viewport: { width: viewport.w, height: viewport.h }, deviceScaleFactor: scale, colorScheme: 'dark', reducedMotion: 'no-preference', ...contextOptions });
    if (cookies.length) await context.addCookies(cookies.map((k) => ({ url: BASE, ...k })));
    const page = await context.newPage();
    await page.addInitScript(CURSOR);
    const t0 = new Date('2026-11-14T09:00:00Z');
    await page.clock.install({ time: t0 });
    // Off camera: open the page and let it load with the clock running in steps.
    await open(page);
    await page.clock.pauseAt(new Date(t0.getTime() + 60_000));
    const cdp = await context.newCDPSession(page);
    await cdp.send('Animation.enable');
    await cdp.send('Animation.setPlaybackRate', { playbackRate: Number(process.env.ANIM_RATE || 0.3) });
    for (let i = 0; i < 40; i++) { await page.clock.runFor(100); await sleep(40); }
    await page.waitForLoadState('networkidle').catch(() => {});
    const s = new Shot(page, scene, dir, viewport.w, viewport.h, t0s, t1s);
    await page.mouse.move(s.mouse.x, s.mouse.y);
    const started = Date.now();
    await script(s, page);
    await s.toEnd();
    const before = t0s > 0 && existsSync(`${dir}/index.json`) ? JSON.parse(readFileSync(`${dir}/index.json`, 'utf8')).frames : [];
    if (t0s > 0 && before.length !== Math.round(t0s * FPS)) throw new Error(`${sceneId}: part ${id} starts at frame ${Math.round(t0s * FPS)}, the parts before it end at ${before.length}`);
    writeFileSync(`${dir}/index.json`, JSON.stringify({ fps: FPS, viewport, scale, origin: `localhost:${PORT}`, frames: [...before, ...s.frames] }));
    console.log(`shot ${id}: ${s.frames.length} frames (${(t1s - t0s).toFixed(1)}s) in ${((Date.now() - started) / 1000).toFixed(0)}s`);
    await context.close();
  },
  /**
   * Film live scene `id` in REAL Visual Studio Code (Playwright Electron), with the EventConductor extension.
   * The workspace is a scratch copy of `workspace` (relative to the repo), so nothing in the repo is touched;
   * `setup(vs)` runs off camera (open files…), then `script(s, vs)` films it with the same paused clock.
   * vs = { page, app, dir (the scratch workspace), webview(): the frame holding the graph editor }.
   * Extension: the release from the Marketplace installed by setup.sh (VSCODE_EXT_DIR), or this checkout's
   * plugins/vscode-eventconductor with VSCODE_EXT=dev (a development host, labelled so in the title bar).
   */
  async vscodeShot(id, { workspace }, setup, script) {
    const scene = scenes[id];
    if (!scene) throw new Error(`no scene ${id} in the timeline`);
    if (only && !only.has(id)) return;
    const tools = process.env.TOOLS || resolve(here, 'build/tools');
    const exe = process.env.VSCODE_EXE || resolve(tools, `vscode-${process.env.VSCODE_VERSION || '1.145.3'}/Visual Studio Code.app/Contents/MacOS/Code`);
    if (!existsSync(exe)) throw new Error(`no VS Code at ${exe} — run ./setup.sh (VSCODE=1)`);
    const root = resolve(here, `build/ch${chapter}/${lang}/vscode`);
    rmSync(root, { recursive: true, force: true });
    const ws = resolve(root, 'ws'), ud = resolve(root, 'user');
    mkdirSync(resolve(ud, 'User'), { recursive: true });
    // One project (copied as the workspace) or several (each a folder of it, by its name).
    const skip = (f) => !/\/(target|node_modules|\.git)(\/|$)/.test(f);
    for (const w of [].concat(workspace)) cpSync(resolve(repo, w), Array.isArray(workspace) ? resolve(ws, basename(w)) : ws, { recursive: true, filter: skip });
    writeFileSync(resolve(ud, 'User/settings.json'), JSON.stringify({
      'workbench.colorTheme': 'Default Dark Modern', 'workbench.startupEditor': 'none', 'security.workspace.trust.enabled': false,
      'update.mode': 'none', 'telemetry.telemetryLevel': 'off', 'extensions.autoCheckUpdates': false, 'extensions.autoUpdate': false,
      'workbench.tips.enabled': false, 'chat.disableAIFeatures': true, 'workbench.secondarySideBar.defaultVisibility': 'hidden',
      'redhat.telemetry.enabled': false, 'editor.fontSize': 15, 'window.title': '${rootName}', 'git.enabled': false,
      'workbench.layoutControl.enabled': false, 'explorer.autoReveal': false, 'window.commandCenter': false, 'editor.minimap.enabled': false,
    }));
    const ext = process.env.VSCODE_EXT === 'dev'
      ? [`--extensionDevelopmentPath=${resolve(repo, 'plugins/vscode-eventconductor')}`]
      : [`--extensions-dir=${process.env.VSCODE_EXT_DIR || resolve(tools, 'vscode-ext')}`];
    const dir = `${build}/live/${id}`;
    rmSync(dir, { recursive: true, force: true }); mkdirSync(dir, { recursive: true });
    const app = await electron.launch({ executablePath: exe, timeout: 90000,
      args: [ws, ...ext, `--user-data-dir=${ud}`, '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes', '--disable-telemetry', '--disable-updates', '--new-window'] });
    const page = await app.firstWindow();
    // 1568x882 CSS px (16:9, fits a laptop screen) at the display's scale factor (2 on a Retina panel).
    // The window is transparent to the person at the screen; screenshots are of the page, not of the screen.
    const VW2 = 1568, VH2 = 882;
    await app.evaluate(({ BrowserWindow }, [w, h]) => { const win = BrowserWindow.getAllWindows()[0]; win.setOpacity(0); win.setContentSize(w, h); }, [VW2, VH2]);
    await page.waitForFunction(() => document.querySelector('.monaco-workbench'), null, { timeout: 60000 });
    await sleep(4000);
    const vs = {
      page, app, dir: ws,
      webview: () => { const f = page.frames().find((x) => x.url().includes('fake.html')); if (!f) { page.screenshot({ path: `${root}/no-webview.png` }).catch(() => {}); throw new Error(`no webview; frames: ${page.frames().map((x) => x.url().slice(0, 60)).join(' | ')}`); } return f; },
      /** Open a file with Quick Open (Cmd+P) — on or off camera. */
      quickOpen: async (name, s) => {
        if (s) { await s.press('Meta+P'); await s.hold(0.4); await s.type(name); await s.hold(0.5); await s.press('Enter'); await s.settleFrames(20); }
        else {
          // the graph editor is up when its webview holds the graph component; a workbench still starting
          // can swallow the first Cmd+P, so try again
          const up = async () => { const f = page.frames().find((x) => x.url().includes('fake.html')); return f && await f.locator('eventconductor-workflow-graph g.node').count().catch(() => 0); };
          for (let attempt = 0; attempt < 3 && !(await up()); attempt++) {
            await page.keyboard.press('Escape');
            await page.keyboard.press('Meta+P');
            await page.locator('.quick-input-widget input').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {});
            await page.keyboard.type(name); await sleep(1500); await page.keyboard.press('Enter');
            for (let i = 0; i < 30 && !(await up()); i++) await sleep(500);
          }
          await sleep(1500);
        }
      },
    };
    await page.evaluate(CURSOR);
    if (setup) await setup(vs);
    const t0 = new Date('2026-11-14T09:00:00Z');
    await page.clock.install({ time: t0 });
    await page.clock.pauseAt(new Date(t0.getTime() + 60_000));
    for (let i = 0; i < 30; i++) { await page.clock.runFor(100); await sleep(40); }
    const s = new Shot(page, scene, dir, VW2, VH2);
    await page.mouse.move(s.mouse.x, s.mouse.y);
    const started = Date.now();
    await script(s, vs);
    await s.toEnd();
    writeFileSync(`${dir}/index.json`, JSON.stringify({ fps: FPS, viewport: { w: VW2, h: VH2 }, scale: 2, origin: 'vscode', frames: s.frames }));
    console.log(`shot ${id} (VS Code): ${s.frames.length} frames (${scene.dur.toFixed(1)}s) in ${((Date.now() - started) / 1000).toFixed(0)}s`);
    await Promise.race([app.close(), sleep(5000)]);
    try { process.kill(app.process().pid, 'SIGKILL'); } catch {}
  },
  /** Open a console page and wait until `ready` (a selector or function) is there — off camera. */
  open: (path, ready) => async (page) => {
    await page.goto(/^https?:/.test(path) ? path : BASE + path);   // another app's page: a full URL
    for (let i = 0; i < 100; i++) {
      await page.clock.runFor(100); await sleep(50);
      const ok = typeof ready === 'function' ? await ready(page) : await page.locator(ready).first().isVisible().catch(() => false);
      if (ok && i > 10) break;
    }
  },
};

const mod = await import(resolve(here, `chapters/${chapter}/live.mjs`));
try { await mod.run(ctx); } finally { await browser.close(); }
console.log(`live ${chapter} ${lang}: done`);
