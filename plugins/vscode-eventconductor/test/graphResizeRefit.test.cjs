const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// The graph follows its container: when the host widens (VS Code's Explorer hidden after the file
// is open) or narrows, the graph is fitted again — unless the reader zoomed or panned by hand, in
// which case their viewport is kept. The component is transpiled and driven without a browser.
const source = path.resolve(__dirname, '../../../modules/workflow-engine/frontend/src/eventconductor-workflow-graph.ts');
const { outputText } = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true },
});
const template = (strings, ...values) => ({ strings, values });
const exportsObject = {};
const storage = { getItem: () => null, setItem() {} };
vm.runInNewContext(outputText, {
  exports: exportsObject,
  window: { addEventListener() {}, removeEventListener() {}, localStorage: storage },
  localStorage: storage,
  CustomEvent: class { constructor(type, options) { this.type = type; Object.assign(this, options); } },
  require: name => {
    if (name === 'lit') return { LitElement: class {}, css: () => '', html: template, svg: template, nothing: null };
    if (name === 'lit/decorators.js') return { customElement: () => value => value, property: () => () => {}, state: () => () => {} };
    if (name === './neutralChrome') return {};
    throw new Error(`Unexpected component dependency: ${name}`);
  },
});
const Graph = exportsObject.MateuWorkflowElk;

/** A laid-out, already fitted graph in a 600×400 host whose canvas is the whole host. */
function fitted() {
  const svg = { getBoundingClientRect: () => ({ left: 0, top: 0 }) };
  const g = new Graph();
  Object.assign(g, {
    wf: { name: 'W', steps: [{ id: 'a', type: 'START' }, { id: 'b', type: 'END', preconditionStepId: 'a' }] },
    positions: { a: { x: 0, y: 0 }, b: { x: 1400, y: 0 } },
    svgEl: svg, elkPositioned: new Set(), readOnly: true, bodyFrames: [], layoutReady: true, layoutPending: false,
    minimapCollapsed: true, showsMinimap: () => false, renderRoot: { querySelector: () => null }, clearFocus() {},
  });
  g.onViewportResize({ w: 600, h: 400 }, { w: 600, h: 400 });
  g.didInitialFit = true;
  g.fitToView();
  return g;
}
const view = g => ({ k: g.zoomK, x: g.panX, y: g.panY });

test('a wider host re-fits an untouched graph', () => {
  const g = fitted();
  const before = view(g);
  g.onViewportResize({ w: 1200, h: 400 }, { w: 1200, h: 400 });
  assert.ok(g.zoomK > before.k, `zoom should grow with the panel (${before.k} → ${g.zoomK})`);
  assert.notDeepEqual(view(g), before);
});

test('a host resize keeps the viewport once the reader has zoomed', () => {
  const g = fitted();
  g.onWheel({ preventDefault() {}, clientX: 100, clientY: 100, deltaY: -200 });
  const zoomed = view(g);
  g.onViewportResize({ w: 1200, h: 400 }, { w: 1200, h: 400 });
  assert.deepEqual(view(g), zoomed);
});

test('a host resize keeps the viewport once the reader has panned', () => {
  const g = fitted();
  g.onCanvasMouseDown({ button: 0, clientX: 100, clientY: 100 });
  g.onPanMove({ clientX: 160, clientY: 130 });
  g.onPanUp();
  const panned = view(g);
  g.onViewportResize({ w: 1200, h: 400 }, { w: 1200, h: 400 });
  assert.deepEqual(view(g), panned);
});

test('Fit hands the viewport back to the container', () => {
  const g = fitted();
  g.onWheel({ preventDefault() {}, clientX: 100, clientY: 100, deltaY: -200 });
  g.fitToView();
  const refitted = view(g);
  g.onViewportResize({ w: 1200, h: 400 }, { w: 1200, h: 400 });
  assert.notDeepEqual(view(g), refitted);
});

test('the step panel narrowing the canvas (host unchanged) does not re-fit', () => {
  const g = fitted();
  const before = view(g);
  g.onViewportResize({ w: 300, h: 400 }, { w: 600, h: 400 });
  assert.deepEqual(view(g), before);
  assert.equal(g.viewW, 300);
});

test('no re-fit before the first fit', () => {
  const g = fitted();
  g.didInitialFit = false;
  const before = view(g);
  g.onViewportResize({ w: 1200, h: 400 }, { w: 1200, h: 400 });
  assert.deepEqual(view(g), before);
});
