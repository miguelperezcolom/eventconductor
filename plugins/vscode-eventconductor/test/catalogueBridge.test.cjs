const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const extensionRoot = path.resolve(__dirname, '..');
const normal = value => JSON.parse(JSON.stringify(value));
const disposable = () => ({ dispose() {} });

// Exercise the actual activated provider without depending on an installed VS Code process.
function extensionHost({ trusted = true, endpoint = 'http://127.0.0.1:18105/api/workflow-agents', fetch, applyEdit = async () => true } = {}) {
  const providers = new Map();
  const fetched = [];
  const runs = [];
  const errors = [];
  const secretReads = [];
  const authorization = 'Basic private-test-credential';
  const uri = value => ({ path: value, toString: () => value });
  const context = {
    subscriptions: [],
    extensionUri: uri('/extension'),
    secrets: {
      onDidChange: disposable,
      get: async key => { secretReads.push(key); return authorization; },
    },
  };
  const vscode = {
    WorkspaceEdit: class { replace() {} },
    Range: class {},
    Uri: { joinPath: (base, ...parts) => uri(path.posix.join(base.path, ...parts)) },
    window: { showErrorMessage: message => errors.push(message), registerCustomEditorProvider: (type, provider) => { providers.set(type, provider); return disposable(); } },
    commands: { registerCommand: disposable, executeCommand: async (name, uri) => runs.push({name, uri:uri.toString()}) },
    workspace: {
      isTrusted: trusted, applyEdit,
      getConfiguration: section => {
        assert.equal(section, 'eventconductor');
        return { get: key => { assert.equal(key, 'agentCatalogueUrl'); return endpoint; } };
      },
      onDidChangeConfiguration: disposable,
      onDidGrantWorkspaceTrust: disposable,
      onDidChangeTextDocument: disposable,
    },
    extensions: { getExtension: () => undefined },
  };
  const exports = {};
  const sandbox = {
    exports, Buffer,
    require: name => {
      if (name === 'vscode') return vscode;
      if (name === 'js-yaml') return { load: JSON.parse, dump: JSON.stringify };
      if (name === './taskProject') return {};
      if (name === './agentCatalogue') return {
        catalogueEndpoint: value => new URL(value),
        fetchAgentCatalogue: async (...args) => {
          fetched.push(args);
          return fetch ? fetch(...args) : [{ id: 'workflow-case-assessor', name: 'Case assessor' }];
        },
      };
      if (name === "./execution") return {
        registerWorkflowExecution() {}, executionMarkup:()=> '<section id="execution-dock" hidden></section>',
        GraphExecutionController: class {
          constructor(context,panel,document) { this.document=document; }
          async open() { runs.push({name:'eventconductor.runWorkflow',uri:this.document.uri.toString()}); }
          async message() {}
        },
      };
      throw new Error(`Unexpected dependency: ${name}`);
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(extensionRoot, 'out/extension.js'), 'utf8'), sandbox);
  exports.activate(context);

  function open(type = 'eventconductor.graphEditor') {
    const messages = [];
    let receive;
    let dispose;
    const webview = {
      cspSource: 'vscode-webview://test',
      asWebviewUri: resource => resource.toString(),
      postMessage: message => { messages.push(normal(message)); return Promise.resolve(true); },
      onDidReceiveMessage: handler => { receive = handler; return disposable(); },
    };
    const panel = { webview, onDidDispose: handler => { dispose = handler; return disposable(); } };
    providers.get(type).resolveCustomTextEditor({
      uri: uri('/workflow.ec'),
      getText: () => '{"name":"Test","steps":[]}',
      lineCount: 1,
    }, panel);
    return { webview, messages, receive: message => receive(message), dispose: () => dispose() };
  }
  return { open, fetched, secretReads, authorization, runs, errors };
}

test('graph opts into the host bridge and uses only configured endpoint and SecretStorage auth', async () => {
  const host = extensionHost();
  const graph = host.open();
  assert.match(graph.webview.html, /<eventconductor-workflow-graph no-expand host-agent-catalogue host-execution>/);
  await graph.receive({
    type: 'requestAgentCatalogue',
    url: 'https://attacker.invalid/collect',
    endpoint: 'https://attacker.invalid/collect',
    headers: { Authorization: 'Bearer injected' },
    authorization: 'Bearer injected',
  });
  assert.deepEqual(host.fetched, [['http://127.0.0.1:18105/api/workflow-agents', host.authorization]]);
  assert.deepEqual(host.secretReads, ['eventconductor.agentCatalogue.auth:http://127.0.0.1:18105/api/workflow-agents']);
  assert.deepEqual(graph.messages, [{
    type: 'agentCatalogue', entries: [{ id: 'workflow-case-assessor', name: 'Case assessor' }], error: '',
  }]);
  assert.equal(JSON.stringify(graph.messages).includes(host.authorization), false);
  assert.equal(graph.webview.html.includes(host.authorization), false);
  assert.equal(JSON.stringify(graph.messages).includes('attacker.invalid'), false);
});

test('untrusted workspaces do not read secrets or contact the catalogue', async () => {
  const host = extensionHost({ trusted: false });
  const graph = host.open();
  await graph.receive({ type: 'requestAgentCatalogue' });
  assert.equal(host.fetched.length, 0);
  assert.equal(host.secretReads.length, 0);
  assert.deepEqual(graph.messages[0].entries, []);
  assert.match(graph.messages[0].error, /Trust this workspace/);
});

test('an unconfigured catalogue does not contact a URL supplied by the webview', async () => {
  const host = extensionHost({ endpoint: '' });
  const graph = host.open();
  await graph.receive({ type: 'requestAgentCatalogue', url: 'https://attacker.invalid' });
  assert.equal(host.fetched.length, 0);
  assert.equal(host.secretReads.length, 0);
  assert.deepEqual(graph.messages[0].entries, []);
  assert.match(graph.messages[0].error, /Configure Agent Catalogue/);
});

test('form editors do not opt in or respond to catalogue messages', async () => {
  const host = extensionHost();
  const form = host.open('eventconductor.formEditor');
  assert.doesNotMatch(form.webview.html, /host-agent-catalogue/);
  await form.receive({ type: 'requestAgentCatalogue' });
  assert.deepEqual(form.messages, []);
  assert.deepEqual(host.fetched, []);
  assert.deepEqual(host.secretReads, []);
});

test('a failed refresh clears previously loaded entries', async () => {
  let attempts = 0;
  const host = extensionHost({ fetch: async () => {
    if (++attempts === 1) return [{ id: 'previous-agent' }];
    throw new Error('Catalogue is offline');
  } });
  const graph = host.open();
  await graph.receive({ type: 'requestAgentCatalogue' });
  await graph.receive({ type: 'requestAgentCatalogue' });
  assert.deepEqual(graph.messages[0].entries, [{ id: 'previous-agent' }]);
  assert.deepEqual(graph.messages[1].entries, []);
  assert.equal(graph.messages[1].type, 'agentCatalogue');
  assert.ok(graph.messages[1].error);
});

test('an obsolete request cannot replace a newer catalogue result', async () => {
  const pending = [];
  const host = extensionHost({ fetch: () => new Promise(resolve => pending.push(resolve)) });
  const graph = host.open();
  const first = graph.receive({ type: 'requestAgentCatalogue' });
  await new Promise(setImmediate);
  const second = graph.receive({ type: 'requestAgentCatalogue' });
  await new Promise(setImmediate);
  pending[1]([{ id: 'newest-agent' }]);
  await second;
  pending[0]([{ id: 'obsolete-agent' }]);
  await first;
  assert.deepEqual(graph.messages, [{ type: 'agentCatalogue', entries: [{ id: 'newest-agent' }], error: '' }]);
});

test('a disposed graph never receives an outstanding catalogue response', async () => {
  let finish;
  const host = extensionHost({ fetch: () => new Promise(resolve => { finish = resolve; }) });
  const graph = host.open();
  const request = graph.receive({ type: 'requestAgentCatalogue' });
  await new Promise(setImmediate);
  graph.dispose();
  finish([{ id: 'late-agent' }]);
  await request;
  assert.deepEqual(graph.messages, []);
});

function webviewBridge() {
  const listeners = new Map();
  const attributes = new Map();
  const messages = [];
  const catalogues = [];
  const windowListeners = new Map();
  const el = {
    addEventListener: (name, handler) => listeners.set(name, handler),
    getAttribute: name => attributes.get(name),
    setAttribute: (name, value) => attributes.set(name, value),
    removeAttribute: name => attributes.delete(name),
    setAgentCatalogue: (...args) => catalogues.push(args),
  };
  const sandbox = {
    acquireVsCodeApi: () => ({ postMessage: message => messages.push(normal(message)) }),
    document: {
      querySelector: name => { assert.equal(name, 'eventconductor-workflow-graph'); return el; },
      getElementById: () => ({ style: {} }),
      body: { classList: { contains: () => false } },
    },
    window: {
      addEventListener: (name, handler) => windowListeners.set(name, handler),
      ecCreateGraphProjection: require('../media/graph-state').createGraphProjection,
      ecExecutionBridge: () => ({updateNotice(){}}),
    },
    customElements: { whenDefined: name => { assert.equal(name, 'eventconductor-workflow-graph'); return Promise.resolve(); } },
    MutationObserver: class { observe() {} },
    setTimeout,
  };
  vm.runInNewContext(fs.readFileSync(path.join(extensionRoot, 'media/main.js'), 'utf8'), sandbox);
  return {
    messages, catalogues,
    emit: (name, event) => listeners.get(name)(event),
    receive: message => windowListeners.get('message')({ data: message }),
  };
}

test('webview catalogue event sends only a reload request, ignoring supplied URL and headers', () => {
  const bridge = webviewBridge();
  assert.deepEqual(bridge.messages, [{ type: 'ready' }]);
  bridge.emit('ec-agent-catalogue-request', { detail: { url: 'https://attacker.invalid', headers: { Authorization: 'secret' } } });
  bridge.receive({ type: 'reloadAgentCatalogue', url: 'https://attacker.invalid' });
  assert.deepEqual(bridge.messages.slice(1), [{ type: 'requestAgentCatalogue' }, { type: 'requestAgentCatalogue' }]);
});

test('webview passes catalogue results and errors into the graph component', async () => {
  const bridge = webviewBridge();
  const entries = [{ id: 'workflow-case-assessor' }];
  bridge.receive({ type: 'agentCatalogue', entries, error: '' });
  await Promise.resolve();
  bridge.receive({ type: 'agentCatalogue', entries: [], error: 'Catalogue unavailable' });
  await Promise.resolve();
  assert.deepEqual(bridge.catalogues, [[entries, ''], [[], 'Catalogue unavailable']]);
  assert.deepEqual(bridge.messages, [{ type: 'ready' }]);
});

test('the graph run button targets its own document and ignores a URI sent by the webview',async()=>{
  const host=extensionHost(); const graph=host.open();
  await graph.receive({type:'runWorkflow',uri:'https://attacker.invalid/another.ec'});
  assert.deepEqual(host.runs,[{name:'eventconductor.runWorkflow',uri:'/workflow.ec'}]);
  assert.equal(host.secretReads.length,0);
});

test('forms, disposed graphs and untrusted workspaces cannot invoke workflow execution',async()=>{
  const host=extensionHost({trusted:false}); const graph=host.open();
  await graph.receive({type:'runWorkflow'}); assert.deepEqual(host.runs,[]); assert.match(host.errors[0],/Trust/);
  const trusted=extensionHost(); const form=trusted.open('eventconductor.formEditor');
  assert.doesNotMatch(form.webview.html,/host-execution/);
  await form.receive({type:'runWorkflow'}); assert.deepEqual(trusted.runs,[]);
  const closed=trusted.open(); closed.dispose(); await closed.receive({type:'runWorkflow'});
  assert.deepEqual(trusted.runs,[]);
});

test('Run waits for preceding graph edits and refuses to execute when the document edit failed',async()=>{
  let release;
  const h=extensionHost({applyEdit:()=>new Promise(resolve=>{release=resolve;})}); const graph=h.open();
  const edit=graph.receive({type:'edit',value:'{"name":"Edited","steps":[]}'});
  const run=graph.receive({type:'runWorkflow'}); await new Promise(resolve=>setImmediate(resolve));
  assert.equal(h.runs.length,0); release(true); await edit; await run;
  assert.equal(h.runs.length,1);
  const failed=extensionHost({applyEdit:async()=>false}); const broken=failed.open();
  await broken.receive({type:'edit',value:'{"name":"Edited","steps":[]}'});
  await broken.receive({type:'runWorkflow'}); assert.equal(failed.runs.length,0);
  assert.match(failed.errors.at(-1),/could not be applied/);
});
