// Captures the REAL console states the promo video animates over: screenshots of a 1280x720
// viewport at 3x (3840x2160) — the small viewport makes the console's UI large in the frame, the
// 3x lets the camera zoom in further without blur — plus the boxes of the elements the cursor
// points at, in 1280x720 CSS pixels. Run by scripts/capture-real.sh, which starts the app.
// Usage: node scripts/record-real.mjs [baseUrl] [phase]
//   phase "main"     — everything except the Versions tab (first run of the app)
//   phase "versions" — the Versions tab, after the app was restarted with definition v2
import { chromium } from 'playwright';
import { mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';

const base = process.argv[2] || 'http://localhost:8197';
const phase = process.argv[3] || 'main';
const out = 'build/real';
mkdirSync(out, { recursive: true });
const boxesFile = `${out}/boxes.json`;
const boxes = existsSync(boxesFile) ? JSON.parse(readFileSync(boxesFile, 'utf8')) : {};

const browser = await chromium.launch();
const VW = 1280, VH = 720;
const page = await browser.newPage({ viewport: { width: VW, height: VH }, deviceScaleFactor: 3, colorScheme: 'dark' });
boxes.viewport = { w: VW, h: VH };

async function settle(ms = 1800) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(ms);
}
async function shot(name) {
  await page.mouse.move(VW - 1, VH - 1); // no hover state left behind
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${out}/${name}.png` });
  console.log('captured', name);
}
async function box(name, locator) {
  const b = await locator.boundingBox();
  if (!b) throw new Error(`no box for ${name}`);
  boxes[name] = { x: b.x, y: b.y, w: b.width, h: b.height };
}
const graph = () => page.locator('eventconductor-workflow-graph');
const graphNode = (text) => graph().locator('g.node').filter({ hasText: text }).first();
/** The union of every node's box: the part of the canvas the graph actually occupies. */
async function nodesBox(name) {
  const bs = await graph().locator('g.node').evaluateAll((els) => els.map((e) => { const b = e.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; }).filter((b) => b[2] > b[0]));
  const x = Math.min(...bs.map((b) => b[0])), y = Math.min(...bs.map((b) => b[1]));
  boxes[name] = { x, y, w: Math.max(...bs.map((b) => b[2])) - x, h: Math.max(...bs.map((b) => b[3])) - y };
}
/** Click an empty corner of the canvas: drops the selection (and its popover), keeps the view. */
async function deselect() {
  const g = await graph().boundingBox();
  await page.mouse.click(g.x + 24, g.y + 24); await settle(1000);
}

async function mainProcessId() {
  const r = await fetch(`${base}/workflow/api/processes?size=50`);
  const d = await r.json();
  return d.content.find((p) => p.businessKey === 'BK-4711').id;
}

if (phase === 'main') {
  const pid = await mainProcessId();
  const runs = (await (await fetch(`${base}/workflow/api/processes?size=50`)).json()).content;
  boxes.meta = {
    mainId: pid,
    failedId: runs.find((p) => p.status === "ERROR").id,
  };

  // 1. The process list, then searched by business key.
  await page.goto(`${base}/workflow/processes`); await settle();
  await box('list.search', page.getByPlaceholder('Search').first());
  await shot('list');
  await page.getByPlaceholder('Search').first().fill('BK-4711');
  await page.keyboard.press('Enter'); await settle();
  await box('search.link', page.getByText(pid.slice(-12)).first());
  await shot('list-searched');

  // 2. The process page, Diagram tab, collapsed — then expanded into lanes.
  await page.goto(`${base}/workflow/processes/${pid}`); await settle();
  await box('process.diagramTab', page.getByText('Diagram', { exact: true }).first());
  await page.getByText('Diagram', { exact: true }).first().click(); await settle(2500);
  await box('process.rooms', graphNode('Book every room'));
  await box('process.status', page.getByText(/Compensated \(/).first());
  await shot('process-collapsed');
  const r = boxes['process.rooms'];
  await page.mouse.dblclick(r.x + r.w / 2, r.y + r.h / 2); await settle(2500);
  await shot('process-lanes-popover');
  await box('lanes.node', graphNode('Book every room'));
  await box('lanes.cancel', graphNode('Cancel the room'));
  await box('lanes.room102', graph().getByText('Room 102', { exact: true }).first());
  await deselect();
  await nodesBox('lanes.nodes');
  await shot('process-lanes');

  // 3. Operate: the failed child run selected in the list, the retry action at hand.
  await page.goto(`${base}/workflow/processes`); await settle();
  const errorRow = page.locator('tr, vaadin-grid-cell-content, [role="row"]').filter({ hasText: 'Error (' }).first();
  await box('list.errorRow', errorRow);
  const cb = page.locator('vaadin-checkbox, input[type="checkbox"]');
  // The row's checkbox is the one vertically aligned with the error badge.
  const eb = boxes['list.errorRow'];
  const all = await cb.evaluateAll((els) => els.map((e) => { const b = e.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height }; }));
  const target = all.find((b) => Math.abs((b.y + b.h / 2) - (eb.y + eb.h / 2)) < 14 && b.x < 200);
  if (target) {
    boxes['list.errorCheckbox'] = target;
    await page.mouse.click(target.x + target.w / 2, target.y + target.h / 2); await settle(800);
  }
  await box('list.retry', page.getByText('Retry from failure', { exact: true }).first());
  await shot('list-failed-selected');

  // 4. The definition: its graph, then the inline body opened.
  await page.goto(`${base}/workflow/definitions/hotel-booking`); await settle(2500);
  await box('def.rooms', graphNode('Book every room'));
  await nodesBox('def.nodes');
  await shot('definition');
  const d = boxes['def.rooms'];
  await page.mouse.dblclick(d.x + d.w / 2, d.y + d.h / 2); await settle(2500);
  await deselect();
  await box('defx.rooms', graphNode('Book every room'));
  await nodesBox('defx.nodes');
  await shot('definition-expanded');
}

if (phase === 'versions') {
  await page.goto(`${base}/workflow/definitions/hotel-booking`); await settle();
  await box('versions.tab', page.getByText('Versions', { exact: true }).first());
  await page.getByText('Versions', { exact: true }).first().click(); await settle();
  await box('versions.table', page.locator('table, vaadin-grid').first());
  await shot('versions');
}

writeFileSync(boxesFile, JSON.stringify(boxes, null, 2));
await browser.close();
