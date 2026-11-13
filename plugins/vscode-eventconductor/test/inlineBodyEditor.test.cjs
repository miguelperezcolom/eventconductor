const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// The graph component's inline-body editing and its new panel fields, without a browser: the
// component is transpiled and its methods called on a plain object standing in for the element.
const source = path.resolve(__dirname, '../../../modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts');
const { outputText } = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
});
const template = (strings, ...values) => ({ strings, values });
const exportsObject = {};
vm.runInNewContext(outputText, {
  exports: exportsObject,
  CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
  require: name => {
    if (name === 'lit') return { LitElement: class {}, css: () => '', html: template, svg: template, nothing: null };
    if (name === 'lit/decorators.js') return { customElement: () => value => value, property: () => () => {}, state: () => () => {} };
    if (name === './neutralChrome') return {};
    throw new Error(`Unexpected component dependency: ${name}`);
  },
});
const prototype = exportsObject.MateuWorkflowElk.prototype;
const plain = value => JSON.parse(JSON.stringify(value));

/** A stand-in element: the component's own methods, a definition, and every emitted document. */
function editor(definition) {
  const emitted = [];
  const el = Object.create(prototype);
  Object.assign(el, {
    wf: plain(definition), positions: { start: { x: 1, y: 2 } }, elkPositioned: new Set(['start']), expanded: new Set(),
    beforeExpansion: null, zoomK: 1.5, panX: 10, panY: 20, selectedId: 'rooms', selectedEdge: null, overlayData: {},
    bodyFrames: [], bodyPath: [], layoutReady: true, didInitialFit: true, readOnly: false,
    processKindStash: new Map(),
    runElkLayout: async () => {},
    dispatchEvent: e => { if (e.type === 'value-changed') emitted.push(JSON.parse(e.detail.value)); return true; },
  });
  return { el, emitted, last: () => emitted[emitted.length - 1] };
}

const booking = () => ({
  name: 'Room booking', layout: { start: { x: 40, y: 140 } },
  steps: [
    { id: 'start', type: 'START' },
    { id: 'rooms', type: 'PROCESS', preconditionStepId: 'start', forEach: { items: 'rooms', as: 'room' },
      steps: [
        { id: 'start', type: 'START' },
        { id: 'create', type: 'ACTION', preconditionStepId: 'start' },
        { id: 'extras', type: 'PROCESS', preconditionStepId: 'create', steps: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END', preconditionStepId: 'start' }] },
        { id: 'end', type: 'END', preconditionStepId: 'extras' },
      ] },
    { id: 'done', type: 'END', preconditionStepId: 'rooms' },
  ],
});

test('editing an inline body writes it back into its PROCESS step and the host only ever sees the whole definition', () => {
  const { el, last } = editor(booking());
  el.enterBody('rooms');
  assert.deepEqual(plain(el.bodyPath), ['rooms']);
  assert.deepEqual(plain(el.wf.steps.map(s => s.id)), ['start', 'create', 'extras', 'end'], 'the body is now the graph');
  assert.equal(el.selectedId, null);
  el.updateStep('create', { name: 'Create booking' });
  const doc = last();
  assert.deepEqual(doc.steps.map(s => s.id), ['start', 'rooms', 'done'], 'the emitted document is the whole definition');
  assert.equal(doc.steps[1].steps[1].name, 'Create booking');
  assert.deepEqual(doc.layout, { start: { x: 40, y: 140 } }, 'the top-level arrangement is untouched');
  assert.equal('layout' in doc.steps[1], false, 'a body arrangement is not written into the step');
});

test('nested bodies compose level by level, and leaving restores the level as it was viewed', () => {
  const { el, last } = editor(booking());
  el.enterBody('rooms');
  el.enterBody('extras');
  assert.deepEqual(plain(el.bodyPath), ['rooms', 'extras']);
  el.wf = { ...el.wf, steps: [...el.wf.steps, { id: 'notify', type: 'ACTION' }] };
  el.emit();
  assert.deepEqual(last().steps[1].steps[2].steps.map(s => s.id), ['start', 'end', 'notify']);
  el.leaveBody();
  assert.deepEqual(plain(el.bodyPath), ['rooms']);
  assert.deepEqual(plain(el.wf.steps.find(s => s.id === 'extras').steps.map(s => s.id)), ['start', 'end', 'notify'],
    'the level we are back on carries the edit made below it');
  el.leaveBody();
  assert.deepEqual(plain(el.bodyPath), []);
  assert.deepEqual(plain(el.positions), { start: { x: 1, y: 2 } });
  assert.equal(el.zoomK, 1.5); assert.equal(el.panX, 10); assert.equal(el.selectedId, 'rooms');
  assert.equal(el.wf.name, 'Room booking');
  assert.deepEqual(plain(el.wf.steps[1].steps[2].steps.map(s => s.id)), ['start', 'end', 'notify']);
});

test('a document the host sends back while a body is open shows that body, or closes it when the path is gone', () => {
  const { el } = editor(booking());
  el.enterBody('rooms');
  const echoed = booking(); echoed.steps[1].steps[1].name = 'Edited elsewhere';
  assert.equal(el.reopenBodiesIn(echoed).steps[1].name, 'Edited elsewhere');
  assert.deepEqual(plain(el.bodyPath), ['rooms']);
  const replaced = booking(); delete replaced.steps[1].steps; replaced.steps[1].childWorkflowDefinitionId = 'room';
  const shown = el.reopenBodiesIn(replaced);
  assert.deepEqual(plain(shown.steps.map(s => s.id)), ['start', 'rooms', 'done']);
  assert.deepEqual(plain(el.bodyPath), [], 'every body closed');
});

test('a PROCESS switches between a named child and a body written in line, keeping the other side for the session', () => {
  const def = { name: 'w', steps: [{ id: 'invoice', type: 'PROCESS', childWorkflowDefinitionId: 'invoicing' }] };
  const { el, last } = editor(def);
  el.setProcessKind(el.wf.steps[0], 'inline');
  let step = last().steps[0];
  assert.equal(step.childWorkflowDefinitionId, undefined);
  assert.deepEqual(step.steps.map(s => [s.id, s.type]), [['start', 'START'], ['end', 'END']]);
  assert.equal(step.steps[1].preconditionStepId, 'start');
  el.setProcessKind(el.wf.steps[0], 'named');
  step = last().steps[0];
  assert.equal(step.childWorkflowDefinitionId, 'invoicing');
  assert.equal(step.steps, undefined);
});

function fieldsOf(render) {
  const fields = {};
  render((label, body) => { fields[label] = body; return { label }; });
  return fields;
}
/** All interpolated values of a template, flattened through nested templates and arrays. */
function flat(t, out = []) {
  if (Array.isArray(t)) { t.forEach(v => flat(v, out)); return out; }
  if (t && t.strings && t.values) { out.push(t); t.values.forEach(v => flat(v, out)); }
  return out;
}
const textOf = t => flat(t).map(x => x.strings.join('')).join('') + flat(t).flatMap(x => x.values).filter(v => typeof v === 'string').join(' ');

test('the forEach section edits onIterationFailure and label, and flags what definition-analysis refuses', () => {
  const step = { id: 'rooms', type: 'PROCESS', forEach: { items: 'rooms', completion: 'any', onIterationFailure: 'compensateAll' } };
  const changes = [];
  const component = { readOnly: false, wf: { steps: [step] }, updateStep: (id, patch) => changes.push([id, plain(patch)]) };
  const rendered = prototype.renderLoopFields.call(component, step, (label, body) => ({ label, body }));
  const all = flat(rendered);
  const labels = all.flatMap(t => t.values).filter(v => v && v.label).map(v => v.label);
  assert.ok(labels.includes('On iteration failure')); assert.ok(labels.includes('Iteration label'));
  assert.match(textOf(rendered), /compensateAll waits for every iteration, which completion 'any' would cut short/);

  const select = all.flatMap(t => t.values).find(v => v && v.label === 'On iteration failure').body;
  const onChange = select.values.find(v => typeof v === 'function');
  onChange({ target: { value: 'continue' } });
  onChange({ target: { value: 'failFast' } });
  assert.deepEqual(changes, [
    ['rooms', { forEach: { items: 'rooms', completion: 'any', onIterationFailure: 'continue' } }],
    ['rooms', { forEach: { items: 'rooms', completion: 'any' } }],
  ], 'failFast is the default and is not written');
});

test('compensateLosers shows on an XOR join, compensateAbandoned on an END, each refused elsewhere', () => {
  const panelOf = step => {
    const component = Object.assign(Object.create(prototype), {
      readOnly: false, selectedId: step.id, wf: { steps: [step] }, agentCatalogue: [], overlayData: {},
      updateStep: () => {}, renderLoopFields: () => '', renderAssignmentFields: () => '', renderAdvancedFields: () => '', renderContinueAsNewFields: () => '',
    });
    return textOf(prototype.renderPanel.call(component));
  };
  assert.match(panelOf({ id: 'j', type: 'JOIN', joinType: 'XOR' }), /data-compensate-losers/);
  assert.doesNotMatch(panelOf({ id: 'j', type: 'JOIN', joinType: 'AND' }), /data-compensate-losers/);
  assert.match(panelOf({ id: 'e', type: 'END' }), /data-compensate-abandoned/);
  assert.match(panelOf({ id: 'j', type: 'JOIN', joinType: 'AND', compensateLosers: true }),
    /compensateLosers is only allowed on a JOIN with joinType XOR/);
  assert.match(panelOf({ id: 'p', type: 'PROCESS', steps: [{ id: 'start', type: 'START' }] }), /written in line/);
});
