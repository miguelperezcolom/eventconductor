// Chapter 7 — what is really run and filmed, in order, against a fresh series app (examples/video-series).
// The manager's path is the forms console's task inbox (Forms → Tasks → Run), which needs the console fix
// of PR #657 ("/forms/task/:id" under the root mount); the operator's path is the console's Complete step.
// The manager signs in with an unsigned test token, which the app takes as it comes because of
// workflow.security.trust-forwarded-token=true — a laptop-only switch, said so on screen.

const SA = { cwd: 'examples/video-series' };
const ROOMS_3 = (guest) => `{"guest": "${guest}", "rooms": [{"number": "101", "type": "double"}, {"number": "102", "type": "double"}, {"number": "103", "type": "double"}]}`;

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
/** What scripts/approve.sh sends: "mia", of front-desk-managers, unsigned. Mateu sends this cookie as the bearer token. */
const MANAGER = [{ name: '__mateu_auth_token', value: `${b64({ alg: 'none', typ: 'JWT' })}.${b64({ sub: 'mia', preferred_username: 'mia', realm_access: { roles: ['front-desk-managers'] } })}.` }];

const openDiagram = (c, id) => async (page) => {
  await c.open(`/workflow/processes/${id}`, 'text=Diagram')(page);
  await page.getByText('Diagram', { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};
/** The "+" under "Output variables" in the Complete step dialog (an icon button with no text). */
const plusButton = (page) => page.evaluate(() => {
  const found = [];
  const walk = (r) => { for (const e of r.querySelectorAll('*')) { if (e.tagName === 'VAADIN-BUTTON' && !(e.textContent || '').trim()) { const b = e.getBoundingClientRect(); if (b.width > 0) found.push({ x: b.x, y: b.y, width: b.width, height: b.height }); } if (e.shadowRoot) walk(e.shadowRoot); } };
  walk(document);
  // below the "Output variables" label (the dialog's close × is an empty button too, above it)
  let labelY = 0;
  const find = (r) => { for (const e of r.querySelectorAll('*')) { if (!labelY && e.childElementCount === 0 && (e.textContent || '').trim() === 'Output variables') { const b = e.getBoundingClientRect(); if (b.height) labelY = b.y; } if (e.shadowRoot) find(e.shadowRoot); } };
  find(document);
  return found.filter((b) => b.y > labelY).sort((a, b) => a.y - b.y || a.x - b.x)[0];
});

export async function run(c) {
  // waiting: BOOKING-7 stops at the manager's approval.
  c.term('booking-7', `scripts/start.sh hotel-booking BOOKING-7 '${ROOMS_3('Grace')}' | jq '{businessKey, status}'`, SA);
  const p7 = await c.waitFor(async () => {
    const p = await c.processByKey('BOOKING-7');
    if (!p) return false;
    const steps = await c.api(`/workflow/api/processes/${p.id}/steps`);
    return steps.some((x) => x.stepId === 'approve' && x.status === 'PENDING') && p;
  }, 'BOOKING-7 waiting at approve');
  await c.shot('waiting', openDiagram(c, p7.id), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(0, 1.0);
    await s.camera(node(s, 'Manager approval'), { z: 2.0, dur: 1.0 });
    await s.untilCue(1, 0.4);
    s.cursor = true;
    await s.moveTo(node(s, 'Manager approval'), 0.7);           // hovering a step opens its card (a click would close it)
    await s.settleFrames(12);
    s.cursor = false;                                  // the mouse stays on the step: moving it would hover another
    await s.camera([node(s, 'Manager approval'), s.page.getByText(/Waiting for a human task/).first()], { zmax: 1.9, dur: 0.9, pad: 40 });
    await s.untilCue(2, -0.2);
    await s.moveTo({ x: 60, y: s.vh / 2 }, 0.4);     // off the step: its card closes
    await s.camera(graph(s).locator('g.node'), { zmax: 1.6, dur: 1.0 });
  });

  // inbox: the manager — Forms → Tasks → Run → Claim → tick → Complete.
  await c.shot('inbox', c.open('/', 'text=Forms'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.3);
    await s.click(s.page.getByText('Forms', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(4);
    await s.click(s.page.getByText('Tasks', { exact: true }).first(), { dur: 0.5 });
    await s.settleFrames(14);
    await s.camera(await s.rowBox(s.page.getByText('BOOKING-7', { exact: true }).first(), { before: 260, span: 1500 }), { zmax: 1.5, dur: 0.9, pad: 40 });
    await s.untilCue(1, -0.4);
    await s.camera('full', { dur: 0.5 });
    // The Run action is the grid's last column, past the right edge at 1920 px: scroll it in, as a user would.
    const run = s.page.getByText('Run', { exact: true }).last();
    await run.scrollIntoViewIfNeeded();
    await s.settleFrames(6);
    await s.click(run, { dur: 0.6 });
    await s.settleFrames(16);
    await s.click(s.page.getByText('Claim', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(10);
    await s.click(s.page.getByText('Approve this booking', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(4);
    await s.click(s.page.getByText('Complete', { exact: true }).first(), { dur: 0.6 });
    await s.settleFrames(12);
    await s.park(0.4);
  }, { viewport: { w: 1280, h: 720 }, cookies: MANAGER });

  // approved: the run went on.
  await c.waitFor(async () => (await c.processByKey('BOOKING-7'))?.status === 'COMPLETED', 'BOOKING-7 completed after the form');
  await c.shot('approved', openDiagram(c, p7.id), async (s) => {
    s.cursor = false;
    await s.cameraNow(graph(s).locator('g.node'), { zmax: 1.5 });
    await s.hold(0.6);
    await s.camera([node(s, 'Approved?'), node(s, 'Book every room'), node(s, 'Tell the guest'), node(s, 'Booked')], { zmax: 2.0, dur: 1.4 });
  });

  // operator: BOOKING-10 waits; Complete step with approved=true and a reason; the Operations tab.
  c.term('booking-10', `scripts/start.sh hotel-booking BOOKING-10 '${ROOMS_3('Hugo')}' > /dev/null`, SA);
  const p10 = await c.waitFor(async () => {
    const p = await c.processByKey('BOOKING-10');
    if (!p) return false;
    const steps = await c.api(`/workflow/api/processes/${p.id}/steps`);
    return steps.some((x) => x.stepId === 'approve' && x.status === 'PENDING') && p;
  }, 'BOOKING-10 waiting at approve');
  await c.shot('operator', c.open(`/workflow/processes/${p10.id}`, 'text=Complete step'), async (s) => {
    await s.cameraNow('full');
    await s.hold(0.3);
    await s.click(s.page.getByText('Complete step', { exact: true }).first(), { dur: 0.7 });
    await s.settleFrames(10);
    await s.camera(s.page.getByText('Complete a step by hand').first(), { zmax: 1.7, dur: 0.8, pad: 330 });
    await s.untilCue(1, -0.3);
    await s.click(await plusButton(s.page), { dur: 0.5 });
    await s.settleFrames(6);
    // the "New variable" panel opens inside the dialog, partly below the fold: scroll each field in first
    const inView = async (loc) => { await loc.scrollIntoViewIfNeeded(); await s.settleFrames(3); return loc; };
    await s.click(await inView(s.page.locator('vaadin-text-field#name input').last()), { dur: 0.4 });
    await s.type('approved');
    await s.click(await inView(s.page.locator('vaadin-text-field#value input').last()), { dur: 0.4 });
    await s.type('true');
    await s.click(await inView(s.page.locator('#variables_create').last()), { dur: 0.4 });
    await s.settleFrames(6);
    await s.click(await inView(s.page.locator('vaadin-text-area#reason textarea').last()), { dur: 0.5 });
    await s.type('Manager away: approved by phone');
    await s.page.locator('vaadin-button#apply').last().scrollIntoViewIfNeeded();
    await s.settleFrames(2);
    await s.untilCue(2, -0.6);
    await s.click(s.page.locator('vaadin-button#apply').last(), { dur: 0.5 });
    await s.settleFrames(16);
    await s.camera('full', { dur: 0.5 });
    // the page's tab list is not refreshed when the first operation is recorded: reload, as a user would
    await s.page.reload();
    await s.settleFrames(16);
    await s.click(tab(s, 'Operations'), { dur: 0.6 });
    await s.settleFrames(8);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText(/\[complete-step\]/).first(), { before: 900, span: 1700 }), { zmax: 1.6, dur: 0.9, pad: 50 });
  });

  // tested: the workshop's copy of the definition, days in milliseconds (cached: it does not depend on the app).
  c.term('time-test', 'mvn test -Dtest=TimeTest', { cache: true, keep: /Running com|Tests run:|BUILD (SUCCESS|FAILURE)/ });
}
