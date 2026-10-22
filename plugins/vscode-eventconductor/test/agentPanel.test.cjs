const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// Exercise the shared component's migration logic without a browser or layout engine.
const source = path.resolve(__dirname, '../../../modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts');
const { outputText } = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
});
const exportsObject = {};
vm.runInNewContext(outputText, {
  exports: exportsObject,
  CustomEvent: class { constructor(type, options) { this.type=type;Object.assign(this,options); } },
  require: name => {
    if (name === 'lit') return { LitElement: class {}, css: () => '', html: (strings,...values) => strings.map((part,i)=>part+(values[i]??'')).join(''), svg: () => '', nothing: null };
    if (name === 'lit/decorators.js') return { customElement: () => value => value, property: () => () => {}, state: () => () => {} };
    if (name === './neutralChrome') return {};
    throw new Error(`Unexpected component dependency: ${name}`);
  },
});
const prototype = exportsObject.MateuWorkflowElk.prototype;
const plain = value => JSON.parse(JSON.stringify(value));

for (const ref of ['previous-agent', 'new-agent']) {
  test(`adopting catalogue configuration for ${ref} removes all overrides and preserves task context`, () => {
    const step = {
      id: 'assess', type: 'AGENT', agent: {
        ref: 'previous-agent', taskPrompt: 'Assess this case', input: { caseId: '${caseId}' }, outputVariable: 'assessment',
        mcp: [], rag: ['limited-handbook'], peers: ['limited-peer'], allowSensitiveVariables: true,
      },
    };
    const original = structuredClone(step);
    const changes = [];
    const component = { updateStep: (id, patch) => changes.push([id, plain(patch)]) };
    prototype.useAgentCatalogueConfiguration.call(component, step, ref);
    assert.deepEqual(changes, [['assess', { agent: {
      ref, taskPrompt: 'Assess this case', input: { caseId: '${caseId}' }, outputVariable: 'assessment',
      allowSensitiveVariables: true,
    } }]]);
    assert.deepEqual(step, original, 'migration must not mutate the imported workflow object');
  });
}

test('the shared graph renders Run only for an editable IDE host and emits an intent without a payload',()=>{
  const render = (hostExecution,readOnly) => prototype.renderToolbar.call({
    wf:{name:'Synthetic workflow'},declaredState:()=> 'ACTIVE',hostExecution,readOnly,
  });
  assert.match(render(true,false),/Run workflow/);
  assert.doesNotMatch(render(false,false),/Run workflow/);
  assert.doesNotMatch(render(true,true),/Run workflow/);
  const events=[];
  prototype.requestWorkflowExecution.call({hostExecution:true,readOnly:false,dispatchEvent:event=>events.push(event)});
  assert.equal(events[0].type,'ec-run-workflow');assert.equal(events[0].bubbles,true);assert.equal(events[0].composed,true);
  assert.equal(events[0].detail,undefined);
  prototype.requestWorkflowExecution.call({hostExecution:false,readOnly:false,dispatchEvent:()=>assert.fail('No host')});
  prototype.requestWorkflowExecution.call({hostExecution:true,readOnly:true,dispatchEvent:()=>assert.fail('Read only')});
});

test('the shared graph exposes Stop only for a host with an active execution and emits no target ID',()=>{
  const render=(active,stopping)=>prototype.renderToolbar.call({wf:{name:'Synthetic'},declaredState:()=> 'ACTIVE',hostExecution:true,readOnly:false,hostExecutionActive:active,hostExecutionStopping:stopping});
  assert.match(render(true,false),/Stop execution/);assert.doesNotMatch(render(false,false),/Stop execution/);assert.match(render(true,true),/Stopping…/);
  const events=[];
  prototype.requestWorkflowStop.call({hostExecution:true,hostExecutionActive:true,hostExecutionStopping:false,dispatchEvent:e=>events.push(e)});
  assert.equal(events[0].type,'ec-stop-workflow');assert.equal(events[0].detail,undefined);
  for(const flags of [{hostExecution:false,hostExecutionActive:true},{hostExecution:true,hostExecutionActive:false},{hostExecution:true,hostExecutionActive:true,hostExecutionStopping:true}]) {
    prototype.requestWorkflowStop.call({...flags,dispatchEvent:()=>assert.fail('Stop unavailable')});
  }
});
