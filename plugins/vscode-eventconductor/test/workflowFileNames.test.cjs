const assert = require('node:assert/strict');
const test = require('node:test');
const manifest = require('../package.json');

// Every place the extension recognises a workflow file must accept the same names: `*.ec.yaml` is
// the recommended one, `.ec` and `*.workflow.yaml` (and the `.yml` twins) stay supported.
const WORKFLOWS = ['order.ec', 'order.ec.yaml', 'order.ec.yml', 'order.workflow.yaml', 'order.workflow.yml', 'Order.EC.YAML'];
const OTHERS = ['order.yaml', 'contact.ecform', 'pricing.ecrule', 'send.ectask', 'order.ec.json', 'spec.yaml'];

/** The `resourceFilename =~ /.../flags` regex of a when clause. */
function filenameRegex(when) {
  const m = /resourceFilename =~ \/(.+?)\/([a-z]*)(?:\s|$)/.exec(when);
  assert.ok(m, `when clause does not match on the file name: ${when}`);
  return new RegExp(m[1], m[2]);
}

function glob(pattern) {
  return new RegExp('^' + pattern.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*') + '$', 'i');
}

test('Run Workflow is offered in the explorer for every workflow file name, and only for those', () => {
  const entry = manifest.contributes.menus['explorer/context'].find(m => m.command === 'eventconductor.runWorkflow');
  assert.doesNotMatch(entry.when, /resourceExtname/, 'resourceExtname of order.ec.yaml is .yaml');
  assert.match(entry.when, /isWorkspaceTrusted/);
  const regex = filenameRegex(entry.when);
  for (const name of WORKFLOWS) assert.ok(regex.test(name), name);
  for (const name of OTHERS) assert.ok(!regex.test(name), name);
});

test('no menu entry recognises a workflow by the bare .ec extension alone', () => {
  for (const [menu, entries] of Object.entries(manifest.contributes.menus)) {
    for (const entry of entries) assert.doesNotMatch(entry.when || '', /resourceExtname == \.ec(\s|$|&)/, `${menu}: ${entry.command}`);
  }
});

test('the graph editor and the YAML schema cover the same workflow file names', () => {
  const graph = manifest.contributes.customEditors.find(e => e.viewType === 'eventconductor.graphEditor');
  const editorGlobs = graph.selector.map(s => glob(s.filenamePattern));
  const schema = manifest.contributes.yamlValidation.find(v => v.url === './schema/ec.schema.json');
  const schemaGlobs = schema.fileMatch.map(glob);
  for (const name of WORKFLOWS) {
    assert.ok(editorGlobs.some(g => g.test(name)), `graph editor: ${name}`);
    assert.ok(schemaGlobs.some(g => g.test(name)), `schema: ${name}`);
  }
});
