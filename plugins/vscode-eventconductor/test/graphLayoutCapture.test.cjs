const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// The graph writes a `layout` block only when the author arranges it: a click that selects a node
// (mousedown + mouseup with no movement) must not put coordinates into a file nobody arranged.
const source = path.resolve(__dirname, '../../../modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts');
const { outputText } = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
});
const template = (strings, ...values) => ({ strings, values });
const exportsObject = {};
vm.runInNewContext(outputText, {
  exports: exportsObject,
  window: { addEventListener() {}, removeEventListener() {}, localStorage: { getItem: () => null, setItem() {} } },
  localStorage: { getItem: () => null, setItem() {} },
  CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
  require: name => {
    if (name === 'lit') return { LitElement: class {}, css: () => '', html: template, svg: template, nothing: null };
    if (name === 'lit/decorators.js') return { customElement: () => value => value, property: () => () => {}, state: () => () => {} };
    if (name === './neutralChrome') return {};
    throw new Error(`Unexpected component dependency: ${name}`);
  },
});
const Graph = exportsObject.MateuWorkflowElk;

function graph() {
  const emitted = [];
  const svg = { getBoundingClientRect: () => ({ left: 0, top: 0 }) };
  const g = new Graph();
  Object.assign(g, {
    wf: { name: 'W', steps: [{ id: 'a', type: 'START' }, { id: 'b', type: 'END', preconditionStepId: 'a' }] },
    positions: { a: { x: 10, y: 10 }, b: { x: 10, y: 120 } },
    svgEl: svg, elkPositioned: new Set(), readOnly: false, panX: 0, panY: 0, zoomK: 1, bodyFrames: [],
    dispatchEvent: e => emitted.push(JSON.parse(e.detail.value)),
  });
  const press = (x, y) => g.onNodeMouseDown({ clientX: x, clientY: y, preventDefault() {}, currentTarget: { closest: () => svg } }, 'a');
  const move = (x, y) => g.onMouseMove({ clientX: x, clientY: y });
  const release = () => g.onMouseUp();
  return { g, emitted, press, move, release };
}

test('clicking a node (no movement) writes no layout', () => {
  const { emitted, press, move, release } = graph();
  press(20, 20); move(20, 20); release();
  assert.deepEqual(emitted, []);
});

test('dragging a node arranges the graph: every step gets its position', () => {
  const { emitted, press, move, release } = graph();
  press(20, 20); move(60, 50); release();
  assert.equal(emitted.length, 1);
  assert.deepEqual(emitted[0].layout, { a: { x: 50, y: 40 }, b: { x: 10, y: 120 } });
});
