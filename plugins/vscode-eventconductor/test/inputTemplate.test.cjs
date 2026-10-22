const assert = require('node:assert/strict');
const test = require('node:test');
const { workflowInputTemplate, parseRunWorkflow } = require('../out/inputTemplate');
const infer = steps => workflowInputTemplate({steps});

test('YAML and JSON workflow documents share parsing and invalid documents are rejected',()=>{
  assert.deepEqual(parseRunWorkflow('name: Example\nsteps: []'),{name:'Example',steps:[]});
  assert.deepEqual(parseRunWorkflow('{"steps":[]}'),{steps:[]});
  for(const value of ['[]','null','name: Missing steps']) assert.throws(()=>parseRunWorkflow(value),/steps array/);
});

test('a workflow with no referenced inputs starts with an empty object',()=>{
  assert.deepEqual(infer([{id:'start',type:'START'},{id:'end',type:'END',preconditionStepId:'start'}]),{});
});

test('agent input references produce blank fields without copying literals or output variables',()=>{
  assert.deepEqual(infer([
    {id:'assess',type:'AGENT',agent:{taskPrompt:'Assess ${caseId} for ${customer.name}',input:{id:'${caseId}',constant:'private-literal'},outputVariable:'assessment'}},
    {id:'reply',type:'REPLY',preconditionStepId:'assess',replyVariables:['assessment']},
  ]),{caseId:'',customer:{name:''}});
});

test('conditions, HTTP bodies and expression functions find inputs without treating literals as names',()=>{
  assert.deepEqual(infer([{id:'charge',type:'HTTP_CALL',preconditionExpression:"amount > 100 && currency == 'EUR'",
    http:{url:'https://example.invalid/${bookingId}',body:{id:'${customer["id"]}',price:'${math:round(amount)}'},headers:{Authorization:'private-header'}},
    replyExpression:"{'label': express ? title : fallback, 'total': amount}"}]),
    {amount:'',bookingId:'',currency:'',customer:{id:''},express:'',fallback:'',title:''});
});

test('HTTP/LLM outputs and event variables produced upstream are excluded from later reads',()=>{
  assert.deepEqual(infer([
    {id:'http',http:{output:{chargeId:'json.id'}}},
    {id:'llm',preconditionStepId:'http',llm:{prompt:'Check ${chargeId}',outputVariable:'answer',output:{category:'json.category'}}},
    {id:'wait',preconditionStepId:'llm',onEvent:{variables:{paymentId:'${data.id}'}}},
    {id:'send',preconditionStepId:'wait',replyTemplate:{id:'${paymentId}',category:'${category}',answer:'${answer}'}},
  ]),{});
});

test('a variable read before a step produces it is still an initial input',()=>{
  assert.deepEqual(infer([
    {id:'first',agent:{input:'${amount}',outputVariable:'amount'}},
    {id:'later',preconditionStepId:'first',replyTemplate:'${amount}'},
  ]),{amount:''});
});

test('forEach input arrays retain item shape and omit iteration-scoped bindings',()=>{
  assert.deepEqual(infer([{id:'batch',forEach:{items:'order.lines',as:'line',indexAs:'lineIndex',output:'results'},
    agent:{taskPrompt:'${line.sku} ${line.quantity} ${lineIndex}',input:{customer:'${customerId}'},outputVariable:'assessment'},
    repeat:{until:"assessment.ok == true || iteration > 2"}}]),{customerId:'',order:{lines:[{quantity:'',sku:''}]}});
});

test('arrays produced upstream are not requested again when their items are referenced',()=>{
  assert.deepEqual(infer([
    {id:'lookup',llm:{outputVariable:'orders'}},
    {id:'batch',preconditionStepId:'lookup',forEach:{items:'orders',as:'order'},agent:{input:'${order.id}'}},
  ]),{});
});

test('schedules and triggers contribute field names with empty values and no event-context inputs',()=>{
  assert.deepEqual(workflowInputTemplate({steps:[],schedules:[{variables:{nights:5}}],
    triggers:[{filter:"data.kind == 'demo'",variables:{bookingId:'${data.id}',customer:{private:'value'}}}]}),
    {bookingId:'',customer:'',nights:''});
});

test('escaped templates, engine bindings and quoted strings do not become initial inputs',()=>{
  assert.deepEqual(infer([{id:'x',agent:{input:['$${literal}',"${ {'ok': caseId, 'nested': {'key': customer.id}} }",'${process.id} ${step.id} ${now} ${businessKey}']}}]),
    {caseId:'',customer:{id:''}});
});

test('timers, named locks and message correlation contribute their referenced fields',()=>{
  assert.deepEqual(workflowInputTemplate({processLock:{key:'bookingId'},steps:[
    {id:'timer',untilVariable:'dueAt',deadline:{date:'${checkoutDate}'}},
    {id:'message',correlationExpression:'bookingId',messageVariables:['status']},
  ]}),{bookingId:'',checkoutDate:'',dueAt:'',status:''});
});

test('prototype paths, recursive YAML aliases and overly deep paths stay bounded',()=>{
  const template={value:'${safe} ${__proto__.x} ${constructor.prototype.y}'}; template.self=template;
  const result=infer([{id:'x',agent:{input:template}}]);
  assert.deepEqual(result,{safe:''}); assert.equal({}.x,undefined);
  assert.deepEqual(infer([{id:'x',agent:{input:'${'+Array(30).fill('level').join('.')+'}'}}]),{});
});
