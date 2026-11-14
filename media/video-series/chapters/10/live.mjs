// Chapter 10 — what is really run and filmed, in order. Two apps, both fresh: the series app (ENGINE, port 8096:
// AGENT against its demo agent catalogue/runtime, and its MCP server) and the workshop app (ENGINE_WS, port 8095:
// LLM_CALL against its local stand-in model). Commands are the apps' own scripts.

const SA = 'examples/video-series', WS = 'examples/workshop-hotel-booking';
const WS_BASE = process.env.ENGINE_WS || 'http://localhost:8095';
const REQUEST_6 = '{"guest": "Erin", "specialRequest": "I use a wheelchair, please make sure the room is accessible", "rooms": [{"number": "101", "type": "double"}]}';
const AGENT_1 = '{"guest": "Erin", "room": "204", "specialRequest": "I use a wheelchair"}';
const DONE = ['COMPLETED', 'COMPENSATED', 'ERROR', 'CANCELLED'];

const graph = (s) => s.locator('eventconductor-workflow-graph');
const node = (s, text) => graph(s).locator('g.node').filter({ hasText: text }).first();
const tab = (s, name) => s.page.getByText(name, { exact: true }).first();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const wsByKey = async (key) => (await (await fetch(`${WS_BASE}/workflow/api/processes?size=200`)).json()).content.find((p) => p.businessKey === key);

/** Open a process page (of either app) and select a tab, off camera. */
const openTab = (c, url, name) => async (page) => {
  await c.open(url, `text=${name}`)(page);
  await page.getByText(name, { exact: true }).first().click();
  for (let i = 0; i < 25; i++) { await page.clock.runFor(100); await sleep(50); }
};

export async function run(c) {
  // llm-run: the workshop's special-request on its stand-in model; the answer in the Variables tab.
  c.term('request-6', `scripts/start.sh special-request BOOKING-6 '${REQUEST_6}' | jq '{businessKey, status}'`, { cwd: WS, env: { ENGINE: WS_BASE } });
  const p6 = await c.waitFor(async () => { const p = await wsByKey('BOOKING-6'); return DONE.includes(p?.status) && p; }, 'BOOKING-6 done');
  await c.shot('llm-run', openTab(c, `${WS_BASE}/workflow/processes/${p6.id}`, 'Variables'), async (s) => {
    s.cursor = false;
    await s.cameraNow('full');
    await s.untilCue(1, 0.2);
    await s.camera([s.page.getByText('requestCategory', { exact: true }).first(), s.page.getByText('needsStaff', { exact: true }).first()], { zmax: 1.8, dur: 1.0, pad: 80 });
    await s.untilCue(2, -0.3);
    await s.camera(await s.rowBox(s.page.getByText('classifyUsage', { exact: true }).first(), { before: 40, span: 1500 }), { zmax: 1.8, dur: 1.0, pad: 50 });
  }, { viewport: { w: 1280, h: 720 } });

  // agent-run: AGENT on the demo catalogue/runtime → the CHOICE takes "Alert the front desk".
  c.term('agent-1', `scripts/start.sh special-request-agent AGENT-1 '${AGENT_1}' | jq '{businessKey, status}'`, { cwd: SA });
  const a1 = await c.waitFor(async () => { const p = await c.processByKey('AGENT-1'); return DONE.includes(p?.status) && p; }, 'AGENT-1 done');
  await c.shot('agent-run', openTab(c, `/workflow/processes/${a1.id}`, 'Diagram'), async (s) => {
    s.cursor = false;
    await s.cameraNow(graph(s).locator('g.node'), { zmax: 1.5 });
    await s.untilCue(1, 0.2);
    await s.camera([node(s, 'Prepare the special'), node(s, 'Alert the front'), node(s, 'Note it on')], { zmax: 2.0, dur: 1.0 });
    await s.untilCue(2, -1.0);
    await s.camera('full', { dur: 0.5 });
    s.cursor = true;
    await s.click(tab(s, 'Variables'), { dur: 0.6 });
    await s.settleFrames(8);
    await s.park(0.4);
    s.cursor = false;
    await s.camera(await s.rowBox(s.page.getByText('preparation', { exact: true }).first(), { before: 40, span: 1600 }), { zmax: 1.6, dur: 1.0, pad: 50 });
  });

  // mcp: the scripted MCP client against the series app's /sse — real JSON-RPC, no model.
  c.term('mcp-demo', 'node scripts/mcp-demo.mjs MCP-7', { cwd: SA });
}
