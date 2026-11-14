// Chapter 11 — what is really run and filmed, in order, against a fresh series app (port 8096) filled by its own
// scripts/seed.sh (waiting, completed, compensated runs and three channel-sync incidents: the stand-in channel
// manager is left in an outage). render.sh's `before` made a Git repository of definitions with hotel-booking in
// it (build/hotel-definitions), which the app imports (`appArgs`): the same content as its classpath copy, so v1.

const SA = 'examples/video-series', DEFS = 'media/video-series/build/hotel-definitions';
const shownText = (s, t) => s.page.getByText(t, { exact: true }).filter({ visible: true }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shown = (cmd) => cmd.replaceAll('$ENGINE', 'localhost:8096');

const openTab = (c, id, name) => async (page) => {
  await c.open(`/workflow/processes/${id}`, `text=${name}`)(page);
  await page.getByText(name, { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};
/** Type into the field labelled `label` (a dialog's Reason). */
const typeInto = async (s, label, text) => {
  await s.click(s.page.getByLabel(label).first(), { dur: 0.6 });
  await s.type(text);
};

export async function run(c) {
  // Off camera: the seed (its output is kept but not shown).
  c.term('seed', 'scripts/seed.sh', { cwd: SA });
  const errors = await c.waitFor(async () => { const l = (await c.api('/workflow/api/processes?definitionId=channel-sync&status=ERROR&size=50')).content; return l.length >= 3 && l; }, 'three channel-sync incidents', 90000);
  const sync2 = errors.find((p) => p.businessKey === 'SYNC-2');

  // find: search by business key, then the Only errors filter.
  await c.shot('find', c.open('/workflow/processes', 'text=SYNC-4'), async (s) => {
    await s.cameraNow('full');
    await s.untilCue(1, -0.6);
    await s.click(s.page.getByPlaceholder('Search').first(), { dur: 0.5 });
    await s.press('Escape');
    await s.type('BOOKING-9');
    await s.press('Enter');
    await s.settleFrames(8);
    await s.park(0.3);
    await s.camera(await s.rowBox(shownText(s, 'BOOKING-9'), { before: 420, span: 1000 }), { zmax: 1.7, dur: 0.8, pad: 50 });
    await s.untilCue(2, -1.6);
    await s.camera('full', { dur: 0.5 });
    // A fresh list, then the filter menu → Only errors → Yes.
    await s.page.goto(c.base + '/workflow/processes');
    await s.settleFrames(14);
    await s.click(s.page.getByPlaceholder('Search').first(), { dur: 0.5 });
    await s.settleFrames(4);
    await s.click(s.page.getByText('Only errors', { exact: true }).first(), { dur: 0.5 });
    await s.click(s.page.getByText('Yes', { exact: true }).first(), { dur: 0.5 });
    await s.press('Escape');
    await s.settleFrames(8);
    await s.park(0.3);
    s.cursor = false;
    await s.camera([shownText(s, 'SYNC-2'), shownText(s, 'SYNC-4')], { zmax: 1.6, dur: 0.9, pad: 120 });
  });

  // incident: SYNC-2's Errors tab, then the Incidents page (one incident, three processes).
  await c.shot('incident', openTab(c, sync2.id, 'Errors'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.hold(0.5);
    await s.camera(await s.rowBox(s.page.getByText(/HTTP_503/).first(), { before: 600, span: 1500 }), { zmax: 1.7, dur: 1.0, pad: 50 });
    await s.untilCue(1, -0.8);
    await s.camera('full', { dur: 0.5 });
    await s.page.goto(c.base + '/workflow/incidents');
    await s.settleFrames(14);
    await s.untilCue(2, -0.6);
    await s.camera(await s.rowBox(s.page.getByText('push-availability', { exact: true }).first(), { before: 420, span: 1650 }), { zmax: 1.5, dur: 1.0, pad: 50 });
  });

  // retry: the outage ends; the incident's Retry all (with a reason) → a batch; the Batch operations page.
  c.term('outage-off', `curl -s -X POST "$ENGINE/fake/channel-manager/outage?on=false"`, { cwd: SA, show: shown(`curl -s -X POST "$ENGINE/fake/channel-manager/outage?on=false"`) });
  const incident = (await c.api('/workflow/api/incidents')).incidents.find((i) => i.definitionId === 'channel-sync');
  await c.shot('retry', c.open('/workflow/incidents', 'text=push-availability'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, -0.4);
    s.cursor = true;
    await s.click(s.page.getByText(incident.key).filter({ visible: true }).first(), { dur: 0.6 });
    await s.settleFrames(12);
    await s.click(s.page.getByText('Retry all', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(8);
    await typeInto(s, 'Reason', 'Channel manager back up');
    await s.click(s.page.getByRole('button', { name: 'Retry all' }).last(), { dur: 0.6 });
    await s.settleFrames(20);
    await s.untilCue(2, -0.6);
    await s.page.goto(c.base + '/workflow/batchOperations');
    await s.settleFrames(14);
    await s.park(0.3);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText('RETRY', { exact: true }).first(), { before: 300, span: 1500 }), { zmax: 1.6, dur: 1.0, pad: 60 });
  });

  // byhand: CHECKIN-1 waits for room-ready; Complete step with a reason; the audit line in Operations.
  const ck = await c.processByKey('CHECKIN-1');
  await c.shot('byhand', openTab(c, ck.id, 'Steps'), async (s) => {
    await s.cameraNow('full');
    await s.untilCue(1, 0.3);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText('wait-room-ready', { exact: true }).first(), { before: 40, span: 900 }), { zmax: 1.7, dur: 0.9, pad: 50 });
    await s.untilCue(2, -0.6);
    await s.camera('full', { dur: 0.5 });
    s.cursor = true;
    await s.click(s.page.getByText('Complete step', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await typeInto(s, 'Reason', 'Housekeeping handed over room 305 by phone');
    await s.click(s.page.getByRole('button', { name: 'Complete step' }).last(), { dur: 0.6 });
    await s.settleFrames(24);
    await s.untilCue(3, -0.8);
    await s.click(tab(s, 'Operations'), { dur: 0.6 });
    await s.settleFrames(10);
    await s.park(0.3);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText(/Housekeeping handed over/).first(), { before: 700, span: 1600 }), { zmax: 1.6, dur: 1.0, pad: 50 });
  });

  // change: a commit in the definitions repository, then its webhook → the engine imports version 2.
  c.term('change-def', "perl -pi -e 's/after: P1D/after: PT4H/' hotel-booking.ec.yaml && git commit -qam 'Escalate the approval after 4 hours' && git show HEAD --format='%h %s' -U0 | grep -E '^[0-9a-f]{7}|^[-+] '", { cwd: DEFS });
  c.term('git-webhook', "curl -s -o /dev/null -w '%{http_code}\\n' -X POST $ENGINE/workflow/webhooks/generic -H 'Content-Type: application/json' -d '{}'", { cwd: DEFS, show: shown("curl -s -o /dev/null -w '%{http_code}\\n' -X POST $ENGINE/workflow/webhooks/generic -H 'Content-Type: application/json' -d '{}'") });
  await c.waitFor(async () => { const v = await fetch(`${c.base}/workflow/api/definitions/hotel-booking`).then((r) => r.ok ? r.json() : null).catch(() => null); return v && JSON.stringify(v).includes('PT4H'); }, 'hotel-booking v2 imported', 30000).catch((e) => console.warn('  !', e.message));

  // migrate: the definition's Versions tab; BOOKING-7 (v1) → Migrate… → a reason → Preview (dry run).
  const b7 = await c.processByKey('BOOKING-7');
  await c.shot('migrate', c.open('/workflow/definitions/hotel-booking', 'text=Versions'), async (s) => {
    s.cursor = true;
    await s.cameraNow('full');
    await s.click(tab(s, 'Versions'), { dur: 0.6 });
    await s.settleFrames(10);
    await s.park(0.3);
    s.cursor = false;
    await s.camera(s.page.locator('vaadin-grid').last(), { zmax: 1.6, dur: 0.9, pad: 40 });
    await s.untilCue(1, -1.4);
    await s.page.goto(`${c.base}/workflow/processes/${b7.id}`);
    await s.settleFrames(14);
    await s.camera('full', { dur: 0.4 });
    s.cursor = true;
    await s.click(s.page.getByText('Migrate…', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await typeInto(s, 'Reason', 'Managers want the 4-hour reminder');
    await s.click(s.page.getByRole('button', { name: 'Preview' }).last(), { dur: 0.6 });
    await s.settleFrames(20);
    await s.park(0.3);
  });
}
