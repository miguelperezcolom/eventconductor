// Chapter 9 — what is really run and filmed, in order, against a fresh series app (examples/video-series,
// H2 in memory, port 8096). Commands are the series app's own scripts; ENGINE (set by the recorder) is the app.
// Timing: each shot lasts its scene's narration; s.untilCue(i) waits for sentence i of THIS language.

const SA = 'examples/video-series';
const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// The command as typed on the recording machine: the app is at localhost:8096 ($ENGINE when it runs elsewhere).
const shown = (cmd) => cmd.replaceAll('$ENGINE', 'localhost:8096');

/** Open a process page and select a tab, off camera. */
const openTab = (c, id, name) => async (page) => {
  await c.open(`/workflow/processes/${id}`, `text=${name}`)(page);
  await page.getByText(name, { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};

export async function run(c) {
  // received: the channel manager's signed webhook → the trigger starts booking-onboarding.
  c.term('webhook', 'scripts/webhook.sh CM-2002 Bea 2026-12-24', { cwd: SA });
  const onb = await c.waitFor(async () => { const p = await c.processByKey('booking-onboarding-CM-2002'); return p?.status === 'COMPLETED' && p; }, 'booking-onboarding-CM-2002 completed');
  await c.shot('received', c.open('/workflow/receivedEvents', 'text=channel-manager-CM-2002'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, -0.4);
    // The outcome columns are to the right of the long receipt id: scroll the grid sideways, as a user would.
    s.cursor = true;
    await s.moveTo(s.page.getByText('channel-manager-CM-2002').first(), 0.6);
    await s.page.mouse.wheel(1400, 0);
    await s.settleFrames(8);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText('booking-onboarding-CM-2002').first(), { before: 900, span: 1250 }), { zmax: 1.7, dur: 1.0, pad: 40 });
    await s.untilCue(2, -0.3);
    await s.camera('full', { dur: 0.9 });
  });

  // published: the run's diagram, then what PUBLISH_EVENT handed to the app's publisher.
  c.term('events', "curl -s $ENGINE/fake/events | jq '.[-1] | {destination, eventType, key, data}'", { cwd: SA, show: shown("curl -s $ENGINE/fake/events | jq '.[-1] | {destination, eventType, key, data}'") });
  await c.shot('published', openTab(c, onb.id, 'Diagram'), async (s) => {
    s.cursor = false;
    await s.cameraNow(graph(s).locator('g.node'), { zmax: 1.6 });
    await s.hold(1.0);
    await s.camera([node(s, 'Register the guest'), node(s, 'Announce the new guest')], { zmax: 2.0, dur: 1.0 });
    await s.untilCue(1, -0.5);
    await s.camera('full', { dur: 0.8 });
  });

  // invoke + quote: a synchronous invocation answered by REPLY; the run keeps going (holding the price).
  c.term('invoke', 'scripts/invoke.sh QUOTE-2 double 2 | jq .', { cwd: SA });
  const q = await c.waitFor(async () => c.processByKey('QUOTE-2'), 'QUOTE-2');
  await sleep(800);
  await c.shot('quote', openTab(c, q.id, 'Reply'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.hold(0.4);
    await s.camera([s.page.getByText('Outcome', { exact: true }).first(), s.page.getByText('Payload', { exact: true }).first(), s.page.getByText(/"quoteId"/).first()], { zmax: 1.7, dur: 1.0, pad: 60 });
    await s.untilCue(1, -1.2);
    await s.camera('full', { dur: 0.6 });
    s.cursor = true;
    await s.click(tab(s, 'Diagram'), { dur: 0.6 });
    await s.settleFrames(8);
    await s.park(0.4);
    s.cursor = false;
    await s.camera([node(s, 'Answer the caller'), node(s, 'Hold the price')], { zmax: 2.0, dur: 1.0 });
  });
}
