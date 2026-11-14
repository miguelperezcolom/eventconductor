// Chapter 2 — what is really run and filmed, in order, against a fresh workshop app
// (examples/workshop-hotel-booking). The commands are the ones of workshop module 1.

const BODY = '{"definitionId": "first-booking", "businessKey": "BOOKING-1", "variables": {"guest": "Alice", "rooms": [{"number": "101", "type": "double"}]}}';

const graph = (s) => s.locator('eventconductor-workflow-graph');
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();

export async function run(c) {
  // boot: the app the pipeline started with `mvn spring-boot:run` (scripts/app.sh) — its real startup log,
  // the lines that say what came up. Shown as the command that produced it.
  c.term('boot', `grep -E "Tomcat started on port|Started HotelBookingApplication|Imported workflow definition 'first-booking'" ${c.here}/build/app.log | sed -E 's/^.*[]] +[^ ]+ +: //; s/ from file:.*//'`,
    { show: 'mvn spring-boot:run' });

  // start: the REST call of module 1, twice — the second answers the same process (idempotent on the key).
  const call = `curl -s -X POST localhost:${c.port}/workflow/api/processes -H 'Content-Type: application/json' -d '${BODY}' | jq '{id, businessKey, status}'`;
  c.term('start-1', call);
  const p1 = await c.waitFor(async () => { const p = await c.processByKey('BOOKING-1'); return p?.status === 'COMPLETED' && p; }, 'BOOKING-1 completed');
  c.term('start-1-again', call);

  // console: the list → the run → Steps, Variables, Diagram.
  await c.shot('console', c.open('/workflow/processes', 'text=BOOKING-1'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.4);
    await s.camera(await s.rowBox(s.page.getByText('BOOKING-1', { exact: true }).first(), { before: 420, span: 1000 }), { zmax: 1.6, dur: 0.9, pad: 50 });
    await s.untilCue(0, 2.2);
    await s.camera('full', { dur: 0.6 });
    await s.click(s.page.getByText(p1.id.slice(-12)).first(), { dur: 0.7 });
    await s.settleFrames(12);
    await s.click(tab(s, 'Steps'), { dur: 0.5 });
    await s.settleFrames(6);
    await s.untilCue(1, 0.3);
    await s.camera(s.page.getByText('register-booking', { exact: true }).first(), { zmax: 1.5, dur: 0.9, pad: 260 });
    await s.untilCue(2, -0.9);
    await s.camera('full', { dur: 0.5 });
    await s.click(tab(s, 'Variables'), { dur: 0.5 });
    await s.settleFrames(6);
    await s.park(0.3);
    await s.camera(await s.rowBox(s.page.getByText('bookingNumber', { exact: true }).first(), { before: 40, span: 1000 }), { zmax: 1.7, dur: 0.9, pad: 50 });
    await s.untilCue(3, -1.0);
    await s.camera('full', { dur: 0.5 });
    await s.click(tab(s, 'Diagram'), { dur: 0.5 });
    await s.settleFrames(10);
    await s.park(0.3);
    s.cursor = false;
    await s.camera(graph(s).locator('g.node'), { zmax: 2.0, dur: 1.0 });
  }, { viewport: { w: 1280, h: 720 } });
}
