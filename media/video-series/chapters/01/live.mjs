// Chapter 1 — what is really run and filmed, in order, against a fresh workshop app
// (examples/workshop-hotel-booking). Commands are the workshop's own scripts (docs: workshop modules 5, 6).

const ROOMS_7 = '{"guest": "Fay", "rooms": [{"number": "101", "type": "double"}, {"number": "102", "type": "double"}, {"number": "103", "type": "double"}]}';
const ROOMS_8 = '{"guest": "Gus", "rooms": [{"number": "201", "type": "double"}, {"number": "202", "type": "double"}, {"number": "900", "type": "penthouse"}]}';
const REQUEST_6 = '{"guest": "Erin", "specialRequest": "I use a wheelchair, please make sure the room is accessible", "rooms": [{"number": "101", "type": "double"}]}';

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const DONE = ['COMPLETED', 'COMPENSATED', 'ERROR', 'CANCELLED'];

/** Open a process page and select a tab, off camera. */
const openTab = (c, id, name) => async (page) => {
  await c.open(`/workflow/processes/${id}`, `text=${name}`)(page);
  await page.getByText(name, { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};

export async function run(c) {
  // start: a booking for three rooms waits at the manager's approval.
  c.term('booking-7', `scripts/start.sh hotel-booking BOOKING-7 '${ROOMS_7}' | jq '{businessKey, status}'`);
  const p7 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-7'); return p?.status === 'RUNNING' && p; }, 'BOOKING-7 waiting');
  await sleep(1500);
  await c.shot('start', openTab(c, p7.id, 'Diagram'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, 1.0);
    await s.camera(graph(s).locator('g.node'), { zmax: 1.5, dur: 1.0 });
    await s.untilCue(2, -0.3);
    await s.camera(node(s, 'Manager approval'), { z: 2.2, dur: 1.0 });
  });

  // approve: the manager's decision through the forms API; the run completes.
  c.term('approve-7', "scripts/approve.sh BOOKING-7 true 'Regular guest'");
  await c.waitFor(async () => (await c.processByKey('BOOKING-7'))?.status === 'COMPLETED', 'BOOKING-7 completed');
  await c.shot('approve', openTab(c, p7.id, 'Diagram'), async (s) => {
    await s.cameraNow(graph(s).locator('g.node'), { zmax: 1.5 });
    s.cursor = false;
    await s.untilCue(1, 0.2);
    await s.camera([node(s, 'Approved?'), node(s, 'Book every room')], { zmax: 2.0, dur: 1.0 });
    await s.hold(1.8);
    await s.camera([node(s, 'Book every room'), node(s, 'Tell the guest'), node(s, 'Booked')], { zmax: 2.0, dur: 1.0 });
    await s.untilCue(2, -0.9);
    await s.camera('full', { dur: 0.6 });
    s.cursor = true;
    await s.click(tab(s, 'Steps'), { dur: 0.6 });
    await s.settleFrames(6);
    await s.moveTo({ x: 1860, y: 1040 }, 0.4);
    s.cursor = false;
    await s.camera(s.page.getByText(/rooms · Room 10[123]/), { zmax: 1.6, dur: 1.0, pad: 200 });
  });

  // penthouse: the same booking over HTTP with a penthouse — sold out, so the rooms are cancelled.
  c.term('booking-8', `scripts/start.sh rooms-booking-http BOOKING-8 '${ROOMS_8}' | jq '{businessKey, status}'`);
  const p8 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-8'); return p?.status === 'COMPENSATED' && p; }, 'BOOKING-8 compensated');
  await c.shot('penthouse', openTab(c, p8.id, 'Diagram'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, 0.3);
    await s.camera([node(s, 'Book every room'), node(s, 'Cancel the room')], { zmax: 2.0, dur: 1.0 });
    await s.untilCue(2, -0.2);
    await s.camera(s.page.getByText(/Compensated \(/).first(), { z: 2.2, dur: 0.9 });
  });

  // operate: find the run by business key, see why it failed, the retry and batch actions.
  await c.shot('operate', c.open('/workflow/processes', 'text=BOOKING-8'), async (s) => {
    await s.cameraNow('full');
    await s.untilCue(1, -0.4);
    await s.click(s.page.getByPlaceholder('Search').first(), { dur: 0.5 });
    await s.type('BOOKING-8');
    await s.press('Enter');
    await s.settleFrames(5);
    await s.click(s.page.getByText(p8.id.slice(-12)).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.untilCue(2, -0.6);
    await s.click(tab(s, 'Errors'), { dur: 0.5 });
    await s.settleFrames(5);
    await s.moveTo({ x: 1860, y: 1040 }, 0.3);
    await s.camera(await s.rowBox(s.page.getByText(/SOLD_OUT/).first()), { zmax: 1.9, dur: 0.8, pad: 140 });
    await s.untilCue(3, -0.5);
    await s.camera('full', { dur: 0.5 });
    await s.click(s.page.getByText('Processes', { exact: true }).first(), { dur: 0.5 });
    await s.settleFrames(10);
    await s.camera(s.page.getByText('Retry from failure', { exact: true }).first(), { z: 1.7, dur: 0.8 });
    await s.moveTo(s.page.getByText('Retry from failure', { exact: true }).first(), 0.7);
    await s.hold(1.2);
    await s.moveTo(s.page.getByText('Retry all matching', { exact: true }).first(), 0.7);
  });

  // tests: the real build of the workshop (cached across languages).
  c.term('mvn-verify', 'mvn verify', { cache: true,
    keep: /EventConductor: .*validated|Tests run: .* -- in com|Tests run: \d+, Failures: 0, Errors: 0, Skipped: 0$|workflow coverage|every check passed|BUILD (SUCCESS|FAILURE)/ });

  // ai-run: a special request classified by the (stand-in) model; the answer in the variables.
  c.term('request-6', `scripts/start.sh special-request BOOKING-6 '${REQUEST_6}'`);
  const p6 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-6'); return DONE.includes(p?.status) && p; }, 'BOOKING-6 done');
  await c.shot('ai-run', openTab(c, p6.id, 'Variables'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.hold(0.6);
    await s.camera(await s.rowBox(s.page.getByText('requestCategory', { exact: true }).first()), { zmax: 1.9, dur: 1.0, pad: 160 });
  });
}
