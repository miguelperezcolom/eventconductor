// Chapter 4 — what is really run and filmed, in order, against a fresh workshop app
// (examples/workshop-hotel-booking, H2 in memory). Commands are the workshop's own scripts.
// Timing: each shot lasts its scene's narration; s.untilCue(i) waits for sentence i of THIS language.

const ROOMS_3 = '{"guest": "Bob", "rooms": [{"number": "101", "type": "double"}, {"number": "102", "type": "double"}, {"number": "900", "type": "penthouse"}]}';
const ROOMS_4 = '{"guest": "Bob", "rooms": [{"number": "201", "type": "double"}, {"number": "900", "type": "penthouse"}]}';

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();

export async function run(c) {
  // Scene "run": booking 3 — two double rooms and the penthouse (always sold out at the stand-in hotel).
  c.term('booking-3', `scripts/start.sh rooms-booking-safe BOOKING-3 '${ROOMS_3}' | jq '{id, businessKey, status}'`);
  const p3 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-3'); return p?.status === 'COMPENSATED' && p; }, 'BOOKING-3 to end compensated');

  // Scene "diagram": the run's Diagram tab — badge, the failed forEach 2/3, the amber compensation 2/2.
  await c.shot('diagram', c.open(`/workflow/processes/${p3.id}`, 'text=Compensated'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.2);
    await s.click(tab(s, 'Diagram'), { dur: 0.7 });
    await s.settleFrames(6);
    await s.park(0.5);
    s.cursor = false;
    await s.untilCue(0, -0.2);
    await s.camera([s.page.getByText(/Compensated \(/).first(), graph(s).locator('g.node')], { zmax: 1.6, dur: 0.9, pad: 30 });
    await s.untilCue(1, -0.2);
    await s.camera(node(s, 'Book every room'), { z: 2.2, dur: 1.0 });
    await s.untilCue(2, 0.6);
    await s.camera([node(s, 'Book every room'), node(s, 'Cancel the room')], { zmax: 2.0, dur: 1.1 });
    await s.untilCue(3, -0.2);
    await s.camera([node(s, 'Tell the guest'), node(s, 'Done')], { zmax: 2.0, dur: 1.0 });
    await s.until(s.dur - 1.4);
    await s.camera(graph(s).locator('g.node'), { zmax: 1.6, dur: 1.2 });
  });

  // Scene "lanes": double-click the forEach → one lane per room; then the Errors tab.
  await c.shot('lanes', async (page) => {
    await c.open(`/workflow/processes/${p3.id}`, 'text=Compensated')(page);
    await page.getByText('Diagram', { exact: true }).first().click();
    for (let i = 0; i < 20; i++) { await page.clock.runFor(100); await new Promise((r) => setTimeout(r, 50)); }
  }, async (s) => {
    const rooms = node(s, 'Book every room');
    await s.cameraNow([rooms, node(s, 'Cancel the room'), node(s, 'Register the booking')], { zmax: 1.7 });
    await s.hold(0.3);
    await s.dblclick(rooms, { dur: 0.9 });
    await s.settleFrames(8);
    // Drop the selection popover: click an empty corner of the canvas (the view stays).
    const g = await s.box(graph(s));
    await s.click({ x: g.x + 40, y: g.y + 40 }, { dur: 0.6 });
    await s.press('Escape');
    await s.settleFrames(4);
    await s.camera(rooms, { zmax: 2.2, dur: 1.0 });
    await s.park(0.6);
    await s.untilCue(1, -0.9);
    await s.camera('full', { dur: 0.8 });
    await s.hold(0.5);
    await s.click(tab(s, 'Errors'), { dur: 0.7 });
    await s.settleFrames(6);
    await s.park(0.5);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText(/SOLD_OUT/).first(), { before: 640, span: 1180 }), { zmax: 1.9, dur: 1.0, pad: 50 });
  });

  // Scene "hotel": booking 4 over HTTP — Compensated in the list; the stand-in hotel holds nothing.
  c.term('booking-4', `scripts/start.sh rooms-booking-http BOOKING-4 '${ROOMS_4}' | jq '{businessKey, status}'`);
  await c.waitFor(async () => (await c.processByKey('BOOKING-4'))?.status === 'COMPENSATED', 'BOOKING-4 to end compensated');
  c.term('hotel-bookings', `curl -s localhost:${c.port}/fake/hotel/bookings`);
  await c.shot('hotel', c.open('/workflow/processes', 'text=BOOKING-4'), async (s) => {
    await s.cameraNow('full');
    await s.untilCue(1, -1.4);
    await s.click(s.page.getByPlaceholder('Search').first(), { dur: 0.6 });
    await s.type('BOOKING-4');
    await s.press('Enter');
    await s.settleFrames(6);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText('BOOKING-4', { exact: true }).last(), { before: 420, span: 900 }), { zmax: 1.6, dur: 0.9, pad: 50 });
  }, { viewport: { w: 1280, h: 720 } });

  // Scene "tested": the real test run (cached across languages: it does not depend on the app).
  c.term('failure-paths-test', 'mvn test -Dtest=FailurePathsTest', { cache: true, keep: /Running com|Tests run:|BUILD (SUCCESS|FAILURE)/ });
}
