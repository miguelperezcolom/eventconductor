// Chapter 8 — what is really run and filmed, in order, against a fresh series app (examples/video-series).
// Commands are the app's own scripts (examples/video-series/README.md, "Chapter → what to record").
// Check-in dates are relative to the day of the recording (as scripts/seed.sh does).

const SA = { cwd: 'examples/video-series' };
const day = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const openDiagram = (c, id) => async (page) => {
  await c.open(`/workflow/processes/${id}`, 'text=Diagram')(page);
  await page.getByText('Diagram', { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};
/** The process holding `key` once step `stepId` has reached one of `states`. */
const reached = (c, key, stepId, states = ['PENDING', 'RUNNING', 'COMPLETED']) => c.waitFor(async () => {
  const p = await c.processByKey(key);
  if (!p) return false;
  const steps = await c.api(`/workflow/api/processes/${p.id}/steps`);
  return steps.some((x) => x.stepId === stepId && states.includes(x.status)) && p;
}, `${key} at ${stepId}`, 60000);

export async function run(c) {
  // timer-run: BALANCE-1, check-in in 20 days — the timer armed for 09:00, three days before.
  c.term('balance-1', `scripts/start.sh balance-reminder BALANCE-1 '{"guest": "Carol", "checkIn": "${day(20)}", "balance": "360"}' | jq '{businessKey, status}'`, SA);
  const b1 = await reached(c, 'BALANCE-1', 'three-days-before', ['PENDING']);
  await c.shot('timer-run', openDiagram(c, b1.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, -0.6);
    s.cursor = true;
    await s.moveTo(node(s, '3 days before'), 0.7);           // hovering a step opens its card (a click would close it)
    await s.settleFrames(12);
    s.cursor = false;                                  // the mouse stays on the step, so its card stays open
    await s.camera([node(s, '3 days before'), s.page.getByText(/Waiting for timer until/).first()], { zmax: 2.2, dur: 0.9, pad: 50 });
  });

  // payment: BALANCE-2, check-in in 2 days — the timer fires at once; the payment message completes the wait.
  c.term('balance-2', `scripts/start.sh balance-reminder BALANCE-2 '{"guest": "Dan", "checkIn": "${day(2)}", "balance": "240"}' | jq '{businessKey, status}'`, SA);
  const b2 = await reached(c, 'BALANCE-2', 'wait-payment', ['PENDING']);
  await sleep(1000);
  await c.shot('payment-before', openDiagram(c, b2.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(0, 2.6);
    await s.camera([node(s, '3 days before'), node(s, 'Send the payment link'), node(s, 'Wait for the payment')], { zmax: 2.0, dur: 1.0 });
  }, { viewport: { w: 1920, h: 1080 }, scene: 'payment', until: 'c2' });
  c.term('paid-2', `scripts/message.sh payment-received BALANCE-2 '{"paidAmount": "240"}'`, SA);
  await c.waitFor(async () => (await c.processByKey('BALANCE-2'))?.status === 'COMPLETED', 'BALANCE-2 completed');
  await c.shot('payment-after', openDiagram(c, b2.id), async (s) => {
    s.cursor = false;
    await s.cameraNow([node(s, '3 days before'), node(s, 'Send the payment link'), node(s, 'Wait for the payment')], { zmax: 2.0 });
    await s.hold(3.6);
    await s.camera([node(s, 'Wait for the payment'), node(s, 'Confirm the booking'), node(s, 'Done')], { zmax: 2.0, dur: 1.0 });
  }, { scene: 'payment', from: 'c2' });

  // between: the sender's step, as it is in the file.
  c.term('cleaning-yaml', "grep -A6 'id: announce-ready' src/main/resources/workflows/room-cleaning.ec.yaml", SA);

  // checkin: CHECKIN-1 waits for room 305; CLEAN-305 sends room-ready; the keys are handed over.
  c.term('checkin-1', `scripts/start.sh guest-checkin CHECKIN-1 '{"guest": "Bob", "room": "305"}' | jq '{businessKey, status}'`, SA);
  const k1 = await reached(c, 'CHECKIN-1', 'wait-room-ready', ['PENDING']);
  await c.shot('checkin-before', openDiagram(c, k1.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(0, 2.0);
    await s.camera([node(s, 'Guest at the desk'), node(s, 'Wait for the room'), node(s, 'Offer the lounge')], { zmax: 2.0, dur: 1.0 });
  }, { scene: 'checkin', until: 'c2' });
  c.term('clean-305', `scripts/start.sh room-cleaning CLEAN-305 '{"room": "305"}' | jq '{businessKey, status}'`, SA);
  await c.waitFor(async () => (await c.processByKey('CHECKIN-1'))?.status === 'COMPLETED', 'CHECKIN-1 completed');
  await c.shot('checkin-after', openDiagram(c, k1.id), async (s) => {
    s.cursor = false;
    await s.cameraNow([node(s, 'Guest at the desk'), node(s, 'Wait for the room'), node(s, 'Offer the lounge')], { zmax: 2.0 });
    await s.hold(0.5);
    await s.camera([node(s, 'Wait for the room'), node(s, 'Hand over the keys'), node(s, 'Offer the lounge'), node(s, 'Checked in')], { zmax: 1.9, dur: 1.0 });
  }, { scene: 'checkin', from: 'c2' });

  // schedules: the page, the schedule's detail — Pause, Resume, Run now.
  await c.shot('schedules', c.open('/workflow/schedules', 'text=nightly-occupancy'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.4);
    await s.camera(await s.rowBox(s.page.getByText('nightly-occupancy', { exact: true }).first(), { before: 120, span: 1700 }), { zmax: 1.4, dur: 0.9, pad: 40 });
    await s.untilCue(1, -1.2);
    await s.camera('full', { dur: 0.5 });
    await s.click(s.page.getByText('nightly-occupancy', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(12);
    await s.camera([s.page.getByText(/^Schedule nightly-occupancy$/).first(), s.page.getByText('Run now', { exact: true }).first(), s.page.getByText(/Declared in a file/).first()], { zmax: 1.7, dur: 0.9, pad: 40 });
    await s.click(s.page.getByText('Pause', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.click(s.page.getByText('Resume', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.untilCue(2, -0.6);
    await s.click(s.page.getByText('Run now', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.click(s.page.getByText('Run now', { exact: true }).last(), { dur: 0.5 });   // the confirmation
    await s.settleFrames(10);
    await s.park(0.4);
  });
}
