// Chapter 13 — what is really run and filmed, in order. The workshop app (ENGINE, port 8095) runs on the host: its
// /fake/hotel is the stand-in the distributed stack calls. The distributed stack is the repository's
// apps/docker-compose.yml plus this chapter's compose.2.41.0.yml (see its header), started here and taken down at
// the end. Its orchestrator answers on :8105 with the compose default login (admin / changeme).

import { spawnSync } from 'node:child_process';

const ROOT = '.', WS = 'examples/workshop-hotel-booking';
const COMPOSE = 'docker compose -p ec-series -f apps/docker-compose.yml -f media/video-series/chapters/13/compose.2.41.0.yml';
const DC = 'http://localhost:8105', AUTH = { username: 'admin', password: 'changeme' };
const ROOMS_13 = '{"guest": "Hana", "rooms": [{"number": "301", "type": "double"}, {"number": "900", "type": "penthouse"}]}';
// The stack reaches the workshop app's stand-in hotel on the host, on the port the app was started on.
const HOTEL_URL = `http://host.docker.internal:${new URL(process.env.ENGINE || 'http://localhost:8095').port || 8095}/fake/hotel`;

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const dc = async (path) => { const r = await fetch(DC + path, { headers: { Authorization: 'Basic ' + Buffer.from('admin:changeme').toString('base64') } }).catch(() => null); return r && r.ok ? r.json() : null; };

export async function run(c) {
  try {
    // compose: the stack, up (the orchestrator, and through depends_on Postgres and Redpanda), then its state.
    c.term('compose-up', `${COMPOSE} up -d orchestrator redpanda-console 2>&1 | grep -E 'Container .* (Started|Healthy)'`, { cwd: ROOT, env: { HOTEL_URL } });
    await c.waitFor(async () => (await dc('/workflow/api/processes'))?.content, 'the orchestrator to answer', 600000);
    // The directory import runs after "Started": wait for its log line before starting a process.
    await c.waitFor(async () => /Imported 1 workflow definition/.test(spawnSync('docker', ['logs', 'orchestrator'], { encoding: 'utf8' }).stdout), 'the definition import', 300000);
    c.term('compose-ps', `${COMPOSE} ps --format 'table {{.Name}}\\t{{.Image}}\\t{{.Status}}'`, { cwd: ROOT });

    // dc-run: the workshop's rooms-booking-http, unchanged, on the orchestrator → the refused room set aside, the run
    // waiting for the guest (the guest-decides story; the cancel path's END compensate: true is after 2.41.0).
    c.term('booking-13', `ENGINE=http://admin:changeme@localhost:8105 scripts/start.sh rooms-booking-http BOOKING-13 '${ROOMS_13}' | jq '{businessKey, status}'`, { cwd: WS });
    const p = await c.waitFor(async () => {
      const x = (await dc('/workflow/api/processes?businessKey=BOOKING-13'))?.content?.[0];
      if (!x) return false;
      const steps = await dc(`/workflow/api/processes/${x.id}/steps`);
      return (Array.isArray(steps) ? steps : steps?.content || []).some((st) => st.stepId === 'wait-for-guest' && st.status === 'PENDING') && x;
    }, 'BOOKING-13 waiting for the guest on the orchestrator', 120000);
    await c.shot('dc-run', async (page) => {
      await c.open(`${DC}/workflow/processes/${p.id}`, 'text=Diagram')(page);
      await page.getByText('Diagram', { exact: true }).first().click();
      for (let i = 0; i < 30; i++) { await page.clock.runFor(100); await sleep(80); }
    }, async (s) => {
      s.cursor = false;
      await s.cameraNow('full');
      await s.untilCue(1, 0.2);
      await s.camera([node(s, 'Book every room'), node(s, 'Wait for the guest')], { zmax: 2.0, dur: 1.0 });
      await s.untilCue(2, -0.2);
      await s.camera(graph(s).locator('g.node'), { zmax: 1.6, dur: 0.9, pad: 30 });
    }, { context: { httpCredentials: AUTH } });

    // topics: the broker's topics in Redpanda Console.
    await c.shot('topics', c.open('http://localhost:8888/topics?showInternal=false', 'text=http-calls'), async (s) => {
      s.cursor = false;
      await s.cameraNow('full');
      await s.hold(0.8);
      await s.camera([s.page.getByText('outbox', { exact: true }).first(), s.page.getByText('upstream', { exact: true }).first(), s.page.getByText('http-calls', { exact: true }).first(), s.page.getByText('event-publications', { exact: true }).first()], { zmax: 1.8, dur: 1.0, pad: 80 });
    });
  } finally {
    c.term('compose-down', `${COMPOSE} down -v`, { cwd: ROOT });   // off camera: nothing is left running
  }

  // helm: the chart, linted, refusing to render without a password, and what it renders.
  c.term('helm-lint', 'helm lint charts/eventconductor', { cwd: ROOT, cache: true });
  c.term('helm-nopass', 'helm template ec charts/eventconductor 2>&1 | grep -o "postgres.password must be set.*"', { cwd: ROOT, cache: true });
  c.term('helm-template', "helm template ec charts/eventconductor --set postgres.password=demo | grep -E '^kind: |image: \"?miguelperezcolom|image: (postgres|docker)' | sort | uniq -c", { cwd: ROOT, cache: true });
}
