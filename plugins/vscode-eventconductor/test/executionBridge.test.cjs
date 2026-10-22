const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const { EngineError, engineEndpoint, processId } = require('../out/engineClient');
const tick = () => new Promise(resolve => setImmediate(resolve));
const normal = x => JSON.parse(JSON.stringify(x));
function host({trusted=true, start, cancel, ready=true, flushEdits=async()=>{}}={}) {
  const commands = new Map(), posts=[], secrets=[], saved=[], submissions=[], cancellations=[], sessions=[], errors=[];
  let receive, dispose, viewState, text='{"id":"draft","steps":[]}';
  const uri = value => ({path:value,fsPath:value,toString:()=>value});
  const document = {uri:uri('/project/draft.ec'),getText:()=>text};
  const panel = {visible:true,title:'',webview:{cspSource:'https://webview.invalid',
    asWebviewUri:u=>u.toString(), postMessage:async m=>posts.push(normal(m)), onDidReceiveMessage:fn=>{receive=fn;}},
    onDidDispose:fn=>{dispose=fn;},onDidChangeViewState:fn=>{viewState=fn;}};
  const context={extensionUri:uri('/extension'),subscriptions:[],
    secrets:{get:async key=>{secrets.push(key);return JSON.stringify({authorization:'Bearer synthetic-private-token'});}},
    workspaceState:{get:()=>[],update:async (key,value)=>saved.push(normal(value))}};
  const vscode={Uri:{joinPath:(u,...parts)=>uri(path.posix.join(u.path,...parts))},ViewColumn:{Beside:2},
    commands:{registerCommand:(name,fn)=>{commands.set(name,fn);return {dispose(){}};}},
    workspace:{isTrusted:trusted,getConfiguration:()=>({get:()=> 'https://engine.invalid/base'}),
      openTextDocument:async()=>document,asRelativePath:()=> 'draft.ec'},
    window:{createWebviewPanel:()=>panel,showErrorMessage:message=>errors.push(message)},env:{clipboard:{writeText:async()=>{}}}};
  class Client {
    constructor(endpoint, credentials) { this.endpoint=engineEndpoint(endpoint);this.credentials=credentials; }
    async start(body,signal) {submissions.push(normal(body));return start?start(body,signal):{id:body.runId};}
    async graph(id) {return {process:{id,status:cancellations.includes(id)?'CANCELLED':'RUNNING'},steps:[]};}
    async cancel(id,signal) {cancellations.push(id);if(cancel)return cancel(id,signal);}
  }
  class Session {
    constructor(client,id,publish){this.id=id;this.publish=publish;this.state={rootId:id,currentId:id,following:true,graph:{process:{id,status:"RUNNING"}},history:[]};sessions.push(this);}
    async suspend(){this.visible=false;}
    async navigate(id,follow){this.state.currentId=id;this.state.following=follow;}
    setVisible(value){this.visible=value;} dispose(){this.disposed=true;}
    async refresh(){this.publish({...this.state,history:[],logs:[],warnings:[]});}
  }
  const exports={};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../out/execution.js'),'utf8'),{
    exports,Buffer,AbortController,URL,Error,
    require:name=>name==='vscode'?vscode:name==='node:crypto'?require('node:crypto'):name==='./inputTemplate'?require('../out/inputTemplate'):
      name==='./engineClient'?{EngineClient:Client,EngineError,engineEndpoint,processId}:
      name==='./executionSession'?{ExecutionSession:Session}:name==='./executionCancellation'?{ExecutionCancellation:class extends require('../out/executionCancellation').ExecutionCancellation { constructor(client,publish){super(client,publish,{pollMs:1,timeoutMs:100});} }}:(()=>{throw Error(name);})()
  });
  let controller;
  const opened=[];
  exports.registerWorkflowExecution(context,()=>document.uri,async(target,id)=>{
    opened.push({uri:target?.toString(),id});
    if(!controller) {
      controller=new exports.GraphExecutionController(context,panel,document,flushEdits);
      panel.webview.html=exports.executionMarkup();
      receive=m=>controller.message(m);
      if (ready) await receive({type:"ready"});
    }
    await controller.open(id);
  });
  return {opened,commands,posts,secrets,saved,submissions,cancellations,sessions,errors,panel,vscode,
    edit:value=>{text=value;},receive:async message=>{receive(message);await tick();await tick();},
    dispose:()=>dispose(),hide:()=>{panel.visible=false;viewState({webviewPanel:panel});}};
}

test('execution captures unsaved contents when Execute is pressed and keeps credentials in the host',async()=>{
  const h=host();await h.commands.get('eventconductor.runWorkflow')();await h.receive({type:'ready'});
  h.edit('{"id":"unsaved","steps":[]}');await h.receive({type:'startRun',variables:'{"amount":42}'});
  assert.equal(h.submissions.length,1);assert.equal(h.submissions[0].definition.id,'unsaved');
  assert.deepEqual(h.submissions[0].variables,{amount:42});assert.equal(h.secrets.length,1);
  assert.doesNotMatch(JSON.stringify(h.posts),/synthetic-private-token|authorization/);
  assert.doesNotMatch(JSON.stringify(h.saved),/amount|unsaved|synthetic-private-token/);
  assert.equal(h.opened.length,1);assert.doesNotMatch(h.panel.webview.html,/<eventconductor-workflow-graph/);
  h.hide();assert.equal(h.sessions[0].visible,false);h.dispose();assert.equal(h.sessions[0].disposed,true);
});

test('an uncertain POST retries the exact original ID, definition and variables',async()=>{
  let attempts=0;
  const h=host({start:async body=>{if(++attempts===1)throw new EngineError('Disconnected');return {id:body.runId};}});
  await h.commands.get('eventconductor.runWorkflow')();await h.receive({type:'startRun',variables:'{"n":1}'});
  assert.equal(h.posts.find(p=>p.type==='submissionError').uncertain,true);
  h.edit('{"id":"edited-later","steps":[]}');await h.receive({type:'startRun',variables:'{"n":2}'});
  assert.equal(h.submissions.length,1);await h.receive({type:'retrySubmission'});
  assert.equal(h.submissions.length,2);assert.deepEqual(h.submissions[0],h.submissions[1]);h.dispose();
});

test('workspace trust gates credential reads and is rechecked for each webview message',async()=>{
  const locked=host({trusted:false});await locked.commands.get('eventconductor.runWorkflow')();
  assert.equal(locked.secrets.length,0);assert.match(locked.errors[0],/Trust/);
  const h=host();await h.commands.get('eventconductor.runWorkflow')();h.vscode.workspace.isTrusted=false;
  await h.receive({type:'startRun',variables:'{}'});assert.equal(h.submissions.length,0);
  assert.match(h.posts.at(-1).message,/Trust/);h.dispose();
});

test('invalid inputs cannot start and closing the view aborts the pending HTTP submission',async()=>{
  let signal;
  const h=host({start:async(body,abort)=>{signal=abort;return new Promise((resolve,reject)=>abort.addEventListener('abort',()=>reject(new Error('Aborted'))));}});
  await h.commands.get('eventconductor.runWorkflow')();await h.receive({type:'startRun',variables:'[]'});
  assert.equal(h.submissions.length,0);await h.receive({type:'startRun',variables:'{}'});
  assert.equal(signal.aborted,false);h.dispose();assert.equal(signal.aborted,true);
  await tick();assert.equal(h.sessions.length,0);
});

test('each run view receives blank inputs from its current unsaved workflow',async()=>{
  const h=host();
  h.edit(JSON.stringify({name:'Draft',steps:[{id:'agent',agent:{input:{id:'${caseId}',account:'${customer.id}',constant:'private-example'}}}]}));
  await h.commands.get('eventconductor.runWorkflow')(); await h.receive({type:'ready'});
  assert.deepEqual(JSON.parse(h.posts.find(p=>p.type==='variables').value),{caseId:'',customer:{id:''}});
  assert.doesNotMatch(h.posts.find(p=>p.type==='variables').value,/private-example/);
  assert.match(h.panel.webview.html,/Reset template/);
  h.edit(JSON.stringify({name:'Changed',steps:[{id:'x',preconditionExpression:'amount > 10'}]}));
  await h.receive({type:'resetVariables'});
  assert.deepEqual(JSON.parse(h.posts.at(-1).value),{amount:''});
  assert.equal(h.submissions.length,0);
  h.dispose();
});

test('regenerating the template is ignored after an uncertain submission freezes the inputs',async()=>{
  const h=host({start:async()=>{throw new EngineError('Disconnected');}});
  await h.commands.get('eventconductor.runWorkflow')(); await h.receive({type:'ready'});
  await h.receive({type:'startRun',variables:'{"caseId":"typed-value"}'});
  const count=h.posts.filter(p=>p.type==='variables').length;
  h.edit(JSON.stringify({steps:[{id:'x',replyTemplate:'${anotherInput}'}]}));
  await h.receive({type:'resetVariables'}); await h.receive({type:'ready'});
  assert.equal(h.posts.filter(p=>p.type==='variables').length,count);
  assert.deepEqual(h.submissions[0].variables,{caseId:'typed-value'});
  h.dispose();
});


test('starting a second run uses fresh blank inputs and the updated draft in the same graph host',async()=>{
  const h=host();await h.commands.get('eventconductor.runWorkflow')();
  await h.receive({type:'startRun',variables:'{"first":"value"}'});
  const initial=h.sessions[0];
  h.edit('{"id":"edited","steps":[{"id":"new","type":"ACTION","replyTemplate":"${caseId}"}]}');
  await h.commands.get('eventconductor.runWorkflow')();
  assert.equal(h.sessions.length,1);assert.equal(initial.disposed,undefined);
  assert.deepEqual(JSON.parse(h.posts.filter(p=>p.type==='variables').at(-1).value),{caseId:''});
  await h.receive({type:'startRun',variables:'{"caseId":"synthetic"}'});
  assert.equal(h.submissions.length,2);assert.notEqual(h.submissions[0].runId,h.submissions[1].runId);
  assert.equal(h.submissions[1].definition.id,'edited');assert.equal(initial.disposed,true);
  assert.equal(h.opened[0].uri,h.opened[1].uri);h.dispose();
});

test('Execute waits for graph writes, rejects failed writes, and coalesces rapid duplicate clicks',async()=>{
  let release;
  const h=host({flushEdits:()=>new Promise(resolve=>{release=resolve;})});
  await h.commands.get('eventconductor.runWorkflow')();
  const first=h.receive({type:'startRun',variables:'{}'});
  await tick();assert.equal(h.submissions.length,0);
  h.edit('{"id":"after-write","steps":[]}');release();await first;
  assert.equal(h.submissions[0].definition.id,'after-write');h.dispose();
  const broken=host({flushEdits:async()=>{throw new Error('Graph write failed');}});
  await broken.commands.get('eventconductor.runWorkflow')();await broken.receive({type:'startRun',variables:'{}'});
  assert.equal(broken.submissions.length,0);assert.match(broken.posts.at(-1).message,/Graph write failed/);broken.dispose();
  const rapid=host();await rapid.commands.get('eventconductor.runWorkflow')();
  await Promise.all([rapid.receive({type:'startRun',variables:'{}'}),rapid.receive({type:'startRun',variables:'{}'})]);
  assert.equal(rapid.submissions.length,1);rapid.dispose();
});


test('opening a graph from a command waits for the webview handshake before sending its input form',async()=>{
  const h=host({ready:false});await h.commands.get('eventconductor.runWorkflow')();assert.equal(h.posts.length,0);
  await h.receive({type:'ready'});assert.equal(h.posts[0].type,'executionOpen');assert.equal(h.posts[1].type,'variables');h.dispose();
});


test('Stop uses only the monitored ID, ignores forged IDs, verifies CANCELLED and keeps the draft editable',async()=>{
  const h=host();await h.commands.get('eventconductor.runWorkflow')();await h.receive({type:'startRun',variables:'{}'});
  const id=h.sessions[0].state.currentId;
  await h.receive({type:'stopRun',id:'unrelated',url:'https://attacker.invalid'});
  await new Promise(resolve=>setTimeout(resolve,20));
  assert.deepEqual(h.cancellations,[id]);assert.equal(h.posts.filter(p=>p.type==='cancellation').at(-1).state.phase,'confirmed');
  assert.equal(h.sessions[0].visible,true);h.edit('{"id":"still-editable","steps":[]}');h.dispose();
});
test('Stop refuses history, uncertain submissions and untrusted messages',async()=>{
  const h=host();await h.commands.get('eventconductor.runWorkflow')();await h.receive({type:'startRun',variables:'{}'});
  h.sessions[0].state.following=false;await h.receive({type:'stopRun'});assert.equal(h.cancellations.length,0);
  h.sessions[0].state.following=true;h.vscode.workspace.isTrusted=false;await h.receive({type:'stopRun'});assert.equal(h.cancellations.length,0);h.dispose();
  const uncertain=host({start:async()=>{throw new EngineError('Disconnected');}});await uncertain.commands.get('eventconductor.runWorkflow')();await uncertain.receive({type:'startRun',variables:'{}'});
  await uncertain.receive({type:'stopRun'});assert.equal(uncertain.cancellations.length,0);uncertain.dispose();
});
