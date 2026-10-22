const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { once } = require('node:events');
const { EngineClient, EngineError, engineEndpoint, processId } = require('../out/engineClient');
const ID = 'c059deca-14e6-4d15-a203-2b539831765c';
async function server(t, handler) {
  const server = http.createServer(handler); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(() => { server.closeAllConnections(); server.close(); });
  return `http://127.0.0.1:${server.address().port}`;
}
test('engine endpoints preserve context paths and reject credentials and ambiguous URLs', () => {
  assert.equal(engineEndpoint('https://engine.invalid/context/workflow/api/').href, 'https://engine.invalid/context/');
  for (const url of ['file:///tmp/engine', 'https://user:password@engine.invalid', 'https://engine.invalid?token=x', 'https://engine.invalid/#x']) {
    assert.throws(() => engineEndpoint(url));
  }
  assert.throws(() => new EngineClient('http://engine.invalid', { apiKey: 'synthetic-key' }), /HTTPS/);
  assert.throws(() => new EngineClient('https://engine.invalid', { authorization: 'Bearer x\r\nInjected: y' }));
  for (const id of ['../other', '..', '/etc/passwd', 'id?secret=x', 'id\n']) assert.throws(() => processId(id));
});
test('the extension-host transport carries the draft and credentials only to its configured engine', async t => {
  const received = [];
  const url = await server(t, (req, res) => {
    const chunks = []; req.on('data', c => chunks.push(c)); req.on('end', () => {
      received.push({ path: req.url, method: req.method, headers: req.headers, body: JSON.parse(Buffer.concat(chunks)) });
      res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify({ id: ID, status: 'RUNNING' }));
    });
  });
  const request = { runId: ID, definition: { id: 'draft', steps: [] }, variables: { amount: 12, lines: [{ id: 1 }] } };
  const client = new EngineClient(url + '/context', { apiKey: 'synthetic-key', authorization: 'Bearer synthetic-token' });
  assert.equal((await client.start(request)).id, ID);
  assert.equal(received[0].path, '/context/workflow/api/test-runs');
  assert.equal(received[0].method, 'POST'); assert.deepEqual(received[0].body, request);
  assert.equal(received[0].headers['x-api-key'], 'synthetic-key');
  assert.equal(received[0].headers.authorization, 'Bearer synthetic-token');
});
test('redirects never forward engine credentials or execute another request', async t => {
  let redirected = false;
  const url = await server(t, (req, res) => {
    if (req.url === '/stolen') { redirected = true; res.end('{}'); return; }
    res.writeHead(302, { Location: '/stolen' }); res.end();
  });
  await assert.rejects(new EngineClient(url, { apiKey: 'synthetic-key' }).start({ runId: ID }), /redirects/);
  assert.equal(redirected, false);
});
test('denied access and an old or disabled engine produce actionable messages', async t => {
  let status = 401;
  const url = await server(t, (_, res) => { res.writeHead(status); res.end('<html>private error</html>'); });
  const client = new EngineClient(url);
  await assert.rejects(client.graph(ID), error => error instanceof EngineError && error.status === 401 && !error.message.includes('private'));
  status = 403;
  await assert.rejects(client.start({ runId: ID }), error => error.status === 403 && /test-runs.required-roles/.test(error.message));
  status = 404;
  await assert.rejects(client.start({ runId: ID }), /test-runs.enabled=true/);
});
test('oversized, malformed and mismatched responses are rejected', async t => {
  let response = 'x'.repeat(120);
  const url = await server(t, (_, res) => res.end(response));
  await assert.rejects(new EngineClient(url, {}, { maxBytes: 100 }).graph(ID), /too large/);
  response = '{}'; await assert.rejects(new EngineClient(url).graph(ID), /invalid execution graph/);
  response = JSON.stringify({ id: 'another-id' });
  await assert.rejects(new EngineClient(url).start({ runId: ID }), /another execution identifier/);
});
test('timeouts and visibility cancellation abort reads without posting an execution', async t => {
  let posts = 0;
  const url = await server(t, req => { if (req.method === 'POST') posts++; });
  await assert.rejects(new EngineClient(url, {}, { timeoutMs: 20 }).graph(ID), /timed out/);
  const controller = new AbortController();
  const reading = new EngineClient(url).graph(ID, controller.signal); controller.abort();
  await assert.rejects(reading, /cancelled/); assert.equal(posts, 0);
});

test('cancellation uses the existing authenticated server endpoint and validates its acknowledgement',async t=>{
  const requests=[];let wrong=false;
  const url=await server(t,(req,res)=>{
    requests.push({method:req.method,path:req.url,authorization:req.headers.authorization});
    res.setHeader('Content-Type','application/json');res.statusCode=202;
    res.end(JSON.stringify({processId:wrong?'other':ID,command:'cancel'}));
  });
  const client=new EngineClient(url+'/context',{authorization:'Basic synthetic'});
  await client.cancel(ID);assert.deepEqual(requests[0],{method:'POST',path:`/context/workflow/api/processes/${ID}/cancel`,authorization:'Basic synthetic'});
  wrong=true;await assert.rejects(client.cancel(ID),/invalid cancellation acknowledgement/);
  await assert.rejects(client.cancel('../other'),/Invalid execution/);
});
