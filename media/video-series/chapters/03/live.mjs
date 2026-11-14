// Chapter 3 — what is really run and filmed: Visual Studio Code itself (Playwright Electron, the
// EventConductor extension as released on the Marketplace — see setup.sh VSCODE=1) on a scratch copy of the
// two example projects, the Maven plugin on a scratch copy of the series app, and the console of a fresh
// series app (examples/video-series).
import { cpSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const WORKSPACE = { workspace: ['examples/video-series', 'examples/workshop-hotel-booking'] };
const GROUP = 'src/main/resources/workflows/group-arrival.ec.yaml';

/** The graph editor inside the VS Code webview. */
const ed = (vs) => vs.webview().locator('eventconductor-workflow-graph');
/** Close the side bar (Explorer) so the graph gets the width — off camera. */
const hideSidebar = async (vs) => {
  for (let i = 0; i < 3 && await vs.page.locator('.part.sidebar').first().isVisible().catch(() => false); i++) { await vs.page.keyboard.press('Meta+B'); await sleep(1200); }
  await sleep(1000);
};
const vnode = (vs, text) => ed(vs).locator('g.node').filter({ hasText: text }).first();

export async function run(c) {
  // open: Quick Open a definition → the graph editor.
  await c.vscodeShot('open', WORKSPACE, null, async (s, vs) => {
    await s.cameraNow('full');
    await s.hold(0.6);
    await vs.quickOpen('group-arrival.ec.yaml', s);
    await s.settleFrames(30);
    await s.park(0.4);
    s.cursor = false;
    await s.untilCue(1, 0.2);
    await s.camera(ed(vs).locator('.palette').first(), { zmax: 2.0, dur: 0.9, pad: 30 });
    await s.hold(2.2);
    await s.camera(ed(vs).locator('g.node'), { zmax: 1.6, dur: 1.0 });
  });

  // edit: a USER_TASK from the palette, named; a Shift+drag link; the forEach failure policy; the file beside.
  await c.vscodeShot('edit', WORKSPACE, async (vs) => { await hideSidebar(vs); await vs.quickOpen('group-arrival.ec.yaml'); await sleep(2500); }, async (s, vs) => {
    await s.cameraNow('full');
    const canvas = await s.box(ed(vs));
    await s.moveTo(ed(vs).locator('.palette-item').filter({ hasText: 'USER_TASK' }).first(), 0.8);
    await s.drag({ x: canvas.x + canvas.width * 0.62, y: canvas.y + canvas.height * 0.82 }, { dur: 1.1 });
    const name = ed(vs).locator('input').nth(2);
    await s.click(name, { dur: 0.5 });
    await vs.page.keyboard.press('Meta+A');
    await s.type('Leader confirms the rooming list');
    await s.settleFrames(4);
    const close = ed(vs).locator('button.close-btn:visible').first();
    if (await close.count()) await s.click(close, { dur: 0.4 });
    await s.untilCue(1, -0.3);
    await s.moveTo(vnode(vs, 'Tell the group leader'), 0.6);
    await s.drag(vnode(vs, 'Leader confirms'), { dur: 0.9, shift: true });
    await s.untilCue(2, -0.3);
    await s.click(vnode(vs, 'Prepare every room'), { dur: 0.6 });
    await s.settleFrames(6);
    const policy = ed(vs).locator('select').filter({ has: vs.webview().locator('option[value="compensateAll"]') }).first();
    await policy.scrollIntoViewIfNeeded();
    await s.settleFrames(3);
    s.cursor = false;
    await s.camera(policy, { zmax: 2.0, dur: 0.8, pad: 120 });
    s.cursor = true;
    await s.click(policy, { dur: 0.6 });
    await policy.selectOption('failFast');
    await s.settleFrames(8);
    await s.untilCue(3, -0.9);
    await s.camera('full', { dur: 0.6 });
    const closeAgain = ed(vs).locator('button.close-btn:visible').first();
    if (await closeAgain.count()) await s.click(closeAgain, { dur: 0.4 });
    await s.click(vs.page.locator('[aria-label*="Show YAML/JSON side-by-side"]').first(), { dur: 0.6 });
    await s.settleFrames(20);
    // in the text beside the graph: find the step the graph just wrote
    await s.click(vs.page.locator('.monaco-editor .view-lines').first(), { dur: 0.5 });
    await vs.page.keyboard.press('Meta+F');
    await s.settleFrames(2);
    await vs.page.keyboard.type('Leader confirms');
    await vs.page.keyboard.press('Enter');
    await vs.page.keyboard.press('Escape');
    await s.settleFrames(10);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(vs.page.locator('.monaco-editor .view-lines').first(), { zmax: 1.7, dur: 0.9, pad: 20 });
  });

  // inline: the workshop's inline PROCESS body, edited in place; Esc back.
  await c.vscodeShot('inline', WORKSPACE, async (vs) => { await hideSidebar(vs); await vs.quickOpen('03-rooms-booking-safe.ec.yaml'); await sleep(2500); }, async (s, vs) => {
    await s.cameraNow('full');
    await s.hold(0.4);
    await s.dblclick(vnode(vs, 'Book every room'), { dur: 0.9 });
    await s.settleFrames(16);
    await s.park(0.4);
    await s.untilCue(1, -0.2);
    s.cursor = false;
    await s.camera(ed(vs).getByText(/Back/).first(), { zmax: 2.0, dur: 0.8, pad: 200 });
    await s.hold(1.4);
    await s.camera('full', { dur: 0.6 });
    s.cursor = true;
    await s.click(ed(vs).getByText(/Back/).first(), { dur: 0.6 });
    await s.settleFrames(12);
    await s.park(0.4);
  });

  // validate: a typo in a scratch copy of the series app; the Maven plugin fails the build; fixed, it passes.
  const val = resolve(c.here, `build/ch${c.chapter}/validate`);
  rmSync(val, { recursive: true, force: true });
  cpSync(resolve(c.repo, 'examples/video-series'), val, { recursive: true, filter: (f) => !/\/(target|node_modules)(\/|$)/.test(f) });
  const file = resolve(val, GROUP), good = readFileSync(file, 'utf8');
  writeFileSync(file, good.replace('preconditionStepId: plan-ready', 'preconditionStepId: plan-redy'));
  const keep = /--- eventconductor|\.ec\.yaml:$|BUILD (SUCCESS|FAILURE)|problem\(s\)|^\[ERROR\] +- |definition\(s\) valid|validated/;
  c.term('typo', `grep -n 'plan-redy' ${GROUP}`, { cwd: val, cache: true });
  c.term('validate-broken', 'mvn -B eventconductor:validate', { cwd: val, cache: true, keep });
  writeFileSync(file, good);
  c.term('validate-fixed', 'mvn -B eventconductor:validate', { cwd: val, cache: true, keep });

  // console: the definition, read-only, in the console of the running app; its Versions; the play button.
  await c.shot('console', c.open('/workflow/definitions/group-arrival', 'text=Versions'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.4);
    await s.camera(s.locator('eventconductor-workflow-graph').locator('g.node'), { zmax: 1.5, dur: 1.0 });
    await s.untilCue(0, 2.6);
    await s.camera('full', { dur: 0.5 });
    await s.click(s.page.getByText('Versions', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(8);
    await s.click(s.page.getByText('Diagram', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.untilCue(1, -0.3);
    const play = await s.box(s.locator('eventconductor-workflow-graph').locator('button, [role="button"]').first()).catch(() => null);
    if (play) await s.click(play, { dur: 0.6 });
    await s.park(0.4);
  });
}
