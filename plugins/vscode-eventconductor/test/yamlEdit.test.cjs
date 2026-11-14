const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const { applyDefinitionEdit, parseDefinition } = require('../out/yamlEdit');

// A definition the way people write them: comments everywhere, mixed quoting, a flow list, a block
// scalar, blank lines between steps.
const SOURCE = [
  '# Orders: what happens to a paid order.',
  'name: "Order processing"   # shown in the console',
  "version: 1",
  '',
  'steps:',
  '  # the entry point',
  '  - id: start',
  '    type: START',
  "    name: 'Start'",
  '',
  '  - id: charge',
  '    type: ACTION',
  '    name: Charge card   # the PSP',
  '    topic: payment-service',
  '    preconditions: [{stepId: start}]',
  '    description: |',
  '      Charges the card.',
  '      Twice if needed.',
  '',
  '  # done',
  '  - id: end',
  '    type: END',
  '    preconditionStepId: charge',
  '',
].join('\n');

/** Applies `change` to the parsed definition the way an editor would, and writes it back. */
function edit(source, change) {
  const value = parseDefinition(source);
  change(value);
  const out = applyDefinitionEdit(source, value);
  assert.deepEqual(parseDefinition(out), JSON.parse(JSON.stringify(value)), 'the file says what the editor asked for');
  return out;
}

/** The lines of `before` that are missing from `after` (in order), i.e. what an edit touched. */
function removedLines(before, after) {
  const a = before.split('\n'), b = after.split('\n');
  const lcs = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) for (let j = b.length - 1; j >= 0; j--)
    lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
  const removed = [];
  let i = 0, j = 0;
  while (i < a.length) {
    if (j < b.length && a[i] === b[j]) { i++; j++; }
    else if (j < b.length && lcs[i][j + 1] >= lcs[i + 1][j]) j++;
    else removed.push(a[i++]);
  }
  return removed;
}

test('a palette drop appends the step and leaves every other byte alone (no layout, no re-quoting)', () => {
  const out = edit(SOURCE, v => v.steps.push({ id: 'n1', type: 'ACTION', name: 'New Action' }));
  assert.equal(out, SOURCE + '\n  - id: n1\n    type: ACTION\n    name: New Action\n');
  assert.doesNotMatch(out, /layout/);
});

test('renaming changes the value only: its quotes and the comment after it stay', () => {
  const out = edit(SOURCE, v => { v.name = 'Orders'; v.steps[0].name = 'Begin'; v.steps[1].name = 'Charge the card'; });
  assert.deepEqual(removedLines(SOURCE, out), ['name: "Order processing"   # shown in the console', "    name: 'Start'", '    name: Charge card   # the PSP']);
  assert.match(out, /^name: "Orders"   # shown in the console$/m);
  assert.match(out, /^    name: 'Begin'$/m);
  assert.match(out, /^    name: Charge the card   # the PSP$/m);
});

test('deleting a step removes its lines (and the blank line it leaves) and nothing else', () => {
  const out = edit(SOURCE, v => { v.steps.splice(1, 1); v.steps[1].preconditionStepId = 'start'; });
  assert.equal(out, [
    '# Orders: what happens to a paid order.',
    'name: "Order processing"   # shown in the console',
    'version: 1',
    '',
    'steps:',
    '  # the entry point',
    '  - id: start',
    '    type: START',
    "    name: 'Start'",
    '',
    '  # done',
    '  - id: end',
    '    type: END',
    '    preconditionStepId: start',
    '',
  ].join('\n'));
});

test('a step removed with comments above it takes those comment lines along', () => {
  const out = edit(SOURCE, v => { v.steps.splice(2, 1); });
  assert.doesNotMatch(out, /# done/);
  assert.match(out, /# the entry point/);
});

test('rewiring edits the precondition lists in place, keeping a flow list a flow list', () => {
  const out = edit(SOURCE, v => {
    v.steps[1].preconditions.push({ stepId: 'end' });
    delete v.steps[2].preconditionStepId;
    v.steps[2].preconditions = [{ stepId: 'charge', expression: 'ok' }];
  });
  assert.match(out, /^    preconditions: \[\{stepId: start\}, \{stepId: end\}\]$/m);
  assert.match(out, /  - id: end\n    type: END\n    preconditions:\n      - stepId: charge\n        expression: ok\n$/);
  assert.equal(removedLines(SOURCE, out).length, 2);
});

test('a step inserted between others goes where the editor put it', () => {
  const out = edit(SOURCE, v => v.steps.splice(1, 0, { id: 'check', type: 'RULE' }));
  assert.ok(out.indexOf('id: check') > out.indexOf('id: start') && out.indexOf('id: check') < out.indexOf('id: charge'));
  assert.deepEqual(removedLines(SOURCE, out), []);
});

test('moving one node of an arranged graph changes only that node\'s coordinates', () => {
  const arranged = SOURCE + 'layout:\n  start: {x: 10, y: 20}\n  charge:\n    x: 200\n    y: 20\n  end:\n    x: 400\n    y: 20\n';
  const out = edit(arranged, v => { v.layout.charge = { x: 220, y: 40 }; });
  assert.deepEqual(removedLines(arranged, out), ['    x: 200', '    y: 20']);
  assert.match(out, /  charge:\n    x: 220\n    y: 40\n/);
});

test('a block scalar edited in place stays a block; a changed key keeps the rest of the step', () => {
  const out = edit(SOURCE, v => { v.steps[1].description = 'Charges the card.\nOnce.\n'; });
  assert.match(out, /    description: \|\n      Charges the card\.\n      Once\.\n\n  # done/);
  assert.deepEqual(removedLines(SOURCE, out), ['      Twice if needed.']);
});

test('the first key of a compact item can go: the next key moves up behind the dash', () => {
  const out = edit(SOURCE, v => { delete v.steps[0].id; });
  assert.match(out, /  # the entry point\n  - type: START\n    name: 'Start'\n/);
});

test('indentation and dash style of the file are followed for new content', () => {
  const flat = 'name: Flat\nsteps:\n- id: a\n  type: START\n';
  const out = edit(flat, v => { v.steps.push({ id: 'b', type: 'END', preconditions: [{ stepId: 'a' }] }); });
  assert.equal(out, flat + '- id: b\n  type: END\n  preconditions:\n  - stepId: a\n');
});

test('JSON files stay JSON, an empty file becomes YAML, an unchanged value leaves the text as is', () => {
  assert.equal(applyDefinitionEdit('{"name":"x","steps":[]}', { name: 'y', steps: [] }), '{\n  "name": "y",\n  "steps": []\n}\n');
  assert.equal(applyDefinitionEdit('', { name: 'New Workflow', steps: [] }), 'name: New Workflow\nsteps: []\n');
  assert.equal(applyDefinitionEdit(SOURCE, parseDefinition(SOURCE)), SOURCE);
});

test('what the targeted edit cannot express still comes out right (anchors, reordering)', () => {
  const anchored = 'defaults: &d\n  retries: 2\nname: A\nsteps:\n  - id: a\n    type: START\n    opts: *d\n  - id: b\n    type: END\n';
  const reordered = edit(anchored, v => { v.steps.reverse(); });
  assert.deepEqual(parseDefinition(reordered).steps.map(s => s.id), ['b', 'a']);
  const changed = edit(anchored, v => { v.steps[0].opts = { retries: 3 }; });
  assert.equal(parseDefinition(changed).steps[0].opts.retries, 3);
});

test('every example definition survives a no-op and a one-step edit with all its comment lines', () => {
  const dir = path.join(__dirname, '..', 'examples');
  for (const name of fs.readdirSync(dir).filter(f => f.endsWith('.ec.yaml'))) {
    const text = fs.readFileSync(path.join(dir, name), 'utf8');
    assert.equal(applyDefinitionEdit(text, parseDefinition(text)), text, name);
    const out = edit(text, v => v.steps.push({ id: 'zz-new', type: 'ACTION', name: 'New Action' }));
    assert.deepEqual(removedLines(text, out), [], name);
  }
});

// The IntelliJ plugin runs the same code in its webviews, from a committed browser bundle.
const intellijBundle = path.join(__dirname, '..', '..', 'intellij-eventconductor', 'src', 'main', 'resources', 'webview', 'ec-yaml-edit.js');

test('the IntelliJ webview bundle is up to date with src/yamlEdit.ts (npm run bundle-intellij)', () => {
  const esbuild = require('esbuild');
  const { options } = require('../scripts/bundle-intellij');
  const fresh = esbuild.buildSync({ ...options, write: false }).outputFiles[0].text;
  assert.equal(fs.readFileSync(intellijBundle, 'utf8'), fresh, 'run `npm run bundle-intellij` and commit the result');
});

test('the IntelliJ bundle defines window.ecYamlEdit and writes the same lossless edit', () => {
  const sandbox = { window: {} };
  sandbox.self = sandbox.window;
  vm.runInNewContext(fs.readFileSync(intellijBundle, 'utf8') + '\nwindow.ecYamlEdit = ecYamlEdit;', sandbox);
  const api = sandbox.window.ecYamlEdit;
  const value = api.parseDefinition(SOURCE);
  value.steps.push({ id: 'n1', type: 'ACTION', name: 'New Action' });
  assert.equal(api.applyDefinitionEdit(SOURCE, value), applyDefinitionEdit(SOURCE, value));
});
