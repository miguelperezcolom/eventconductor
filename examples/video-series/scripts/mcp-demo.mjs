#!/usr/bin/env node
// A scripted, deterministic MCP client (chapter 10): real JSON-RPC requests to the engine's real MCP endpoint,
// in a fixed order, with no AI model in the loop — what an assistant would do, minus the guessing.
//
//   node scripts/mcp-demo.mjs                 # against http://localhost:8096
//   ENGINE=http://localhost:8096 node scripts/mcp-demo.mjs MCP-7
//
// It speaks MCP's HTTP+SSE transport (what spring-ai-starter-mcp-server-webmvc serves): GET /sse opens the
// stream and names the endpoint to POST to; every answer comes back on the stream. Node 18+, no dependencies.
//
// The script: initialize → tools/list → listWorkflowDefinitions → publishInboundEvent (a booking created on the
// `bookings` destination, which starts booking-onboarding through its event trigger) → findProcessByBusinessKey
// → getProcessLogs. The MCP server has no "start process" tool: processes start from their doors (an event, a
// schedule, an update-with-start), and so they do here.

const ENGINE = process.env.ENGINE ?? 'http://localhost:8096';
const BOOKING = process.argv[2] ?? 'MCP-1';

const pending = new Map();
let nextId = 1;
let postUrl;

const stream = await fetch(`${ENGINE}/sse`, { headers: { Accept: 'text/event-stream' } });
if (!stream.ok) {
  console.error(`GET /sse answered ${stream.status}: is the app running (and MCP on)?`);
  process.exit(1);
}
const endpointReady = new Promise((resolve) => readEvents(stream.body, resolve));

async function readEvents(body, onEndpoint) {
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of body) {
    buffer += decoder.decode(chunk, { stream: true });
    let cut;
    while ((cut = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, cut);
      buffer = buffer.slice(cut).replace(/^\r?\n\r?\n/, '');
      const event = /^event:\s*(.*)$/m.exec(raw)?.[1]?.trim();
      const data = raw.split(/\r?\n/).filter((l) => l.startsWith('data:')).map((l) => l.slice(5).trimStart()).join('\n');
      if (event === 'endpoint') {
        onEndpoint(new URL(data, ENGINE).toString());
      } else if (event === 'message' && data) {
        const message = JSON.parse(data);
        pending.get(message.id)?.(message);
        pending.delete(message.id);
      }
    }
  }
}

async function send(method, params, notification = false) {
  const message = { jsonrpc: '2.0', method, ...(params ? { params } : {}) };
  let answer;
  if (!notification) {
    message.id = nextId++;
    answer = new Promise((resolve) => pending.set(message.id, resolve));
  }
  const response = await fetch(postUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(message),
  });
  if (!response.ok) throw new Error(`${method}: HTTP ${response.status}`);
  if (notification) return undefined;
  const reply = await answer;
  if (reply.error) throw new Error(`${method}: ${JSON.stringify(reply.error)}`);
  return reply.result;
}

async function tool(name, args, summarize) {
  console.log(`\n→ tools/call ${name} ${JSON.stringify(args)}`);
  const result = await send('tools/call', { name, arguments: args });
  const text = (result.content ?? []).map((c) => c.text ?? '').join('\n');
  let shown = text;
  try {
    const value = JSON.parse(text);
    shown = summarize ? summarize(value) : JSON.stringify(value, null, 2);
  } catch { /* prose, as it came */ }
  console.log(shown.length > 1600 ? `${shown.slice(0, 1600)}\n…` : shown);
  return text;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

postUrl = await endpointReady;
const init = await send('initialize', {
  protocolVersion: '2024-11-05',
  capabilities: {},
  clientInfo: { name: 'video-series-scripted-client', version: '1.0' },
});
console.log(`connected to ${init.serverInfo?.name} ${init.serverInfo?.version ?? ''} (MCP ${init.protocolVersion})`);
await send('notifications/initialized', undefined, true);

const { tools } = await send('tools/list');
console.log(`\n← ${tools.length} tools: ${tools.map((t) => t.name).sort().join(', ')}`);

await tool('listWorkflowDefinitions', {}, (definitions) =>
  definitions.map((d) => `  ${d.id.padEnd(24)} ${d.status.padEnd(8)} ${d.name}`).join('\n'));
await tool('publishInboundEvent', {
  destination: 'bookings',
  type: 'com.example.booking.created',
  data: JSON.stringify({ bookingId: BOOKING, guest: 'Dana', checkIn: '2026-12-24' }),
}, (result) => [`  event ${result.eventId} on ${result.destination}`,
  ...result.receipts.map((r) => `  ${r.receiver}: ${r.outcome} ${r.businessKey ?? ''} ${r.detail ?? ''}`)].join('\n'));

// The process runs on the engine's own threads: look it up until it has finished (a few hundred ms).
const businessKey = `booking-onboarding-${BOOKING}`;
let details = '';
for (let attempt = 0; attempt < 20; attempt++) {
  await sleep(500);
  const result = await send('tools/call', { name: 'findProcessByBusinessKey', arguments: { businessKey } });
  details = (result.content ?? []).map((c) => c.text ?? '').join('\n');
  let status;
  try { status = JSON.parse(details).status; } catch { /* not there yet */ }
  if (['COMPLETED', 'ERROR', 'COMPENSATED', 'CANCELLED'].includes(status)) break;
}
const processDetail = await tool('findProcessByBusinessKey', { businessKey }, (p) => [
  `  ${p.name} · ${p.businessKey} · ${p.status} (${p.completionPct}%)`,
  `  variables: ${p.variables.join(', ')}`,
  ...p.steps.map((s) => `  step ${s.stepId.padEnd(16)} ${s.status}`),
].join('\n'));
const processId = JSON.parse(processDetail).id;
await tool('getProcessLogs', { processId }, (logs) =>
  logs.map((l) => `  ${String(l.timestamp ?? '').slice(11, 19)} ${l.message ?? JSON.stringify(l)}`).join('\n'));

process.exit(0);
