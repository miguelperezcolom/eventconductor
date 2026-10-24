const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const ts = require('typescript');

// The properties panel's USER_TASK and sensitive-output fields, without a browser: `html` keeps its
// interpolated values, so a test can read what a field shows and fire its change handler.
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

/** Every field the renderer produced, by label: the template it wraps. */
function fieldsOf(render) {
  const fields = {};
  const field = (label, body) => { fields[label] = body; return { label }; };
  render(field);
  return fields;
}

/** The value a field's input shows and its change handler, from the template's interpolations. */
function input(body) {
  const at = name => body.strings.findIndex(part => part.trimEnd().endsWith(name));
  const valueAt = at('.value="');
  const changeAt = at('@change="');
  const checkedAt = at('?checked="');
  return {
    value: valueAt >= 0 ? body.values[valueAt] : undefined,
    checked: checkedAt >= 0 ? body.values[checkedAt] : undefined,
    change: (value, checked) => body.values[changeAt]({ target: { value, checked } }),
  };
}

function panel(step) {
  const changes = [];
  const component = { readOnly: false, updateStep: (id, patch) => changes.push([id, plain(patch)]) };
  return { changes, component };
}

test('a USER_TASK shows its assignment, field by field, and its escalation read-only', () => {
  const step = {
    id: 'approve', type: 'USER_TASK', formId: 'approval',
    assignment: { assignee: '${owner}', candidateGroups: ['approvers', 'leads'], candidateUsers: '${reviewers}', priority: 80, dueDate: 'P3D' },
    escalation: [
      { after: 'PT48H', to: { candidateGroups: ['ops'] } },
      { after: 'P2D', action: 'notify' },
      { after: 'P3D', escalateTo: 'manual-review' },
    ],
  };
  const { component } = panel(step);
  const fields = fieldsOf(field => prototype.renderAssignmentFields.call(component, step, false, field));

  assert.deepEqual(Object.keys(fields), ['Assignee', 'Candidate users', 'Candidate groups', 'Task priority', 'Due', 'Follow up', 'Escalation']);
  assert.equal(input(fields['Assignee']).value, '${owner}');
  assert.equal(input(fields['Candidate groups']).value, 'approvers, leads');
  assert.equal(input(fields['Candidate users']).value, '${reviewers}', 'one template giving the list stays as typed');
  assert.equal(input(fields['Task priority']).value, '80');
  assert.equal(input(fields['Due']).value, 'P3D');
  assert.equal(input(fields['Follow up']).value, '');
  assert.equal(input(fields['Escalation']).value, 'PT48H reassign → ops · P2D notify · P3D escalate → manual-review');
});

test('editing the assignment keeps the rest of it, types lists, numbers and templates, and drops it when empty', () => {
  const step = { id: 'approve', type: 'USER_TASK', assignment: { assignee: 'alice', priority: 50 } };
  const { changes, component } = panel(step);
  const fields = fieldsOf(field => prototype.renderAssignmentFields.call(component, step, false, field));

  input(fields['Candidate groups']).change(' approvers , leads ');
  input(fields['Candidate users']).change('${reviewers}');
  input(fields['Task priority']).change('90');
  input(fields['Task priority']).change('${urgency}');
  input(fields['Assignee']).change('  ');
  assert.deepEqual(changes, [
    ['approve', { assignment: { assignee: 'alice', priority: 50, candidateGroups: ['approvers', 'leads'] } }],
    ['approve', { assignment: { assignee: 'alice', priority: 50, candidateUsers: '${reviewers}' } }],
    ['approve', { assignment: { assignee: 'alice', priority: 90 } }],
    ['approve', { assignment: { assignee: 'alice', priority: '${urgency}' } }],
    ['approve', { assignment: { priority: 50 } }],
  ]);

  const bare = { id: 'review', type: 'USER_TASK', assignment: { assignee: 'bob' } };
  const second = panel(bare);
  const bareFields = fieldsOf(field => prototype.renderAssignmentFields.call(second.component, bare, false, field));
  assert.equal(bareFields['Escalation'], undefined, 'no escalation, no field');
  input(bareFields['Assignee']).change('');
  assert.deepEqual(second.changes, [['review', { }]], 'an assignment with nothing left is dropped from the step');
});

test('any step can mark its output variables sensitive in the advanced section', () => {
  const step = { id: 'tokenize', type: 'ACTION', outputVariables: ['pan', 'cvv'], sensitiveOutputs: true };
  const { changes, component } = panel(step);
  const fields = fieldsOf(field => prototype.renderAdvancedFields.call(component, step, false, field));

  assert.equal(input(fields['Output variables']).value, 'pan, cvv');
  assert.equal(input(fields['Sensitive outputs']).checked, true);
  input(fields['Sensitive outputs']).change(undefined, false);
  input(fields['Output variables']).change('pan');
  assert.deepEqual(changes, [['tokenize', { }], ['tokenize', { outputVariables: ['pan'] }]]);

  const child = { id: 'kyc', type: 'PROCESS', outputVariables: ['score'] };
  const childFields = fieldsOf(field => prototype.renderAdvancedFields.call(panel(child).component, child, false, field));
  assert.equal(childFields['Output variables'], undefined, 'a PROCESS shows them with the child workflow');
  assert.equal(input(childFields['Sensitive outputs']).checked, false);

  const join = { id: 'join', type: 'JOIN' };
  const joinFields = fieldsOf(field => prototype.renderAdvancedFields.call(panel(join).component, join, false, field));
  assert.equal(joinFields['Output variables'], undefined, 'a step that writes nothing has none to declare');
  assert.equal(joinFields['Sensitive outputs'], undefined);
});
