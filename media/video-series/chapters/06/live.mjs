// Chapter 6 — what is really run and filmed, in order, against a fresh series app (examples/video-series).
// Commands are the app's own scripts (examples/video-series/README.md, "Chapter → what to record").

const SA = { cwd: 'examples/video-series' };
const GROUP_1 = '{"leader": "Ana", "groupSize": "12", "rooms": [{"number": "301", "type": "double"}, {"number": "302", "type": "suite"}, {"number": "303", "type": "double", "issues": 2}, {"number": "304", "type": "out-of-order"}]}';
const TRANSFER_1 = '{"guest": "Erin", "flight": "IB3456"}';

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Open a process page on its Diagram tab, off camera. */
const openDiagram = (c, id) => async (page) => {
  await c.open(`/workflow/processes/${id}`, 'text=Diagram')(page);
  await page.getByText('Diagram', { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};

export async function run(c) {
  // group: GROUP-1 — the choice, the fork, the rooms (one out of order) — then into the iterations.
  c.term('group-1', `scripts/start.sh group-arrival GROUP-1 '${GROUP_1}' | jq '{businessKey, status}'`, SA);
  const g1 = await c.waitFor(async () => { const p = await c.processByKey('GROUP-1'); return p?.status === 'COMPLETED' && p; }, 'GROUP-1 completed', 60000);
  await c.shot('group', openDiagram(c, g1.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, 0.2);
    await s.camera([node(s, 'Group booked'), node(s, 'Large group?'), node(s, 'Plan the standard'), node(s, 'Assign a group')], { zmax: 2.0, dur: 1.0 });
    await s.untilCue(2, -0.2);
    await s.camera([node(s, 'Prepare everything'), node(s, 'Prepare the welcome'), node(s, 'Prepare every room'), node(s, 'Everything ready')], { zmax: 1.9, dur: 1.0 });
    await s.untilCue(3, -0.6);
    await s.camera('full', { dur: 0.6 });
    s.cursor = true;
    await s.dblclick(node(s, 'Prepare every room'), { dur: 0.8 });
    await s.settleFrames(10);
    // Drop the selection popover: click an empty corner of the canvas (the view stays).
    const gb = await s.box(graph(s));
    await s.click({ x: gb.x + 40, y: gb.y + 40 }, { dur: 0.5 });
    await s.press('Escape');
    await s.settleFrames(4);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(node(s, 'Prepare every room'), { zmax: 2.0, dur: 1.0, pad: 40 });
  });

  // child: the same diagram, double-click Room 303 → its own prepare-room run (repeat, loopBack ×2).
  await c.shot('child', async (page) => {
    await openDiagram(c, g1.id)(page);
    await page.locator('eventconductor-workflow-graph g.node').filter({ hasText: 'Prepare every room' }).first().dblclick();
    for (let i = 0; i < 20; i++) { await page.clock.runFor(100); await sleep(50); }
  }, async (s) => {
    await s.cameraNow(node(s, 'Prepare every room'), { zmax: 2.0, pad: 40 });
    await s.hold(0.4);
    await s.dblclick(graph(s).getByText('Room 303', { exact: true }).first(), { dur: 0.9 });
    await s.settleFrames(14);
    await s.camera('full', { dur: 0.01 });
    await s.click(s.page.getByText('Diagram', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(14);
    await s.moveTo({ x: 40, y: s.vh / 2 }, 0.4);     // off the graph: no hover popover
    await s.press('Escape');
    s.cursor = false;
    await s.camera(graph(s).locator('g.node'), { zmax: 1.7, dur: 0.9 });
    await s.untilCue(1, 0.3);
    await s.camera(node(s, 'Is the room clean'), { zmax: 2.2, dur: 0.9, pad: 70 });
    await s.hold(2.4);
    await s.camera([node(s, 'Inspect the room'), node(s, 'Anything'), node(s, 'Fix what')], { zmax: 2.1, dur: 1.0, pad: 70 });
  });

  // race: TRANSFER-1 — City Cabs wins, the Airport Express booking is undone (compensateLosers).
  c.term('transfer-1', `scripts/start.sh airport-transfer TRANSFER-1 '${TRANSFER_1}' | jq '{businessKey, status}'`, SA);
  const t1 = await c.waitFor(async () => {
    const p = await c.processByKey('TRANSFER-1');
    if (p?.status !== 'COMPLETED') return false;
    const steps = await c.api(`/workflow/api/processes/${p.id}/steps`);
    return steps.some((x) => x.stepId === 'cancel-airport-express' && x.status === 'COMPLETED') && p;
  }, 'TRANSFER-1 completed, the loser cancelled', 60000);
  await c.shot('race', openDiagram(c, t1.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(0, 0.2);
    await s.camera([node(s, 'Book with City Cabs'), node(s, 'First to confirm')], { zmax: 2.1, dur: 1.0 });
    await s.untilCue(1, 0.2);
    await s.camera([node(s, 'Book with Airport'), node(s, 'Cancel Airport Express')], { zmax: 2.2, dur: 1.0 });
    await s.until(s.dur - 1.6);
    await s.camera(graph(s).locator('g.node'), { zmax: 1.6, dur: 1.2 });
  });
}
