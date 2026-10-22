const test = require('node:test');
const assert = require('node:assert/strict');
const { ExecutionSession } = require('../out/executionSession');
const { EngineError } = require('../out/engineClient');
function graph(id, status = 'RUNNING', next = null) {
  return { process: { id, name: id, definitionId: 'flow', definitionVersion: id === 'second' ? 2 : 1, status, variables: {}, continuedAs: next },
    testRun: true, definition: { steps: [{ id: 'work', type: 'ACTION' }] }, overlay: {}, steps: [] };
}
function client(overrides = {}) {
  return { graph: async id => graph(id), children: async () => ({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }), logs: async () => [], ...overrides };
}
test('following crosses continuations, preserves their versions and can return to history', async t => {
  const session = new ExecutionSession(client({ graph: async id => id === 'first' ? graph(id, 'COMPLETED', 'second') : graph(id) }), 'first', () => {});
  t.after(() => session.dispose());
  await session.refresh(); assert.equal(session.state.currentId, 'second'); assert.equal(session.state.graph.process.definitionVersion, 2);
  assert.deepEqual(session.state.history.map(p => p.id), ['first', 'second']);
  await session.navigate('first'); assert.equal(session.state.currentId, 'first'); assert.equal(session.state.following, false);
  await session.follow(); assert.equal(session.state.currentId, 'second');
});
test('parallel refresh requests share one polling cycle and step selection filters paged children', async t => {
  let graphs = 0; const filters = [];
  const session = new ExecutionSession(client({ graph: async id => { graphs++; return graph(id); }, children: async (_id, page, step) => {
    filters.push({ page, step }); return { content: [], page, size: 20, totalElements: 40, totalPages: 2 };
  } }), 'root', () => {}); t.after(() => session.dispose());
  await Promise.all([session.refresh(), session.refresh(), session.refresh()]); assert.equal(graphs, 1);
  await session.selectStep('work'); await session.page(1);
  assert.deepEqual(filters.at(-1), { page: 1, step: 'work' });
  await session.selectStep('absent'); assert.equal(session.state.selectedStep, 'work');
});
test('an old response cannot replace a newly selected process', async t => {
  const published = []; let started;
  const waiting = new Promise(resolve => { started = resolve; });
  const session = new ExecutionSession(client({ graph: (id, signal) => {
    if (id !== 'old') return Promise.resolve(graph(id));
    started(); return new Promise((_, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
  } }), 'old', state => published.push(state.graph?.process.id)); t.after(() => session.dispose());
  const pending = session.refresh(); await waiting; await session.navigate('new'); await pending;
  assert.equal(session.state.graph.process.id, 'new'); assert.equal(published.includes('old'), false);
});
test('hidden views abort outstanding reads, resume cleanly and disposed views stay silent', async () => {
  let aborted = false, calls = 0, published = 0;
  const session = new ExecutionSession(client({ graph: (id, signal) => {
    calls++; if (calls > 1) return Promise.resolve(graph(id, 'COMPLETED'));
    return new Promise((_, reject) => signal.addEventListener('abort', () => { aborted = true; reject(new Error('aborted')); }, { once: true }));
  } }), 'root', () => published++);
  const pending = session.refresh(); session.setVisible(false); await pending; assert.equal(aborted, true);
  const before = published; session.setVisible(true);
  await new Promise(resolve => setTimeout(resolve, 5)); assert.equal(session.state.graph.process.id, 'root');
  assert.ok(published > before); session.dispose(); const after = published; await session.refresh(); assert.equal(published, after);
});
test('a missing history run is explained and a denied child page does not hide the accessible graph', async t => {
  const missing = new ExecutionSession(client({ graph: async () => { throw new EngineError('not found', 404); } }), 'purged', () => {});
  t.after(() => missing.dispose()); await missing.refresh(); assert.match(missing.state.error, /purged/);
  const denied = new ExecutionSession(client({ children: async () => { throw new EngineError('access denied', 403); } }), 'root', () => {});
  t.after(() => denied.dispose()); await denied.refresh(); assert.equal(denied.state.graph.process.id, 'root');
  assert.match(denied.state.warnings[0], /access denied/); assert.equal(denied.state.children, undefined);
});
test('circular continuation links are bounded and reported', async t => {
  let calls = 0;
  const session = new ExecutionSession(client({ graph: async id => { calls++; return graph(id, 'COMPLETED', id === 'a' ? 'b' : 'a'); } }), 'a', () => {});
  t.after(() => session.dispose()); await session.refresh(); assert.match(session.state.error, /circular/); assert.equal(calls, 2);
});

test('a not-yet-visible continuation keeps the last accessible snapshot and its ID together', async () => {
  const client = {
    graph: async id => { if (id === 'next') throw new EngineError('Not visible yet', 404); return graph('root', 'COMPLETED', 'next'); },
    children: async () => children(), logs: async () => [],
  };
  const session = new ExecutionSession(client, 'root', () => {});
  await session.refresh();
  assert.equal(session.state.currentId, 'root');
  assert.equal(session.state.graph.process.id, 'root');
  assert.match(session.state.error, /not be visible yet/);
  session.dispose();
});
