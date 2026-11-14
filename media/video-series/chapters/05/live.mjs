// Chapter 5 — what is really run and filmed, in order, against a fresh workshop app
// (examples/workshop-hotel-booking). Commands are the workshop's own scripts (docs: workshop module 4).

const ROOMS_4 = '{"guest": "Carol", "rooms": [{"number": "101", "type": "double"}, {"number": "102", "type": "double"}, {"number": "103", "type": "double"}]}';
const ROOMS_5 = '{"guest": "Dan", "rooms": [{"number": "201", "type": "double"}, {"number": "202", "type": "double"}, {"number": "900", "type": "penthouse"}]}';

const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Open a process page and select a tab, off camera. */
const openTab = (c, id, name) => async (page) => {
  await c.open(`/workflow/processes/${id}`, `text=${name}`)(page);
  await page.getByText(name, { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};

export async function run(c) {
  // run: three rooms booked over HTTP; the ids in the Variables tab; what the stand-in hotel holds.
  c.term('booking-4', `scripts/start.sh rooms-booking-http BOOKING-4 '${ROOMS_4}' | jq '{businessKey, status}'`);
  const p4 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-4'); return p?.status === 'COMPLETED' && p; }, 'BOOKING-4 completed');
  c.term('hotel-holds', `curl -s localhost:${c.port}/fake/hotel/bookings | jq -c '.[] | {number, id, idempotencyKey}'`);
  await c.shot('run', openTab(c, p4.id, 'Variables'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, 0.2);
    await s.camera(await s.rowBox(s.page.getByText('roomBookings', { exact: true }).first(), { before: 40, span: 1300 }), { zmax: 1.7, dur: 1.0, pad: 50 });
    await s.untilCue(2, -0.4);
    await s.camera('full', { dur: 0.6 });
  }, { viewport: { w: 1280, h: 720 } });

  // refused: the penthouse answers 409 — the Errors tab, not retried.
  c.term('booking-5', `scripts/start.sh rooms-booking-http BOOKING-5 '${ROOMS_5}' | jq '{businessKey, status}'`);
  const p5 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-5'); return p?.status === 'COMPENSATED' && p; }, 'BOOKING-5 compensated');
  await c.shot('refused', openTab(c, p5.id, 'Errors'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, -0.2);
    await s.camera(await s.rowBox(s.page.getByText(/409/).first(), { before: 700, span: 1240 }), { zmax: 1.8, dur: 1.0, pad: 50 });
    await s.untilCue(2, -0.3);
    await s.camera('full', { dur: 0.8 });
    s.cursor = true;
    await s.click(tab(s, 'Diagram'), { dur: 0.6 });
    await s.settleFrames(8);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(s.locator('eventconductor-workflow-graph').locator('g.node'), { zmax: 1.6, dur: 0.9 });
  }, { viewport: { w: 1280, h: 720 } });
}
