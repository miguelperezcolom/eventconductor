// Chapter 14 — the series app (port 8096) started the way every other EventConductor app ships: its laptop switches
// off (allow-anonymous=false, trust-forwarded-token=false), an API key set, and its `observability` profile (tracing
// on). render.sh's `before` started the observability compose (Prometheus, Grafana, Jaeger); it is taken down at
// the end. The app also imports this chapter's definitions/ (partner-push: a secret nobody listed).

const SA = 'examples/video-series', KEY = 'series-demo-api-key';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const shown = (cmd) => cmd.replaceAll('$ENGINE', 'localhost:8096');
const H = { 'X-Api-Key': KEY, 'Content-Type': 'application/json' };
const api = (c, path, init = {}) => fetch(c.base + path, { ...init, headers: H }).then((r) => r.json());
const term = (c, id, cmd, opts = {}) => c.term(id, cmd, { cwd: SA, show: shown(cmd), ...opts });

export async function run(c) {
  try {
    // refuse: anonymous → 401 (API) and 403 (MCP); with the key → answered.
    term(c, 'api-401', "curl -s -w '\\nHTTP %{http_code}\\n' $ENGINE/workflow/api/processes | jq -R '. as $l | try ($l | fromjson | {status, detail}) catch $l'");
    term(c, 'mcp-403', "curl -s -m 3 -w '\\nHTTP %{http_code}\\n' $ENGINE/sse | jq -R '. as $l | try ($l | fromjson | .message) catch $l'");
    term(c, 'key-200', `curl -s -H 'X-Api-Key: ${KEY}' "$ENGINE/workflow/api/processes?size=1" | jq '{totalElements}'`);

    // console: the browser gets the 403 page.
    await c.shot('console', c.open('/', 'text=403'), async (s) => {
      s.cursor = false;
      await s.cameraNow('full');
      await s.hold(0.8);
      await s.camera([s.page.getByText('403 Forbidden').first(), s.page.getByText(/refuses anonymous access/).first()], { zmax: 2.0, dur: 1.0, pad: 60 });
    }, { viewport: { w: 1280, h: 720 } });

    // secret: partner-push asks for PARTNER_TOKEN, which workflow.http.secrets never lists.
    term(c, 'partner-1', `curl -s -H 'X-Api-Key: ${KEY}' -H 'Content-Type: application/json' -d '{"definitionId": "partner-push", "businessKey": "PARTNER-1", "variables": {"room": "204", "available": "2"}}' $ENGINE/workflow/api/processes > /dev/null && sleep 2 && id=$(curl -s -H 'X-Api-Key: ${KEY}' "$ENGINE/workflow/api/processes?businessKey=PARTNER-1" | jq -r '.content[0].id') && curl -s -H 'X-Api-Key: ${KEY}' $ENGINE/workflow/api/processes/$id/logs | jq -r '.[] | select(.type == "Error") | .message'`);

    // metrics: some load (channel syncs; three during an outage of the stand-in channel manager) → Grafana.
    term(c, 'load', `for n in $(seq 1 12); do [ $n = 10 ] && curl -s -X POST "$ENGINE/fake/channel-manager/outage?on=true"; curl -s -o /dev/null -w "SYNC-$n %{http_code}  " -H 'X-Api-Key: ${KEY}' -H 'Content-Type: application/json' -d "{\\"definitionId\\": \\"channel-sync\\", \\"businessKey\\": \\"SYNC-$n\\", \\"variables\\": {\\"room\\": \\"10$n\\", \\"date\\": \\"2026-12-24\\", \\"available\\": \\"$n\\"}}" $ENGINE/workflow/api/processes; done; echo`);
    await c.waitFor(async () => (await api(c, '/workflow/api/processes?definitionId=channel-sync&status=ERROR&size=10')).content?.length >= 3, 'the outage incidents', 90000);
    await sleep(25000);   // two Prometheus scrapes and a Grafana refresh
    // The recorder pauses the page's clock at a fixed time, so time ranges are given absolute, from the real clock.
    const now = Date.now();
    await c.shot('metrics', c.open(`http://localhost:3000/d/eventconductor?orgId=1&from=${now - 15 * 60000}&to=${now + 60000}`, 'text=Processes running'), async (s) => {
      s.cursor = false;
      await s.cameraNow('full');
      await s.untilCue(1, 0.3);
      await s.camera([s.page.getByText('Processes running').first(), s.page.getByText('Stalled steps').first()], { zmax: 1.7, dur: 1.0, pad: 120 });
      await s.untilCue(2, -0.6);
      await s.camera('full', { dur: 0.8 });
    });

    // traces: Jaeger, service video-series — the traces list, then one process's trace.
    // One process's trace: the spans carry the business key (eventconductor.process.businessKey).
    const tags = encodeURIComponent(JSON.stringify({ 'eventconductor.process.businessKey': 'SYNC-1' }));
    await c.shot('traces', c.open(`http://localhost:16686/search?service=video-series&tags=${tags}&limit=20&lookback=custom&start=${(now - 30 * 60000) * 1000}&end=${(Date.now() + 60000) * 1000}`, 'text=/video-series: /'), async (s) => {
      s.cursor = false;
      await s.cameraNow('full');
      await s.untilCue(2, -1.4);
      s.cursor = true;
      await s.click(s.page.getByText(/^video-series: /).first(), { dur: 0.6 });
      await s.settleFrames(16);
      await s.park(0.3);
    });
  } finally {
    c.term('obs-down', 'docker compose -p ec-series-obs --profile observability down', { cwd: SA });   // off camera
  }
}
